package com.clawkit.tools.impl;

import com.clawkit.tools.ProcessRunner;
import com.clawkit.tools.control.CancelRegistration;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** P1-G1：DefaultProcessRunner 的取消与 deadline 行为。 */
class DefaultProcessRunnerCancelTest {

    /** 最小可取消控制实现（tools 测试不依赖 reliability 模块）。 */
    static class TestControl implements ExecutionControl {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        Instant deadline;

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                listeners.forEach(Runnable::run);
            }
        }

        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public Optional<Instant> deadline() { return Optional.ofNullable(deadline); }
        @Override public TokenBudget tokenBudget() { return TokenBudget.unlimited(); }
        @Override public CancelRegistration onCancel(Runnable action) {
            if (cancelled.get()) { action.run(); return CancelRegistration.noop(); }
            listeners.add(action);
            return () -> listeners.remove(action);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static ProcessRunner.ProcessExecutionRequest longRunning(
            Path workDir, ExecutionControl control) {
        String shell = isWindows() ? "cmd" : "bash";
        String flag = isWindows() ? "/c" : "-c";
        String command = isWindows() ? "ping -n 30 127.0.0.1" : "sleep 30";
        return new ProcessRunner.ProcessExecutionRequest(
            workDir, command, shell, flag, Duration.ofSeconds(60), 8000, null, control);
    }

    @Test
    void cancelKillsRunningProcessTree() throws Exception {
        Path dir = Files.createTempDirectory("prc");
        TestControl control = new TestControl();
        DefaultProcessRunner runner = new DefaultProcessRunner();
        AtomicReference<ProcessRunner.ProcessExecutionResult> result = new AtomicReference<>();

        long start = System.currentTimeMillis();
        Thread worker = Thread.ofVirtual().start(() ->
            result.set(runner.execute(longRunning(dir, control))));

        Thread.sleep(500); // 让进程启动
        control.cancel();
        worker.join(15_000);

        long elapsed = System.currentTimeMillis() - start;
        assertNotNull(result.get(), "runner should return after cancel");
        assertTrue(result.get().cancelled(), "result must be marked cancelled");
        assertFalse(result.get().success());
        assertTrue(elapsed < 20_000, "cancel must terminate long process early, took " + elapsed + "ms");
    }

    @Test
    void cancelBeforeStartDoesNotLaunchProcess() throws Exception {
        Path dir = Files.createTempDirectory("prc");
        TestControl control = new TestControl();
        control.cancel();

        var result = new DefaultProcessRunner().execute(longRunning(dir, control));

        assertTrue(result.cancelled());
        assertEquals("[CANCELLED_BEFORE_START]", result.stderr());
        assertEquals(0, result.totalOutputBytes());
    }

    @Test
    void interruptingRunnerThreadTerminatesStartedProcess() throws Exception {
        Path dir = Files.createTempDirectory("prc");
        TestControl control = new TestControl();
        AtomicReference<ProcessRunner.ProcessExecutionResult> result = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() ->
            result.set(new DefaultProcessRunner().execute(longRunning(dir, control))));

        Thread.sleep(500);
        long start = System.currentTimeMillis();
        worker.interrupt();
        worker.join(10_000);

        assertNotNull(result.get(), "runner must return after interrupt");
        assertTrue(result.get().cancelled());
        assertTrue(System.currentTimeMillis() - start < 8_000,
            "interrupt must not leave the process running");
    }

    @Test
    void deadlineTightensEffectiveTimeout() throws Exception {
        Path dir = Files.createTempDirectory("prc");
        TestControl control = new TestControl();
        control.deadline = Instant.now().plusMillis(800); // 剩余 0.8s，配置 timeout 60s

        long start = System.currentTimeMillis();
        var result = new DefaultProcessRunner().execute(longRunning(dir, control));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.timedOut(), "deadline-bounded run must time out");
        assertFalse(result.cancelled());
        assertTrue(elapsed < 20_000, "effective timeout must be min(config, deadline), took " + elapsed + "ms");
    }
}
