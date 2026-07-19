package com.clawkit.tools.impl;

import com.clawkit.tools.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ProcessRunner 默认实现。
 *
 * <p>关键安全特性：
 * <ul>
 *   <li>stdout/stderr 并发 pump（先启动再 waitFor），防止管道阻塞</li>
 *   <li>有界 head collector，不将完整长输出读入内存</li>
 *   <li>timeout 后先 SIGTERM → 2s 宽限期 → SIGKILL</li>
 *   <li>进程树终止（ProcessHandle.descendants）</li>
 *   <li>环境变量白名单</li>
 *   <li>不使用 redirectErrorStream(true)，stdout/stderr 独立分区</li>
 * </ul>
 */
public class DefaultProcessRunner implements ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultProcessRunner.class);

    private static final Set<String> ENV_ALLOWLIST = Set.of(
        "PATH", "HOME", "USER", "USERNAME", "SHELL", "LANG", "LC_ALL",
        "JAVA_HOME", "GIT_BASH", "SYSTEMROOT", "PROGRAMFILES", "PROGRAMFILES(X86)",
        "TEMP", "TMP", "TMPDIR", "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH",
        "COMSPEC", "PATHEXT", "TERM", "COLORTERM", "PWD", "OLDPWD"
    );

    @Override
    public ProcessExecutionResult runDirect(
            List<String> command, Path workDir,
            Duration timeout, long maxOutputBytes) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);

            // 环境变量白名单
            Map<String, String> env = pb.environment();
            Map<String, String> allowed = buildAllowedEnv(env, null);
            env.clear();
            env.putAll(allowed);

            process = pb.start();

            StreamCollector stdoutCollector = new StreamCollector(
                process.getInputStream(), maxOutputBytes);
            StreamCollector stderrCollector = new StreamCollector(
                process.getErrorStream(), maxOutputBytes);
            Thread stdoutThread = Thread.ofVirtual().start(stdoutCollector);
            Thread stderrThread = Thread.ofVirtual().start(stderrCollector);

            long timeoutMs = timeout != null ? timeout.toMillis() : 30_000;
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            int exitCode;

            if (!finished) {
                var descendants = process.toHandle().descendants().toList();
                process.destroy();
                descendants.forEach(h -> { try { h.destroy(); } catch (Exception ignored) {} });
                boolean terminated = process.waitFor(2, TimeUnit.SECONDS);
                if (!terminated) {
                    process.destroyForcibly();
                    descendants.forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) {} });
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                try { exitCode = process.exitValue(); }
                catch (IllegalThreadStateException e) { exitCode = -1; }
            } else {
                exitCode = process.exitValue();
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            return new ProcessExecutionResult(
                stdoutCollector.getOutput(), stderrCollector.getOutput(),
                exitCode, !finished,
                stdoutCollector.totalBytes() + stderrCollector.totalBytes(),
                stdoutCollector.truncated() || stderrCollector.truncated(),
                false, stdoutCollector.envelope(), stderrCollector.envelope());

        } catch (InterruptedException e) {
            if (process != null) {
                terminateTree(process);
            }
            Thread.currentThread().interrupt();
            return new ProcessExecutionResult("", "[CANCELLED]", -1,
                false, 0, false, true);
        } catch (Exception e) {
            log.warn("ProcessRunner.runDirect failed: {}", e.getMessage());
            return new ProcessExecutionResult("", e.getMessage(), -1, false, 0, false);
        }
    }

    @Override
    public ProcessExecutionResult execute(ProcessExecutionRequest req) {
        var control = req.control();
        // 派发前取消：进程未启动，确认无副作用
        if (control.isCancelled()) {
            return new ProcessExecutionResult("", "[CANCELLED_BEFORE_START]", -1,
                false, 0, false, true);
        }

        Process process = null;
        try {
            // 构建命令
            List<String> cmd = new ArrayList<>();
            cmd.add(req.shell());
            if (req.shellFlag() != null && !req.shellFlag().isEmpty()) {
                cmd.add(req.shellFlag());
            }
            cmd.add(req.command());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(req.workDir().toFile());
            pb.redirectErrorStream(false); // stdout/stderr 独立

            // 环境变量白名单
            Map<String, String> env = pb.environment();
            Map<String, String> allowed = buildAllowedEnv(env, req.envAllowlist());
            env.clear();
            env.putAll(allowed);

            process = pb.start();
            Process runningProcess = process;

            // ── 启动并发 pump（在 waitFor 之前） ───────────────
            StreamCollector stdoutCollector = new StreamCollector(
                process.getInputStream(), req.maxOutputBytes());
            StreamCollector stderrCollector = new StreamCollector(
                process.getErrorStream(), req.maxOutputBytes());
            Thread stdoutThread = Thread.ofVirtual().start(stdoutCollector);
            Thread stderrThread = Thread.ofVirtual().start(stderrCollector);

            // ── 取消 → 立即终止进程树；deadline → 收紧有效 timeout ──
            long configuredMs = req.timeout() != null ? req.timeout().toMillis() : 30_000;
            long timeoutMs = configuredMs;
            var remaining = control.remainingTime();
            if (remaining.isPresent()) {
                timeoutMs = Math.max(1, Math.min(configuredMs, remaining.get().toMillis()));
            }

            boolean finished;
            try (var cancelReg = control.onCancel(() -> terminateTree(runningProcess))) {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    // 超时 → 先 SIGTERM 父进程 + 子树，再 SIGKILL
                    terminateTree(process);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }

            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException e) {
                exitCode = -1;
            }

            stdoutThread.join(1000);
            stderrThread.join(1000);

            boolean cancelled = control.isCancelled();
            return new ProcessExecutionResult(
                stdoutCollector.getOutput(), stderrCollector.getOutput(),
                exitCode, !finished && !cancelled,
                stdoutCollector.totalBytes() + stderrCollector.totalBytes(),
                stdoutCollector.truncated() || stderrCollector.truncated(),
                cancelled, stdoutCollector.envelope(), stderrCollector.envelope());

        } catch (InterruptedException e) {
            if (process != null) {
                terminateTree(process);
            }
            Thread.currentThread().interrupt();
            return new ProcessExecutionResult(
                "", "[CANCELLED]", -1, false, 0, false, true);
        } catch (Exception e) {
            log.warn("ProcessRunner failed: {}", e.getMessage());
            return new ProcessExecutionResult(
                "", e.getMessage(), -1, false, 0, false);
        }
    }

    /** 终止进程树：先快照 descendants → SIGTERM 父子 → 宽限期 → SIGKILL。 */
    private static void terminateTree(Process process) {
        try {
            var descendants = process.toHandle().descendants().toList();
            process.destroy();
            descendants.forEach(h -> { try { h.destroy(); } catch (Exception ignored) {} });
            boolean terminated = process.waitFor(2, TimeUnit.SECONDS);
            if (!terminated) {
                process.destroyForcibly();
                descendants.forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) {} });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> buildAllowedEnv(Map<String, String> currentEnv,
                                                  Map<String, String> reqAllowlist) {
        // 优先使用传入的白名单，没有则用内置白名单
        Set<String> allowlist = (reqAllowlist != null && !reqAllowlist.isEmpty())
            ? reqAllowlist.keySet() : ENV_ALLOWLIST;
        Map<String, String> allowed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : currentEnv.entrySet()) {
            String key = e.getKey().toUpperCase();
            if (allowlist.contains(key) || allowlist.contains(e.getKey())) {
                allowed.put(e.getKey(), e.getValue());
            }
        }
        // 确保基础变量存在
        allowed.putIfAbsent("PATH", System.getenv("PATH"));
        if (System.getenv("SYSTEMROOT") != null) {
            allowed.putIfAbsent("SYSTEMROOT", System.getenv("SYSTEMROOT"));
        }
        return allowed;
    }

    // ── 有界流收集器 ─────────────────────────────────────────────

    /**
     * P1-G2：基于 BoundedOutputCollector 的截断保真收集。
     * head 2/3 + tail 1/3；总字节数与 SHA-256 始终完整统计。
     */
    private static class StreamCollector implements Runnable {
        private final InputStream stream;
        private final BoundedOutputCollector collector;
        private volatile com.clawkit.tools.OutputEnvelope envelope;

        StreamCollector(InputStream stream, long maxBytes) {
            this.stream = stream;
            long headCap = Math.max(1, maxBytes * 2 / 3);
            long tailCap = Math.max(256, maxBytes - headCap);
            this.collector = new BoundedOutputCollector(headCap, tailCap);
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = stream.read(buf)) != -1) {
                    collector.accept(buf, 0, n);
                }
            } catch (Exception ignored) {
                // stream closed
            }
        }

        long totalBytes() {
            return collector.totalBytes();
        }

        boolean truncated() {
            return collector.truncated();
        }

        /** 输出信封（幂等构建）。 */
        com.clawkit.tools.OutputEnvelope envelope() {
            var env = envelope;
            if (env == null) {
                env = collector.toEnvelope("max-output-bytes");
                envelope = env;
            }
            return env;
        }

        /** 兼容文本视图：截断时保留 head + 省略标记 + tail。 */
        String getOutput() {
            var env = envelope();
            if (!env.truncated()) {
                return env.head() + env.tail();
            }
            return env.head()
                + "\n…[输出截断，省略 " + env.omittedBytes() + " 字节，sha256="
                + shortHash(env.sha256()) + "]…\n"
                + env.tail();
        }

        private static String shortHash(String sha256) {
            return sha256 != null && sha256.length() >= 12 ? sha256.substring(0, 12) : "?";
        }
    }
}
