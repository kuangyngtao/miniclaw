package com.clawkit.reliability.attempt;

import com.clawkit.reliability.attempt.AttemptFailure.IllegalTransitionException;
import com.clawkit.reliability.attempt.AttemptFailure.IdempotencyConflictException;
import com.clawkit.reliability.attempt.AttemptFailure.StaleVersionException;
import com.clawkit.reliability.attempt.AttemptFailure.StoreUnavailableException;
import com.clawkit.reliability.attempt.AttemptFailure.TargetBusyException;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionIdentity;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * 文件版 Attempt 存储（P1-G3 控制面事实来源）。
 *
 * <p>append-only journal + CRC + {@code FileChannel.force(true)}；
 * snapshot 仅用于加速读取，journal 才是事实来源。
 * 每次事务持有跨进程文件锁并先回放其他进程新追加的记录，
 * 目标互斥与幂等索引因此对并发 JVM 同样成立。
 *
 * <p>journal 写入失败必须阻断写动作（fail closed）；
 * 尾部损坏（崩溃窗口）在持锁恢复时截断，中段损坏进入 degraded 模式拒绝所有变更。
 */
public final class FileActionAttemptStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileActionAttemptStore.class);
    private static final int SNAPSHOT_EVERY = 64;
    private static final long LOCK_WAIT_MS = 10_000;

    private final Path dir;
    private final Path journalFile;
    private final Path snapshotFile;
    private final Path lockFile;
    private final ObjectMapper mapper;
    private final ReentrantLock processLock = new ReentrantLock();

    private final Map<String, ActionAttempt> attempts = new HashMap<>();
    private final Map<String, String> idempotencyIndex = new HashMap<>();
    private final Map<String, String> targetHolders = new HashMap<>();
    private long lastSeq;
    private long journalOffset;
    private boolean damaged;
    private int appendsSinceSnapshot;

    public FileActionAttemptStore(Path dir) {
        this.dir = dir;
        this.journalFile = dir.resolve("journal.jsonl");
        this.snapshotFile = dir.resolve("snapshot.json");
        this.lockFile = dir.resolve("store.lock");
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new StoreUnavailableException("cannot create store dir " + dir, e);
        }
        inTransaction(() -> null); // 打开即恢复：回放 journal（含尾部损坏截断）
    }

    // ── 变更 API ─────────────────────────────────────────────────

    /**
     * 创建 Attempt。同一 idempotencyKey 幂等：已存在则返回既有 Attempt（重启重放安全）。
     * 目标被活跃 Attempt 持有时抛 {@link TargetBusyException}。
     */
    public ActionAttempt create(ActionDescriptor descriptor, String logicalActionId,
                                int attemptSeq, String runId, String relatedAttemptId,
                                String purpose) {
        return inTransaction(() -> {
            ActionIdentity identity = ActionIdentity.of(
                logicalActionId, attemptSeq, descriptor.targetKey(), descriptor.fingerprint());
            String existing = idempotencyIndex.get(identity.idempotencyKey());
            if (existing != null) {
                ActionAttempt prior = attempts.get(existing);
                if (prior == null || !prior.identity().equals(identity)) {
                    throw new IdempotencyConflictException(identity.idempotencyKey());
                }
                return prior;
            }
            String holder = targetHolders.get(identity.targetKey());
            boolean compensationTransfer = holder != null
                && "COMPENSATION".equals(purpose)
                && holder.equals(relatedAttemptId);
            if (holder != null && !compensationTransfer) {
                throw new TargetBusyException(identity.targetKey(), holder);
            }
            Instant now = Instant.now();
            ActionAttempt attempt = new ActionAttempt(
                "att-" + UUID.randomUUID(), identity, descriptor,
                AttemptState.CREATED, 0, attemptSeq, runId, relatedAttemptId,
                purpose, null, now, now);
            append(attempt);
            return attempt;
        });
    }

    /**
     * 状态迁移（CAS）：expectedVersion 不匹配抛 {@link StaleVersionException}，
     * 迟到响应不能覆盖新状态或人工结论；非法迁移抛 {@link IllegalTransitionException}。
     */
    public ActionAttempt transition(String attemptId, long expectedVersion,
                                    AttemptState to, String reason) {
        return inTransaction(() -> {
            ActionAttempt current = attempts.get(attemptId);
            if (current == null) {
                throw new StoreUnavailableException("unknown attempt " + attemptId, null);
            }
            if (current.version() != expectedVersion) {
                throw new StaleVersionException(attemptId, expectedVersion, current.version());
            }
            if (!current.state().canTransitionTo(to)) {
                throw new IllegalTransitionException(attemptId, current.state(), to);
            }
            ActionAttempt next = current.with(to, reason, Instant.now());
            append(next);
            return next;
        });
    }

    // ── 查询 API ─────────────────────────────────────────────────

    public Optional<ActionAttempt> byId(String attemptId) {
        return read(() -> Optional.ofNullable(attempts.get(attemptId)));
    }

    public Optional<ActionAttempt> byIdempotencyKey(String key) {
        return read(() -> Optional.ofNullable(idempotencyIndex.get(key)).map(attempts::get));
    }

    /** 当前持有目标锁的活跃 Attempt。 */
    public Optional<ActionAttempt> activeOnTarget(String targetKey) {
        return read(() -> Optional.ofNullable(targetHolders.get(targetKey)).map(attempts::get));
    }

    public List<ActionAttempt> nonTerminal() {
        return read(() -> attempts.values().stream()
            .filter(a -> !a.state().isTerminal())
            .sorted(java.util.Comparator.comparing(ActionAttempt::createdAt))
            .toList());
    }

    public List<ActionAttempt> byLogicalAction(String logicalActionId) {
        return read(() -> attempts.values().stream()
            .filter(a -> a.identity().logicalActionId().equals(logicalActionId))
            .sorted(java.util.Comparator.comparingInt(ActionAttempt::attemptSeq))
            .toList());
    }

    public List<ActionAttempt> byTarget(String targetKey) {
        return read(() -> attempts.values().stream()
            .filter(a -> a.targetKey().equals(targetKey))
            .sorted(java.util.Comparator.comparing(ActionAttempt::createdAt))
            .toList());
    }

    public boolean damaged() {
        return read(() -> damaged);
    }

    @Override
    public void close() {
        processLock.lock();
        try {
            writeSnapshot();
        } catch (Exception e) {
            log.warn("snapshot on close failed: {}", e.getMessage());
        } finally {
            processLock.unlock();
        }
    }

    // ── 事务与恢复 ────────────────────────────────────────────────

    private <T> T inTransaction(Supplier<T> work) {
        processLock.lock();
        try (FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock fileLock = acquireWithRetry(lockChannel);
            try {
                refresh(true);
                return work.get();
            } finally {
                try { fileLock.release(); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            throw new StoreUnavailableException("store transaction failed", e);
        } finally {
            processLock.unlock();
        }
    }

    private <T> T read(Supplier<T> work) {
        processLock.lock();
        try {
            refresh(false); // 无锁读取新追加的完整记录；尾部残缺不消费
            return work.get();
        } finally {
            processLock.unlock();
        }
    }

    private FileLock acquireWithRetry(FileChannel channel) throws IOException {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException e) {
                // 同 JVM 的另一 store 实例持有：等待重试（等价跨进程竞争）
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreUnavailableException("interrupted waiting for store lock", e);
            }
        }
        throw new StoreUnavailableException("timed out acquiring store lock " + lockFile, null);
    }

    /**
     * 回放 journal 中未消费的记录。
     * 在首个无效行处停止：其后仍有有效行 → 中段损坏（damaged，fail closed）；
     * 其后全为垃圾/无换行 → 崩溃尾部，持锁（exclusive）时截断恢复。
     */
    private void refresh(boolean exclusive) {
        maybeLoadSnapshot();
        if (!Files.exists(journalFile)) return;
        try {
            long size = Files.size(journalFile);
            if (size <= journalOffset) return;
            byte[] tailBytes;
            try (FileChannel ch = FileChannel.open(journalFile, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate((int) (size - journalOffset));
                ch.position(journalOffset);
                while (buf.hasRemaining() && ch.read(buf) != -1) { /* drain */ }
                tailBytes = buf.array();
            }
            String text = new String(tailBytes, StandardCharsets.UTF_8);
            long consumed = 0;
            int lineStart = 0;
            while (lineStart < text.length()) {
                int nl = text.indexOf('\n', lineStart);
                if (nl < 0) {
                    // 无换行的尾部残片：崩溃遗留或写入进行中；持锁时截断
                    if (exclusive) {
                        truncateTo(journalOffset + consumed);
                    }
                    break;
                }
                String line = text.substring(lineStart, nl);
                if (applyLine(line)) {
                    consumed += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                } else {
                    if (hasValidLineAfter(text, nl + 1)) {
                        damaged = true;
                        log.error("journal mid-file corruption at offset {}; mutations blocked",
                            journalOffset + consumed);
                    } else if (exclusive) {
                        truncateTo(journalOffset + consumed);
                    }
                    break; // 无效行永不消费
                }
                lineStart = nl + 1;
            }
            journalOffset += consumed;
        } catch (IOException e) {
            throw new StoreUnavailableException("journal replay failed", e);
        }
    }

    private boolean hasValidLineAfter(String text, int from) {
        int lineStart = from;
        while (lineStart < text.length()) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) return false;
            if (crcValid(text.substring(lineStart, nl))) return true;
            lineStart = nl + 1;
        }
        return false;
    }

    private static boolean crcValid(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) return false;
        CRC32 crc = new CRC32();
        crc.update(line.substring(space + 1).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue()).equals(line.substring(0, space));
    }

    /** 解析并应用一行；返回是否有效。 */
    private boolean applyLine(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) return false;
        String crcHex = line.substring(0, space);
        String json = line.substring(space + 1);
        CRC32 crc = new CRC32();
        crc.update(json.getBytes(StandardCharsets.UTF_8));
        if (!Long.toHexString(crc.getValue()).equals(crcHex)) {
            return false;
        }
        try {
            JournalEntry entry = mapper.readValue(json, JournalEntry.class);
            if (entry.seq() != lastSeq + 1) {
                log.error("journal seq discontinuity: got {}, expected {}",
                    entry.seq(), lastSeq + 1);
                return false;
            }
            apply(entry);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void apply(JournalEntry entry) {
        lastSeq = entry.seq();
        ActionAttempt attempt = entry.attempt();
        String indexed = idempotencyIndex.get(attempt.identity().idempotencyKey());
        if (indexed != null && !indexed.equals(attempt.attemptId())) {
            throw new IdempotencyConflictException(attempt.identity().idempotencyKey());
        }
        attempts.put(attempt.attemptId(), attempt);
        idempotencyIndex.putIfAbsent(attempt.identity().idempotencyKey(), attempt.attemptId());
        String holder = targetHolders.get(attempt.targetKey());
        if (attempt.state().releasesTarget()) {
            if (attempt.attemptId().equals(holder)) {
                targetHolders.remove(attempt.targetKey());
            }
        } else if (holder == null || holder.equals(attempt.attemptId())
                || ("COMPENSATION".equals(attempt.purpose())
                    && holder.equals(attempt.relatedAttemptId()))) {
            // 只在空闲或本就持有时占锁；已释放的 attempt（如 VERIFYING→COMPENSATION_PENDING）
            // 不得从其他 attempt 手中偷锁
            targetHolders.put(attempt.targetKey(), attempt.attemptId());
        } else {
            throw new IllegalStateException("journal target mutex violation on "
                + attempt.targetKey() + ": holder=" + holder
                + ", incoming=" + attempt.attemptId());
        }
    }

    /** durable append：CRC + force(true)。失败抛 StoreUnavailable（fail closed）。 */
    private void append(ActionAttempt attempt) {
        if (damaged) {
            throw new StoreUnavailableException("journal damaged; mutations are blocked", null);
        }
        JournalEntry entry = new JournalEntry(lastSeq + 1, Instant.now(), attempt);
        try {
            String json = mapper.writeValueAsString(entry);
            CRC32 crc = new CRC32();
            crc.update(json.getBytes(StandardCharsets.UTF_8));
            String line = Long.toHexString(crc.getValue()) + " " + json + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(journalFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    ch.write(buffer);
                }
                ch.force(true);
            }
            journalOffset += bytes.length;
            apply(entry);
            if (++appendsSinceSnapshot >= SNAPSHOT_EVERY) {
                writeSnapshot();
            }
        } catch (IOException e) {
            throw new StoreUnavailableException("journal append failed", e);
        }
    }

    private void truncateTo(long size) {
        try (FileChannel ch = FileChannel.open(journalFile, StandardOpenOption.WRITE)) {
            if (ch.size() > size) {
                log.warn("truncating torn journal tail from {} to {} bytes", ch.size(), size);
                ch.truncate(size);
                ch.force(true);
            }
        } catch (IOException e) {
            throw new StoreUnavailableException("journal tail recovery failed", e);
        }
    }

    // ── snapshot（加速读取；journal 才是事实来源） ─────────────────

    private boolean snapshotLoaded;

    private void maybeLoadSnapshot() {
        if (snapshotLoaded) return;
        snapshotLoaded = true;
        if (!Files.exists(snapshotFile)) return;
        try {
            Snapshot snap = mapper.readValue(Files.readAllBytes(snapshotFile), Snapshot.class);
            if (snap.attempts() != null && lastSeq == 0) {
                for (ActionAttempt a : snap.attempts()) {
                    attempts.put(a.attemptId(), a);
                    idempotencyIndex.putIfAbsent(a.identity().idempotencyKey(), a.attemptId());
                    if (!a.state().releasesTarget()) {
                        targetHolders.put(a.targetKey(), a.attemptId());
                    }
                }
                lastSeq = snap.lastSeq();
                journalOffset = snap.journalOffset();
            }
        } catch (IOException e) {
            log.warn("snapshot unreadable, falling back to full journal replay: {}", e.getMessage());
            attempts.clear();
            idempotencyIndex.clear();
            targetHolders.clear();
            lastSeq = 0;
            journalOffset = 0;
        }
    }

    private void writeSnapshot() {
        appendsSinceSnapshot = 0;
        try {
            Snapshot snap = new Snapshot(lastSeq, journalOffset, List.copyOf(attempts.values()));
            Path tmp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(snap));
            Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("snapshot write failed (journal remains source of truth): {}", e.getMessage());
        }
    }

    record JournalEntry(long seq, Instant at, ActionAttempt attempt) {}

    record Snapshot(long lastSeq, long journalOffset, List<ActionAttempt> attempts) {}
}
