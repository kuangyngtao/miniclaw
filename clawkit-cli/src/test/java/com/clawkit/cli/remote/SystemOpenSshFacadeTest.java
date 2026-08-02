package com.clawkit.cli.remote;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * P0-3: Prove process lifecycle correctness without real ssh in PATH.
 *
 * <p>Every test uses a fake {@link SshProcessFactory} so assertions
 * on destroy count, thread cleanup, and timeout behavior are mechanical.
 */
class SystemOpenSshFacadeTest {

    private SystemOpenSshFacade facade;
    private final AtomicInteger destroyCount = new AtomicInteger(0);
    private final AtomicInteger startCount = new AtomicInteger(0);

    // ── checkVersion timeout must kill process ────────────────────────

    @Test
    void sshVersionTimeoutMustKillProcess() throws Exception {
        var blocked = new BlockingProcess(0);
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return blocked;
        });

        // Should complete (not hang) — timeout fires, process destroyed
        var result = facade.checkVersion();
        assertThat(result.available()).isFalse();
        assertThat(blocked.destroyed.get()).isTrue();
        assertThat(blocked.destroyForciblyCalled.get()).isTrue();
    }

    // ── runSshG timeout must kill process ─────────────────────────────

    @Test
    void sshGTimeoutMustKillProcess() throws Exception {
        var blocked = new BlockingProcess(0);
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return blocked;
        });

        assertThatThrownBy(() -> facade.runSshG("test-server", "opsro"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("timed out");
        assertThat(blocked.destroyed.get()).isTrue();
    }

    // ── Blocked stdout must respect deadline ──────────────────────────

    @Test
    void blockedStdoutMustRespectDeadline() throws Exception {
        var blocked = new BlockingProcess(Integer.MAX_VALUE); // never produces output
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return blocked;
        });

        assertThatThrownBy(() -> facade.runSshG("test-server", "opsro"))
            .isInstanceOf(IOException.class);
        assertThat(blocked.destroyed.get()).isTrue();
    }

    // ── Non-zero exit must fail ───────────────────────────────────────

    @Test
    void nonZeroExitMustFail() throws Exception {
        var failing = new FakeProcess("", 1, true, "");
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return failing;
        });

        assertThatThrownBy(() -> facade.runSshG("test-server", "opsro"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("exited with code 1");
    }

    // ── Oversized stdout must fail closed ─────────────────────────────

    @Test
    void oversizedStdoutMustFailClosed() throws Exception {
        String big = "x".repeat(70000); // exceeds 65536 limit
        var oversized = new FakeProcess(big, 0, true, "");
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return oversized;
        });

        assertThatThrownBy(() -> facade.runSshG("test-server", "opsro"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("exceeded limit");
    }

    // ── Normal exit with valid output must succeed ────────────────────

    @Test
    void validOutputMustParseCorrectly() throws Exception {
        String raw = """
            hostname 203.0.113.10
            port 22
            user opsro
            identityfile /home/user/.ssh/id_ed25519
            identityfile /home/user/.ssh/id_rsa
            identityagent SSH_AUTH_SOCK
            proxycommand none
            """;
        var normal = new FakeProcess(raw, 0, true, "");
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return normal;
        });

        var result = facade.runSshG("test-server", "opsro");
        assertThat(result.hostname()).isEqualTo("203.0.113.10");
        assertThat(result.port()).isEqualTo(22);
        assertThat(result.user()).isEqualTo("opsro");
        assertThat(result.identityFileCount()).isEqualTo(2);
        assertThat(result.agentEnabled()).isTrue();
        assertThat(result.hasProxyCommand()).isFalse();
    }

    // ── Process destroyed even on success ─────────────────────────────

    @Test
    void processDestroyedAfterSuccess() throws Exception {
        var normal = new FakeProcess("hostname example.com\nport 22\nuser opsro\n", 0, true, "");
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return normal;
        });

        facade.runSshG("test-server", "opsro");
        // Process should be destroyed even after normal exit
        assertThat(normal.destroyed.get()).isTrue();
    }

    // ── No orphan threads or processes after return ───────────────────

    @Test
    void noReaderThreadOrProcessAfterReturn() throws Exception {
        var normal = new FakeProcess("hostname example.com\nport 22\nuser opsro\n", 0, true, "");
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return normal;
        });

        long readersBefore = sshReaderThreadCount();
        facade.runSshG("test-server", "opsro");
        awaitReaderThreadCount(readersBefore);
        assertThat(normal.destroyed.get()).isTrue();
        assertThat(sshReaderThreadCount()).isLessThanOrEqualTo(readersBefore);
    }

    @Test
    void delayedOutputMustNotBeDiscardedBecauseAvailableIsZero() throws Exception {
        var delayed = new FakeProcess("", 0, true, "") {
            @Override public InputStream getErrorStream() {
                return new DelayedInputStream("OpenSSH_9.7p1".getBytes(StandardCharsets.UTF_8), 300);
            }
        };
        facade = facadeWithFactory(cmd -> delayed);

        var result = facade.checkVersion();

        assertThat(result.available()).isTrue();
        assertThat(result.version()).contains("OpenSSH_9.7p1");
    }

    // ── checkVersion with non-zero exit should still report available ──

    @Test
    void sshVersionExit255IsNormal() throws Exception {
        // ssh -V writes to stderr and exits 255 on some platforms
        var vProcess = new FakeProcess("", 255, true,
            "OpenSSH_9.6p1, LibreSSL 3.3.6");
        facade = facadeWithFactory(cmd -> {
            startCount.incrementAndGet();
            return vProcess;
        });

        var result = facade.checkVersion();
        assertThat(result.available()).isTrue();
        assertThat(result.version()).contains("OpenSSH");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private SystemOpenSshFacade facadeWithFactory(SshProcessFactory factory) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        return new SystemOpenSshFacade(tmp, null, factory);
    }

    private static long sshReaderThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && t.getName().startsWith("ssh-facade-io"))
            .count();
    }

    private static void awaitReaderThreadCount(long expectedMaximum) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && sshReaderThreadCount() > expectedMaximum) {
            Thread.sleep(10);
        }
    }

    // ── Fake process that blocks forever ─────────────────────────────

    static class BlockingProcess extends Process {
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final AtomicBoolean destroyForciblyCalled = new AtomicBoolean(false);
        private final int exitAfterSeconds;

        BlockingProcess(int exitAfterSeconds) {
            this.exitAfterSeconds = exitAfterSeconds;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }

        @Override
        public InputStream getInputStream() {
            // Return empty stream that never produces data
            return new InputStream() {
                @Override public int read() throws IOException {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return -1; }
                    return 0; // never EOF
                }
            };
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override public int waitFor() throws InterruptedException {
            Thread.sleep(TimeUnit.SECONDS.toMillis(exitAfterSeconds));
            return 0;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            return false; // never exits normally
        }

        @Override public int exitValue() {
            throw new IllegalThreadStateException("not exited");
        }

        @Override public void destroy() {
            destroyed.set(true);
        }

        @Override public Process destroyForcibly() {
            destroyForciblyCalled.set(true);
            destroyed.set(true);
            return this;
        }

        @Override public boolean isAlive() { return !destroyed.get(); }
    }

    // ── Fake process with canned output ────────────────────────────────

    static class FakeProcess extends Process {
        private final String stdout;
        private final int exitCode;
        private final boolean exitNormally;
        private final String stderr;
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        private boolean stdinClosed;

        FakeProcess(String stdout, int exitCode, boolean exitNormally, String stderr) {
            this.stdout = stdout;
            this.exitCode = exitCode;
            this.exitNormally = exitNormally;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) {}
                @Override public void close() { stdinClosed = true; }
            };
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
        }

        @Override public int waitFor() { return exitCode; }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return exitNormally;
        }

        @Override public int exitValue() { return exitCode; }

        @Override public void destroy() { destroyed.set(true); }

        @Override public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override public boolean isAlive() { return !destroyed.get() && !exitNormally; }
    }

    static class DelayedInputStream extends InputStream {
        private final byte[] bytes;
        private final long delayMs;
        private boolean delayed;
        private int index;

        DelayedInputStream(byte[] bytes, long delayMs) {
            this.bytes = bytes;
            this.delayMs = delayMs;
        }

        @Override public int read() throws IOException {
            if (!delayed) {
                delayed = true;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            return index < bytes.length ? bytes[index++] & 0xff : -1;
        }
    }
}
