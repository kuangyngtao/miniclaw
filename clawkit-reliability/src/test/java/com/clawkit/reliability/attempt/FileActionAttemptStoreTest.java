package com.clawkit.reliability.attempt;

import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileActionAttemptStoreTest {

    @TempDir
    Path dir;

    static ActionDescriptor descriptor(String target) {
        return new ActionDescriptor("file.write", target, "digest-1",
            ToolRiskLevel.HIGH, Reversibility.REVERSIBLE,
            ActionReliability.idempotentSetter(), VerificationMode.DETERMINISTIC,
            List.of(), List.of("file-sha256:" + target + ":abc"), "", "");
    }

    @Test
    void createTransitionPersistAndRecover() {
        String attemptId;
        try (var store = new FileActionAttemptStore(dir)) {
            var a = store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            attemptId = a.attemptId();
            var b = store.transition(attemptId, a.version(), AttemptState.PRECHECKING, "pre");
            var c = store.transition(attemptId, b.version(), AttemptState.READY, "ok");
            store.transition(attemptId, c.version(), AttemptState.DISPATCH_INTENT, "go");
        }
        // 重启进程（新实例回放 journal）
        try (var reopened = new FileActionAttemptStore(dir)) {
            var recovered = reopened.byId(attemptId).orElseThrow();
            assertEquals(AttemptState.DISPATCH_INTENT, recovered.state());
            assertEquals(3, recovered.version());
            // DISPATCH_INTENT 持有目标锁
            assertEquals(attemptId, reopened.activeOnTarget("file:/a").orElseThrow().attemptId());
        }
    }

    @Test
    void idempotencyKeyReplayReturnsExistingAttempt() {
        try (var store = new FileActionAttemptStore(dir)) {
            var first = store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            var replay = store.create(descriptor("file:/a"), "act-1", 1, "run-2", null, "ACTION");
            assertEquals(first.attemptId(), replay.attemptId());
            assertEquals(1, store.byLogicalAction("act-1").size());
        }
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentFingerprint() {
        try (var store = new FileActionAttemptStore(dir)) {
            store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            var changed = new ActionDescriptor("file.write", "file:/a", "digest-2",
                ToolRiskLevel.HIGH, Reversibility.REVERSIBLE,
                ActionReliability.idempotentSetter(), VerificationMode.DETERMINISTIC,
                List.of(), List.of("file-sha256:file:/a:def"), "", "");

            assertThrows(AttemptFailure.IdempotencyConflictException.class, () ->
                store.create(changed, "act-1", 1, "run-2", null, "ACTION"));
        }
    }

    @Test
    void targetMutexBlocksSecondAttemptUntilRelease() {
        try (var store = new FileActionAttemptStore(dir)) {
            var a = store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            assertThrows(AttemptFailure.TargetBusyException.class, () ->
                store.create(descriptor("file:/a"), "act-2", 1, "run-1", null, "ACTION"));
            // 终态释放后允许新 Attempt
            var b = store.transition(a.attemptId(), a.version(), AttemptState.FAILED_NO_EFFECT, "no effect");
            assertTrue(b.state().releasesTarget());
            assertDoesNotThrow(() ->
                store.create(descriptor("file:/a"), "act-2", 1, "run-1", null, "ACTION"));
        }
    }

    @Test
    void staleVersionCannotOverrideNewerState() {
        try (var store = new FileActionAttemptStore(dir)) {
            var a = store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            var b = store.transition(a.attemptId(), a.version(), AttemptState.PRECHECKING, "pre");
            store.transition(a.attemptId(), b.version(), AttemptState.READY, "ok");
            // 迟到事件持旧 version → 拒绝
            assertThrows(AttemptFailure.StaleVersionException.class, () ->
                store.transition(a.attemptId(), a.version(), AttemptState.CANCELLED_NO_EFFECT, "late"));
        }
    }

    @Test
    void illegalTransitionRejected() {
        try (var store = new FileActionAttemptStore(dir)) {
            var a = store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            assertThrows(AttemptFailure.IllegalTransitionException.class, () ->
                store.transition(a.attemptId(), a.version(), AttemptState.VERIFIED_SUCCESS, "skip"));
        }
    }

    @Test
    void tornTailIsRecoveredOnReopen() throws Exception {
        String attemptId;
        try (var store = new FileActionAttemptStore(dir)) {
            var a = store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            attemptId = a.attemptId();
        }
        // 模拟崩溃：journal 尾部追加半行垃圾
        Files.writeString(dir.resolve("journal.jsonl"), "deadbeef {\"seq\":99,\"garbage",
            StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        try (var reopened = new FileActionAttemptStore(dir)) {
            assertFalse(reopened.damaged());
            assertEquals(AttemptState.CREATED, reopened.byId(attemptId).orElseThrow().state());
            // 恢复后可以继续写入
            var a = reopened.byId(attemptId).orElseThrow();
            assertDoesNotThrow(() ->
                reopened.transition(attemptId, a.version(), AttemptState.PRECHECKING, "after recovery"));
        }
    }

    @Test
    void midFileCorruptionFailsClosed() throws Exception {
        try (var store = new FileActionAttemptStore(dir)) {
            store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
            store.create(descriptor("file:/b"), "act-2", 1, "run-1", null, "ACTION");
        }
        // 损坏第一行（中段），保留后续有效行
        Path journal = dir.resolve("journal.jsonl");
        var lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        lines.set(0, "badcrc {\"seq\":1}");
        Files.write(journal, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
        // 删掉 snapshot 强制走 journal 回放
        Files.deleteIfExists(dir.resolve("snapshot.json"));

        assertThrows(AttemptFailure.StoreUnavailableException.class, () -> {
            try (var reopened = new FileActionAttemptStore(dir)) {
                reopened.create(descriptor("file:/c"), "act-3", 1, "run-1", null, "ACTION");
            }
        });
    }

    @Test
    void duplicateSequenceLinesAreSkipped() throws Exception {
        try (var store = new FileActionAttemptStore(dir)) {
            store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
        }
        Path journal = dir.resolve("journal.jsonl");
        String firstLine = Files.readAllLines(journal, StandardCharsets.UTF_8).get(0);
        Files.writeString(journal, firstLine + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.APPEND); // 重复 seq=1
        Files.deleteIfExists(dir.resolve("snapshot.json"));

        try (var reopened = new FileActionAttemptStore(dir)) {
            assertFalse(reopened.damaged());
            assertEquals(1, reopened.byLogicalAction("act-1").size());
        }
    }

    @Test
    void twoStoreInstancesShareTargetMutexAcrossChannels() throws Exception {
        // 两个独立 store 实例（等价两个进程：独立 FileChannel/FileLock/内存态）
        try (var storeA = new FileActionAttemptStore(dir);
             var storeB = new FileActionAttemptStore(dir)) {

            var a = storeA.create(descriptor("file:/shared"), "act-A", 1, "run-A", null, "ACTION");
            // B 实例在自己的事务中回放 journal 后必须看到 A 的目标占用
            assertThrows(AttemptFailure.TargetBusyException.class, () ->
                storeB.create(descriptor("file:/shared"), "act-B", 1, "run-B", null, "ACTION"));

            // A 释放后 B 才能获取
            storeA.transition(a.attemptId(), a.version(), AttemptState.FAILED_NO_EFFECT, "done");
            assertDoesNotThrow(() ->
                storeB.create(descriptor("file:/shared"), "act-B", 1, "run-B", null, "ACTION"));
        }
    }

    @Test
    void concurrentCreatesOnSameTargetAdmitExactlyOne() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger busies = new AtomicInteger();
        try (var store = new FileActionAttemptStore(dir)) {
            var workers = new java.util.ArrayList<Thread>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                workers.add(Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        store.create(descriptor("file:/hot"), "act-" + idx, 1, "run", null, "ACTION");
                        wins.incrementAndGet();
                    } catch (AttemptFailure.TargetBusyException e) {
                        busies.incrementAndGet();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Thread t : workers) t.join(15_000);
        }
        assertEquals(1, wins.get(), "同一目标并发副作用必须恰好一个获准");
        assertEquals(threads - 1, busies.get());
    }
}
