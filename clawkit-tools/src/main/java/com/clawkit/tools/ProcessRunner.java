package com.clawkit.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import java.util.List;

/**
 * 进程执行抽象。可注入 fake 实现用于测试。
 */
public interface ProcessRunner {

    ProcessExecutionResult execute(ProcessExecutionRequest request);

    /**
     * 直接执行命令数组（无 shell 包装）。
     * 用于 git 等不需要 shell 解释的命令。
     */
    default ProcessExecutionResult runDirect(
            List<String> command, Path workDir,
            Duration timeout, long maxOutputBytes) {
        // 默认回退到 shell 包装
        return execute(new ProcessExecutionRequest(
            workDir, String.join(" ", command), "bash", "-c",
            timeout, maxOutputBytes, null));
    }

    // ── request / result ─────────────────────────────────────────

    record ProcessExecutionRequest(
        Path workDir,
        String command,
        String shell,
        String shellFlag,
        Duration timeout,
        long maxOutputBytes,
        Map<String, String> envAllowlist
    ) {}

    record ProcessExecutionResult(
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        long totalOutputBytes,
        boolean truncated
    ) {
        public boolean success() { return exitCode == 0 && !timedOut; }
    }
}
