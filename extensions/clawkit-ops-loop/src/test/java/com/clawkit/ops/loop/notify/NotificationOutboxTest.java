package com.clawkit.ops.loop.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationOutboxTest {

    private Path outboxDir;
    private NotificationOutbox outbox;

    @BeforeEach
    void setUp() throws Exception {
        outboxDir = Files.createTempDirectory("outbox-test-");
        outbox = new NotificationOutbox(outboxDir);
    }

    // ── Idempotency key ──

    @Test void idempotencyKeyIsStable() {
        String k1 = NotificationOutbox.idempotencyKey(
            "inc-1", "v1", "chat-123", NotificationOutbox.EventType.INITIAL_REPORT);
        String k2 = NotificationOutbox.idempotencyKey(
            "inc-1", "v1", "chat-123", NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(k1).isEqualTo(k2);
    }

    @Test void differentIncidentProducesDifferentKey() {
        String k1 = NotificationOutbox.idempotencyKey(
            "inc-1", "v1", "chat", NotificationOutbox.EventType.INITIAL_REPORT);
        String k2 = NotificationOutbox.idempotencyKey(
            "inc-2", "v1", "chat", NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test void differentVersionProducesDifferentKey() {
        String k1 = NotificationOutbox.idempotencyKey(
            "inc-1", "v1", "chat", NotificationOutbox.EventType.INITIAL_REPORT);
        String k2 = NotificationOutbox.idempotencyKey(
            "inc-1", "v2", "chat", NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(k1).isNotEqualTo(k2);
    }

    // ── Outbox state machine ──

    @Test void pendingToSentTransition() throws Exception {
        var entry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.PENDING);

        entry = outbox.markDispatching(entry);
        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.DISPATCHING);
        assertThat(entry.attemptCount()).isEqualTo(1);

        entry = outbox.markSent(entry, "msg-456");
        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.SENT);
        assertThat(entry.feishuMessageId()).isEqualTo("msg-456");
    }

    @Test void upsertPendingIsIdempotentForSentEntry() throws Exception {
        var entry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        outbox.markDispatching(entry);
        outbox.markSent(entry, "msg-1");

        // Re-upserting should return the SENT entry
        var reEntry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(reEntry.state()).isEqualTo(NotificationOutbox.State.SENT);
    }

    @Test void retryableFailureCanBeRetried() throws Exception {
        var entry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        entry = outbox.markDispatching(entry);
        entry = outbox.markRetryableFailed(entry, "timeout");

        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.RETRYABLE_FAILED);
        // Can be re-dispatched
        entry = outbox.markDispatching(entry);
        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.DISPATCHING);
        assertThat(entry.attemptCount()).isEqualTo(2);
    }

    @Test void permanentFailureIsNotRetried() throws Exception {
        var entry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        outbox.markDispatching(entry);
        outbox.markPermanentFailed(entry, "code=403");

        var reEntry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(reEntry.state()).isEqualTo(NotificationOutbox.State.PERMANENT_FAILED);
    }

    @Test void chatIdIsHashedNotStored() throws Exception {
        var entry = outbox.upsertPending("inc-1", "v1", "oc_abc123def456",
            NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(entry.chatIdHash()).isNotEqualTo("oc_abc123def456");
        assertThat(entry.chatIdHash()).hasSize(16); // 8 bytes hex
    }

    // ── Persistence ──

    @Test void entriesPersistAcrossInstances() throws Exception {
        var entry = outbox.upsertPending("inc-1", "v1", "chat-123",
            NotificationOutbox.EventType.INITIAL_REPORT);
        outbox.markDispatching(entry);
        outbox.markSent(entry, "msg-99");

        // New outbox instance reading same directory
        var outbox2 = new NotificationOutbox(outboxDir);
        var found = outbox2.findByKey(entry.idempotencyKey());
        assertThat(found).isNotNull();
        assertThat(found.state()).isEqualTo(NotificationOutbox.State.SENT);
        assertThat(found.feishuMessageId()).isEqualTo("msg-99");
    }

    // ── Error classification ──

    @Test void retryableErrors() {
        assertThat(OpsFeishuNotifier.isRetryable("code=429 rate limit")).isTrue();
        assertThat(OpsFeishuNotifier.isRetryable("code=503 server error")).isTrue();
        assertThat(OpsFeishuNotifier.isRetryable("connection timed out")).isTrue();
    }

    @Test void permanentErrors() {
        assertThat(OpsFeishuNotifier.isRetryable("code=400 bad request")).isFalse();
        assertThat(OpsFeishuNotifier.isRetryable("code=401 unauthorized")).isFalse();
        assertThat(OpsFeishuNotifier.isRetryable("code=403 forbidden")).isFalse();
        assertThat(OpsFeishuNotifier.isRetryable("code=404 not found")).isFalse();
    }

    // ── R4: Structured exception classification ──

    @Test void feishuApiExceptionRetryableClassification() {
        // Simulate what FeishuApiException would do (avoids cross-module dep)
        // 429 → retryable
        assertThat(OpsFeishuNotifier.isRetryableException(
            new java.io.IOException("429"))).isTrue();
        // 503 → retryable
        assertThat(OpsFeishuNotifier.isRetryableException(
            new java.io.IOException("503"))).isTrue();
        // timeout → retryable
        assertThat(OpsFeishuNotifier.isRetryableException(
            new java.io.IOException("connection timed out"))).isTrue();
        // 400 → permanent
        assertThat(OpsFeishuNotifier.isRetryableException(
            new java.io.IOException("400 bad request"))).isFalse();
        // 401 → permanent
        assertThat(OpsFeishuNotifier.isRetryableException(
            new java.io.IOException("401 unauthorized"))).isFalse();
    }

    // ── R4: Outbox CAS prevents concurrent double-send ──

    @Test void outboxCasPreventsOverwritingSent() throws Exception {
        var entry = outbox.upsertPending("inc-cas", "v1", "chat-cas",
            NotificationOutbox.EventType.INITIAL_REPORT);
        outbox.markDispatching(entry);
        outbox.markSent(entry, "msg-done");

        // Simulate a stale dispatcher trying to mark as retryable
        // (CAS should prevent overwriting SENT with RETRYABLE_FAILED)
        var current = outbox.findByKey(entry.idempotencyKey());
        assertThat(current.state()).isEqualTo(NotificationOutbox.State.SENT);
        // Marking retryable on a SENT entry: CAS rejects silently
        outbox.markRetryableFailed(entry, "stale");
        var after = outbox.findByKey(entry.idempotencyKey());
        assertThat(after.state()).isEqualTo(NotificationOutbox.State.SENT);
    }

    // ── R4: Chinese renderer doesn't leak sensitive data ──

    @Test void chineseFeishuRendererNoSensitiveLeak() {
        var report = new com.clawkit.ops.loop.report.HumanIncidentReport(
            "1", "1", null, "SYNTHETIC_BUSINESS_DATA",
            "inc-cn", "inc-cn", "测试场景", java.time.Instant.now(),
            java.time.Instant.now(),
            com.clawkit.ops.loop.report.HumanIncidentReport.IncidentStatus.ACTIVE,
            com.clawkit.ops.loop.report.HumanIncidentReport.DiagnosisConfidence.PROBABLE,
            "HOT_ACCOUNT_CONTENTION", 0.85, "摘要", "影响", "活跃",
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(),
            java.util.List.of("ESCALATE"), true, "需人工确认", false);
        String feishu = com.clawkit.ops.loop.report.FeishuSummaryRenderer.render(report);
        assertThat(feishu).contains("事故报告");
        assertThat(feishu).contains("很可能");  // PROBABLE
        assertThat(feishu).contains("升级人工处理");
        assertThat(feishu).doesNotContain("fixture-control-only");
        assertThat(feishu).doesNotContain("CLAWKIT_API_KEY");
        assertThat(feishu).doesNotContain("122.51.51");
    }

    // ── Notifier mock test ──

    @Test void notifierUsesOutbox() throws Exception {
        var sentContent = new AtomicReference<String>();
        var sentChatId = new AtomicReference<String>();

        OpsFeishuNotifier.FeishuMessageSender sender = (chatId, content, key) -> {
            sentChatId.set(chatId);
            sentContent.set(content);
            return "msg-sent-1";
        };

        var notifier = new OpsFeishuNotifier(sender, null, "oc_test123", outbox);

        var report = new com.clawkit.ops.loop.report.HumanIncidentReport(
            "1", "1", null, "SYNTHETIC_BUSINESS_DATA",
            "inc-test", "inc-test", "PROFILE", java.time.Instant.now(),
            java.time.Instant.now(),
            com.clawkit.ops.loop.report.HumanIncidentReport.IncidentStatus.ACTIVE,
            com.clawkit.ops.loop.report.HumanIncidentReport.DiagnosisConfidence.PROBABLE,
            "TEST", 0.7, "summary", "impact", "active",
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(),
            java.util.List.of("ESCALATE"), false, null, false);

        var entry = notifier.notify(report, NotificationOutbox.EventType.INITIAL_REPORT);
        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.SENT);
        assertThat(entry.feishuMessageId()).isEqualTo("msg-sent-1");
        assertThat(sentChatId.get()).isEqualTo("oc_test123");
        assertThat(sentContent.get()).contains("SYNTHETIC_BUSINESS_DATA");
    }

    @Test void notifierIdempotencyPreventsDuplicateSend() throws Exception {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        OpsFeishuNotifier.FeishuMessageSender sender = (chatId, content, key) -> {
            counter.incrementAndGet();
            return "msg-" + counter.get();
        };

        var notifier = new OpsFeishuNotifier(sender, null, "oc_test", outbox);
        var report = new com.clawkit.ops.loop.report.HumanIncidentReport(
            "1", "1", null, "SYNTHETIC_BUSINESS_DATA",
            "inc-dup", "inc-dup", "P", java.time.Instant.now(),
            java.time.Instant.now(),
            com.clawkit.ops.loop.report.HumanIncidentReport.IncidentStatus.ACTIVE,
            com.clawkit.ops.loop.report.HumanIncidentReport.DiagnosisConfidence.INCONCLUSIVE,
            "X", 0.0, "s", "i", "c",
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(),
            java.util.List.of(), false, null, false);

        notifier.notify(report, NotificationOutbox.EventType.INITIAL_REPORT);
        notifier.notify(report, NotificationOutbox.EventType.INITIAL_REPORT); // duplicate

        assertThat(counter.get()).isEqualTo(1); // Only sent once
    }

    @Test void differentVersionsTriggerSeparateNotifications() throws Exception {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        OpsFeishuNotifier.FeishuMessageSender sender = (chatId, content, key) -> {
            counter.incrementAndGet();
            return "msg-" + counter.get();
        };
        OpsFeishuNotifier.FeishuReplySender replier = (msgId, content, key) -> {
            counter.incrementAndGet();
            return "reply-" + counter.get();
        };

        var notifier = new OpsFeishuNotifier(sender, replier, "oc_test", outbox);
        var now = java.time.Instant.now();
        // V1
        var r1 = new com.clawkit.ops.loop.report.HumanIncidentReport(
            "1", "v1", null, "SYNTHETIC_BUSINESS_DATA",
            "inc-v", "inc-v", "P", now, now,
            com.clawkit.ops.loop.report.HumanIncidentReport.IncidentStatus.ACTIVE,
            com.clawkit.ops.loop.report.HumanIncidentReport.DiagnosisConfidence.PROBABLE,
            "X", 0.7, "s1", "i1", "c",
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(),
            java.util.List.of(), false, null, false);
        // V2
        var r2 = new com.clawkit.ops.loop.report.HumanIncidentReport(
            "1", "v2", null, "SYNTHETIC_BUSINESS_DATA",
            "inc-v", "inc-v", "P", now, now,
            com.clawkit.ops.loop.report.HumanIncidentReport.IncidentStatus.ACTIVE,
            com.clawkit.ops.loop.report.HumanIncidentReport.DiagnosisConfidence.PROBABLE,
            "X", 0.7, "s2", "i2", "c",
            java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(),
            java.util.List.of(), false, null, false);

        notifier.notify(r1, NotificationOutbox.EventType.INITIAL_REPORT);
        notifier.notify(r2, NotificationOutbox.EventType.STATUS_UPDATE);

        assertThat(counter.get()).isEqualTo(2); // Two distinct sends
    }
}
