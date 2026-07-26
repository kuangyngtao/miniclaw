package com.clawkit.ops.loop.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable outbox for Feishu incident notifications.
 *
 * <p>M2-6. Each entry transitions through:
 * <pre>{@code
 *   PENDING → DISPATCHING → SENT
 *   PENDING → DISPATCHING → RETRYABLE_FAILED → PENDING (retry)
 *   PENDING → DISPATCHING → PERMANENT_FAILED
 * }</pre>
 *
 * <p>Idempotency key: {@code sha256(incidentId | reportVersion | chatId | eventType)}
 * converted to a stable Feishu UUID.
 *
 * <p>Feishu failure only changes Delivery state — never changes
 * Incident, Discovery, or Diagnosis state.
 */
public final class NotificationOutbox {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    public enum State {
        PENDING, DISPATCHING, SENT, RETRYABLE_FAILED, PERMANENT_FAILED
    }

    public enum EventType {
        INITIAL_REPORT, STATUS_UPDATE
    }

    public record Entry(
        String idempotencyKey,
        String incidentId,
        String reportVersion,
        String chatIdHash,       // sha256 of chatId, never the raw value
        EventType eventType,
        State state,
        String feishuMessageId,  // null until SENT
        String failureReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private final Path outboxDir;

    public NotificationOutbox(Path outboxDir) {
        this.outboxDir = outboxDir;
    }

    /**
     * Generate a stable idempotency key as a UUID.
     */
    public static String idempotencyKey(String incidentId, String reportVersion,
                                        String chatId, EventType eventType) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(incidentId.getBytes(StandardCharsets.UTF_8));
            md.update("|".getBytes(StandardCharsets.UTF_8));
            md.update(reportVersion.getBytes(StandardCharsets.UTF_8));
            md.update("|".getBytes(StandardCharsets.UTF_8));
            md.update(chatId.getBytes(StandardCharsets.UTF_8));
            md.update("|".getBytes(StandardCharsets.UTF_8));
            md.update(eventType.name().getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            // Convert first 16 bytes to a stable UUID
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (digest[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (digest[i] & 0xff);
            // Set UUID version 4 and variant
            msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
            lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
            return new UUID(msb, lsb).toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Hash a chatId for logging. */
    public static String hashChatId(String chatId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(chatId.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "error";
        }
    }

    /** Create or load a pending entry. */
    public Entry upsertPending(String incidentId, String reportVersion,
                                String chatId, EventType eventType) throws IOException {
        String key = idempotencyKey(incidentId, reportVersion, chatId, eventType);
        Entry existing = findByKey(key);
        if (existing != null) {
            if (existing.state() == State.SENT) return existing; // already sent
            if (existing.state() == State.PERMANENT_FAILED) return existing; // don't retry
        }
        String chatHash = hashChatId(chatId);
        Entry entry = new Entry(key, incidentId, reportVersion, chatHash,
            eventType, State.PENDING, null, null, 0,
            Instant.now(), Instant.now());
        persist(entry);
        return entry;
    }

    /** Mark an entry as DISPATCHING. */
    public Entry markDispatching(Entry entry) throws IOException {
        Entry updated = new Entry(entry.idempotencyKey(), entry.incidentId(),
            entry.reportVersion(), entry.chatIdHash(), entry.eventType(),
            State.DISPATCHING, entry.feishuMessageId(), null,
            entry.attemptCount() + 1, entry.createdAt(), Instant.now());
        persist(updated);
        return updated;
    }

    /** Mark an entry as SENT with the Feishu message_id. */
    public Entry markSent(Entry entry, String feishuMessageId) throws IOException {
        Entry updated = new Entry(entry.idempotencyKey(), entry.incidentId(),
            entry.reportVersion(), entry.chatIdHash(), entry.eventType(),
            State.SENT, feishuMessageId, null,
            entry.attemptCount(), entry.createdAt(), Instant.now());
        persist(updated);
        return updated;
    }

    /** Mark an entry as RETRYABLE_FAILED. */
    public Entry markRetryableFailed(Entry entry, String reason) throws IOException {
        Entry updated = new Entry(entry.idempotencyKey(), entry.incidentId(),
            entry.reportVersion(), entry.chatIdHash(), entry.eventType(),
            State.RETRYABLE_FAILED, entry.feishuMessageId(), reason,
            entry.attemptCount(), entry.createdAt(), Instant.now());
        persist(updated);
        return updated;
    }

    /** Mark an entry as PERMANENT_FAILED. */
    public Entry markPermanentFailed(Entry entry, String reason) throws IOException {
        Entry updated = new Entry(entry.idempotencyKey(), entry.incidentId(),
            entry.reportVersion(), entry.chatIdHash(), entry.eventType(),
            State.PERMANENT_FAILED, entry.feishuMessageId(), reason,
            entry.attemptCount(), entry.createdAt(), Instant.now());
        persist(updated);
        return updated;
    }

    /** Find an entry by its idempotency key. */
    public Entry findByKey(String idempotencyKey) throws IOException {
        Path file = outboxDir.resolve(idempotencyKey + ".json");
        if (!Files.exists(file)) return null;
        return MAPPER.readValue(file.toFile(), Entry.class);
    }

    /** List all entries, newest first. */
    public List<Entry> listAll() throws IOException {
        if (!Files.exists(outboxDir)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try (var s = Files.list(outboxDir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    entries.add(MAPPER.readValue(f.toFile(), Entry.class));
                } catch (Exception e) {
                    log.warn("Corrupt outbox entry: {}", f.getFileName());
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::updatedAt).reversed());
        return entries;
    }

    private void persist(Entry entry) throws IOException {
        Files.createDirectories(outboxDir);
        Path target = outboxDir.resolve(entry.idempotencyKey() + ".json");
        Path tmp = outboxDir.resolve(entry.idempotencyKey() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entry);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }
}
