package com.clawkit.ops.loop.notify;

import com.clawkit.ops.loop.report.FeishuSummaryRenderer;
import com.clawkit.ops.loop.report.HumanIncidentReport;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends incident notifications to a fixed Feishu chat via a durable outbox.
 *
 * <p>M2-6. Architecture:
 * <ul>
 *   <li>{@link FeishuMessageSender} is a minimal functional interface — the
 *       actual Feishu HTTP calls live in {@code clawkit-im} and are wired
 *       at composition time.</li>
 *   <li>{@link NotificationOutbox} manages delivery state in ops-loop.</li>
 *   <li>First report → new message; subsequent reportVersion → reply.</li>
 *   <li>Feishu failure → only Delivery state changes, never Incident.</li>
 * </ul>
 *
 * <p>Security:
 * <ul>
 *   <li>Logs only chat hash — never raw chat_id, message_id, token</li>
 *   <li>Fixed chat_id — not overridable by Agent or Incident</li>
 *   <li>Does NOT read messages, create groups, add users, or trigger repair</li>
 * </ul>
 */
public final class OpsFeishuNotifier {

    private static final Logger log = LoggerFactory.getLogger(OpsFeishuNotifier.class);

    /** Minimal Feishu send abstraction. Implemented in clawkit-im. */
    @FunctionalInterface
    public interface FeishuMessageSender {
        /** Send a message and return the Feishu message_id. */
        String send(String chatId, String content, String idempotencyKey) throws IOException;
    }

    /** Minimal Feishu reply abstraction. */
    @FunctionalInterface
    public interface FeishuReplySender {
        /** Reply to a message and return the reply message_id. */
        String reply(String messageId, String content, String idempotencyKey) throws IOException;
    }

    private final FeishuMessageSender messageSender;
    private final FeishuReplySender replySender;
    private final String chatId;
    private final NotificationOutbox outbox;

    public OpsFeishuNotifier(FeishuMessageSender messageSender,
                             FeishuReplySender replySender,
                             String chatId, NotificationOutbox outbox) {
        this.messageSender = messageSender;
        this.replySender = replySender;
        this.chatId = chatId;
        this.outbox = outbox;
    }

    /**
     * Notify about an incident report.
     *
     * @return the outbox entry
     */
    public NotificationOutbox.Entry notify(HumanIncidentReport report,
                                            NotificationOutbox.EventType eventType) {
        String reportVersion = report.reportVersion();
        String incidentId = report.incidentId();

        try {
            // Check idempotency
            String key = NotificationOutbox.idempotencyKey(
                incidentId, reportVersion, chatId, eventType);
            NotificationOutbox.Entry existing = outbox.findByKey(key);
            if (existing != null && existing.state() == NotificationOutbox.State.SENT) {
                log.info("[feishu-notify] already sent: incident={} version={} chat={}",
                    incidentId, reportVersion, NotificationOutbox.hashChatId(chatId));
                return existing;
            }
            if (existing != null && existing.state() == NotificationOutbox.State.PERMANENT_FAILED) {
                log.warn("[feishu-notify] permanent failure, not retrying: incident={}", incidentId);
                return existing;
            }

            // Upsert pending
            NotificationOutbox.Entry entry = outbox.upsertPending(
                incidentId, reportVersion, chatId, eventType);

            // Check for previous sent entry → reply
            NotificationOutbox.Entry previousSent = findPreviousSent(incidentId);
            String content = FeishuSummaryRenderer.render(report);
            String idempotencyKey = entry.idempotencyKey();

            // Dispatch
            entry = outbox.markDispatching(entry);
            String messageId;
            try {
                if (previousSent != null && previousSent.feishuMessageId() != null
                    && replySender != null) {
                    messageId = replySender.reply(
                        previousSent.feishuMessageId(), content, idempotencyKey);
                } else {
                    messageId = messageSender.send(chatId, content, idempotencyKey);
                }
                entry = outbox.markSent(entry, messageId);
                log.info("[feishu-notify] sent: incident={} version={}",
                    incidentId, reportVersion);
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "unknown";
                if (isRetryable(msg)) {
                    entry = outbox.markRetryableFailed(entry, msg);
                    log.warn("[feishu-notify] retryable: incident={}", incidentId);
                } else {
                    entry = outbox.markPermanentFailed(entry, msg);
                    log.error("[feishu-notify] permanent: incident={}", incidentId);
                }
            }

            return entry;
        } catch (Exception e) {
            log.error("[feishu-notify] unexpected: incident={}", incidentId, e);
            try {
                return outbox.upsertPending(incidentId, reportVersion, chatId, eventType);
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    private NotificationOutbox.Entry findPreviousSent(String incidentId) throws IOException {
        return outbox.listAll().stream()
            .filter(e -> e.incidentId().equals(incidentId))
            .filter(e -> e.state() == NotificationOutbox.State.SENT)
            .filter(e -> e.feishuMessageId() != null)
            .findFirst().orElse(null);
    }

    static boolean isRetryable(String errorMessage) {
        if (errorMessage == null) return false;
        String m = errorMessage.toLowerCase();
        if (m.contains("code=429") || m.contains("rate limit")) return true;
        if (m.contains("code=5") || m.contains("server error")) return true;
        if (m.contains("timeout") || m.contains("timed out")) return true;
        if (m.contains("connection refused")) return true;
        if (m.contains("code=400") || m.contains("code=401")
            || m.contains("code=403") || m.contains("code=404")) return false;
        return true;
    }

    public NotificationOutbox outbox() { return outbox; }
}
