package com.clawkit.tools.impl;

import com.clawkit.tools.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);

            // 环境变量白名单
            Map<String, String> env = pb.environment();
            Map<String, String> allowed = buildAllowedEnv(env, null);
            env.clear();
            env.putAll(allowed);

            Process process = pb.start();

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
                stdoutCollector.totalBytes + stderrCollector.totalBytes,
                stdoutCollector.truncated || stderrCollector.truncated);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessExecutionResult("", "[CANCELLED]", -1, false, 0, false);
        } catch (Exception e) {
            log.warn("ProcessRunner.runDirect failed: {}", e.getMessage());
            return new ProcessExecutionResult("", e.getMessage(), -1, false, 0, false);
        }
    }

    @Override
    public ProcessExecutionResult execute(ProcessExecutionRequest req) {
        long totalOutputBytes = 0;
        boolean truncated = false;

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

            Process process = pb.start();

            // ── 启动并发 pump（在 waitFor 之前） ───────────────
            StreamCollector stdoutCollector = new StreamCollector(
                process.getInputStream(), req.maxOutputBytes());
            StreamCollector stderrCollector = new StreamCollector(
                process.getErrorStream(), req.maxOutputBytes());
            Thread stdoutThread = Thread.ofVirtual().start(stdoutCollector);
            Thread stderrThread = Thread.ofVirtual().start(stderrCollector);

            // ── 等待进程退出 ──────────────────────────────────
            long timeoutMs = req.timeout() != null ? req.timeout().toMillis() : 30_000;
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            int exitCode;

            if (!finished) {
                // 超时 → 先 SIGTERM 父进程 + 子树，再 SIGKILL
                var descendants = process.toHandle().descendants().toList();
                process.destroy();
                descendants.forEach(h -> { try { h.destroy(); } catch (Exception ignored) {} });
                boolean terminated = process.waitFor(2, TimeUnit.SECONDS);
                if (!terminated) {
                    process.destroyForcibly();
                    descendants.forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) {} });
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                // 安全获取 exit code
                try {
                    exitCode = process.exitValue();
                } catch (IllegalThreadStateException e) {
                    exitCode = -1;
                }

                // 等待泵完成
                stdoutThread.join(1000);
                stderrThread.join(1000);

                return new ProcessExecutionResult(
                    stdoutCollector.getOutput(), stderrCollector.getOutput(),
                    exitCode, true,
                    stdoutCollector.totalBytes + stderrCollector.totalBytes,
                    stdoutCollector.truncated || stderrCollector.truncated);
            }

            exitCode = process.exitValue();
            stdoutThread.join(1000);
            stderrThread.join(1000);

            totalOutputBytes = stdoutCollector.totalBytes + stderrCollector.totalBytes;
            truncated = stdoutCollector.truncated || stderrCollector.truncated;

            return new ProcessExecutionResult(
                stdoutCollector.getOutput(), stderrCollector.getOutput(),
                exitCode, false, totalOutputBytes, truncated);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessExecutionResult(
                "", "[CANCELLED]", -1, false, 0, false);
        } catch (Exception e) {
            log.warn("ProcessRunner failed: {}", e.getMessage());
            return new ProcessExecutionResult(
                "", e.getMessage(), -1, false, 0, false);
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

    private static class StreamCollector implements Runnable {
        private final InputStream stream;
        private final long maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long totalBytes;
        boolean truncated;

        StreamCollector(InputStream stream, long maxBytes) {
            this.stream = stream;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = stream.read(buf)) != -1) {
                    totalBytes += n;
                    if (!truncated) {
                        long remaining = maxBytes - buffer.size();
                        if (remaining > 0) {
                            buffer.write(buf, 0, (int) Math.min(n, remaining));
                        }
                        if (buffer.size() >= maxBytes) {
                            truncated = true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // stream closed
            }
        }

        String getOutput() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
