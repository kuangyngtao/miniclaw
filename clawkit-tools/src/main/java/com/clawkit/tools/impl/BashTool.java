package com.clawkit.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.ProcessRunner;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.ToolSideEffect;
import com.clawkit.tools.ToolError;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolOutputStats;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在工作区内执行 bash 命令。
 * 核心安全机制：超时控制 + 工作区绑定 + 错误原样回传 + 输出截断。
 */
public class BashTool implements Tool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 8000;

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "要执行的 bash 命令，例如: ls -la 或 go test ./..."
            }
          },
          "required": ["command"]
        }""";

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;
    private final String shell;
    private final String shellFlag;
    private final ProcessRunner processRunner;

    /** 静态检测：Windows 上优先用 Git Bash 完整路径（避开 WSL shim），不存在则回退 cmd */
    private static final String[] DETECTED_SHELL = detectShell();

    private static String[] detectShell() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows) {
            return new String[]{"bash", "-c"};
        }
        // 1. 环境变量 GIT_BASH
        String envBash = System.getenv("GIT_BASH");
        if (envBash != null && java.nio.file.Files.exists(java.nio.file.Path.of(envBash))) {
            return new String[]{envBash, "-c"};
        }
        // 2. PATH 中查找 git 安装目录下的 bash（避免 System32 的 WSL shim）
        String pathBash = findBashInPath();
        if (pathBash != null) {
            return new String[]{pathBash, "-c"};
        }
        // 3. 常见安装路径
        for (String candidate : new String[]{
                "D:\\Git\\usr\\bin\\bash.exe",
                "D:\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "C:\\Program Files\\Git\\bin\\bash.exe"}) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(candidate))) {
                return new String[]{candidate, "-c"};
            }
        }
        return new String[]{"cmd", "/c"};
    }

    private static String findBashInPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.toLowerCase().contains("system32")) continue;
            java.nio.file.Path candidate = java.nio.file.Path.of(dir, "bash.exe");
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    public BashTool(Path workDir) {
        this(workDir, new DefaultProcessRunner());
    }

    public BashTool(Path workDir, ProcessRunner processRunner) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.shell = DETECTED_SHELL[0];
        this.shellFlag = DETECTED_SHELL[1];
        this.processRunner = processRunner;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在当前工作区执行任意的 bash 命令。支持链式命令(如 &&)。返回标准输出和标准错误。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    /**
     * P1-G4：任意 Shell 的动作描述符。
     * 目标按命令内容划分：结果未知只 sticky 锁定同一命令的重复执行，
     * 不冻结整个工作区的其他 shell 动作；不可逆、无可信恢复能力，
     * 验证策略 MANUAL_REQUIRED（可记录动作，但不得自动进入 VERIFIED_SUCCESS）。
     */
    @Override
    public com.clawkit.tools.action.ActionDescriptor describeAction(
            com.clawkit.tools.ToolExecutionRequest req) {
        JsonNode args = req.arguments();
        if (args == null) return null;
        JsonNode cmdNode = args.get("command");
        if (cmdNode == null || cmdNode.asText().isEmpty()) return null;
        String command = cmdNode.asText();
        String commandDigest = com.clawkit.tools.action.Digests.sha256Hex(command);
        return new com.clawkit.tools.action.ActionDescriptor(
            "bash.exec",
            com.clawkit.tools.action.ActionTargets.shellTarget(workDir)
                + ":cmd:" + commandDigest.substring(0, 16),
            commandDigest,
            ToolRiskLevel.HIGH,
            com.clawkit.tools.action.Reversibility.IRREVERSIBLE,
            com.clawkit.tools.action.ActionReliability.none(),
            com.clawkit.tools.action.VerificationMode.MANUAL_REQUIRED,
            java.util.List.of(), java.util.List.of(),
            "", "workspace-wide shell: " + shellCmd(command));
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
            name(), description(), null, null,
            new ToolBehavior(false, ToolRiskLevel.HIGH, true, false, true, false,
                Set.of(ToolSideEffect.SHELL_EXEC)),
            new ToolExecutionPolicy(Duration.ofSeconds(TIMEOUT_SECONDS), MAX_OUTPUT_BYTES,
                ToolExecutionPolicy.OutputTruncation.HEAD_TAIL, ToolExecutionPolicy.ToolConcurrency.SERIAL),
            ToolMetadataProvenance.builtin(name())
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest req) {
        long start = System.currentTimeMillis();
        try {
            JsonNode argsNode = req.arguments();
            if (argsNode == null) {
                return ToolExecutionResult.invalidArguments(
                    req.toolCallId(), name(), "参数 JSON 解析失败", 0, metadata());
            }

            JsonNode cmdNode = argsNode.get("command");
            if (cmdNode == null || cmdNode.asText().isEmpty()) {
                return ToolExecutionResult.invalidArguments(
                    req.toolCallId(), name(), "缺少必需参数 'command'", 0, metadata());
            }

            String command = cmdNode.asText();
            String useShell = shell;
            String useFlag = shellFlag;
            if (isWindows() && needsCmdExe(command) && !"cmd".equals(shell)) {
                useShell = "cmd";
                useFlag = "/c";
            }

            ProcessRunner.ProcessExecutionRequest pr = new ProcessRunner.ProcessExecutionRequest(
                workDir, command, useShell, useFlag,
                Duration.ofSeconds(TIMEOUT_SECONDS), MAX_OUTPUT_BYTES, null,
                req.scope() != null ? req.scope().control() : null);
            ProcessRunner.ProcessExecutionResult presult = processRunner.execute(pr);
            long duration = System.currentTimeMillis() - start;

            String output = formatOutput(presult);
            com.clawkit.tools.OutputEnvelope envelope =
                combineEnvelopes(presult.stdoutEnvelope(), presult.stderrEnvelope());

            // P0-4：stats 从 envelope 唯一派生，消除双写漂移
            byte[] outputBytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ToolOutputStats stats = new ToolOutputStats(
                envelope.totalBytes(),           // totalBytes — 观察到的源 bytes
                outputBytes.length,              // returnedBytes — 模型实际看到的
                envelope.returnedBytes(),        // retainedSourceBytes — reducer 选取的源 bytes
                -1,                              // totalLines unknown
                -1,                              // returnedLines unknown
                envelope.truncated(),            // truncated
                envelope.truncationReason(),     // truncationReason
                "LEGACY_V0",                     // retentionPolicy
                true                             // inputComplete — Bash 读完
            );

            if (presult.cancelled()) {
                // 派发前取消 → 确认无副作用；进程已启动后取消 → 结果未知
                boolean beforeDispatch = "[CANCELLED_BEFORE_START]".equals(presult.stderr());
                return ToolExecutionResult.cancelled(
                    req.toolCallId(), name(),
                    beforeDispatch
                        ? "命令在启动前已被取消。"
                        : "命令执行中被取消，进程树已终止；命令可能已产生部分副作用。\n" + truncate(output),
                    duration, metadata(), beforeDispatch)
                    .withOutputEnvelope(envelope);
            }

            if (presult.timedOut()) {
                output = output + "\n[警告: 命令执行超时(" + TIMEOUT_SECONDS + "s)，已被系统强制终止。]";
                return ToolExecutionResult.timedOut(
                    req.toolCallId(), name(), truncate(output), duration, stats, metadata())
                    .withOutputEnvelope(envelope);
            }

            if (presult.exitCode() != 0) {
                return ToolExecutionResult.of(
                    req.toolCallId(), name(),
                    "[exit=" + presult.exitCode() + "]\n" + truncate(output),
                    ToolExecutionStatus.NON_ZERO_EXIT,
                    ToolError.fatal("NON_ZERO_EXIT", "Process exited with code " + presult.exitCode()),
                    duration, stats, presult.exitCode(), metadata(), null)
                    .withOutputEnvelope(envelope);
            }

            if (output.isEmpty()) {
                output = "命令执行成功，无终端输出。";
            }
            return ToolExecutionResult.success(
                req.toolCallId(), name(), truncate(output), duration, stats, metadata())
                .withOutputEnvelope(envelope);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolExecutionResult.internalError(
                req.toolCallId(), name(), e.getMessage(), duration, metadata());
        }
    }

    @Override
    @Deprecated
    public Result<String> execute(String arguments) {
        // 1. 解析参数
        JsonNode argsNode;
        try {
            argsNode = mapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "参数 JSON 解析失败: " + e.getMessage()));
        }

        JsonNode cmdNode = argsNode.get("command");
        if (cmdNode == null || cmdNode.asText().isEmpty()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'command'"));
        }

        String command = cmdNode.asText();
        String useShell = shell;
        String useFlag = shellFlag;

        // Windows: .cmd/.bat 文件直接走 cmd.exe /c
        if (isWindows() && needsCmdExe(command) && !"cmd".equals(shell)) {
            useShell = "cmd";
            useFlag = "/c";
        }

        // 2. 通过 ProcessRunner 执行
        ProcessRunner.ProcessExecutionRequest req = new ProcessRunner.ProcessExecutionRequest(
            workDir, command, useShell, useFlag,
            Duration.ofSeconds(TIMEOUT_SECONDS), MAX_OUTPUT_BYTES, null);

        ProcessRunner.ProcessExecutionResult pr = processRunner.execute(req);

        // 3. 映射结果
        if (pr.timedOut()) {
            String output = formatOutput(pr);
            output = output + "\n[警告: 命令执行超时(" + TIMEOUT_SECONDS + "s)，已被系统强制终止。]";
            log.warn("[Bash] {} → TIMEOUT ({}s)", shellCmd(command), TIMEOUT_SECONDS);
            return new Result.Err<>(new Result.ErrorInfo("TIMEOUT",
                truncate(output)));
        }

        if (pr.exitCode() != 0) {
            String output = formatOutput(pr);
            log.warn("[Bash] {} → exit={}", shellCmd(command), pr.exitCode());
            return new Result.Err<>(new Result.ErrorInfo("NON_ZERO_EXIT",
                "[exit=" + pr.exitCode() + "]\n" + truncate(output)));
        }

        String output = formatOutput(pr);
        if (output.isEmpty()) {
            log.info("[Bash] {} → 0 bytes (empty)", shellCmd(command));
            return new Result.Ok<>("命令执行成功，无终端输出。");
        }

        log.info("[Bash] {} → {} bytes", shellCmd(command), output.length());
        return new Result.Ok<>(truncate(output));
    }

    private String formatOutput(ProcessRunner.ProcessExecutionResult pr) {
        StringBuilder sb = new StringBuilder();
        if (!pr.stdout().isEmpty()) {
            sb.append("[stdout]\n").append(pr.stdout());
        }
        if (!pr.stderr().isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("[stderr]\n").append(pr.stderr());
        }
        // P1-G2：截断时附加错误片段，保证错误在中段也不丢失
        var envelope = combineEnvelopes(pr.stdoutEnvelope(), pr.stderrEnvelope());
        if (envelope != null && envelope.truncated() && !envelope.errorExcerpts().isEmpty()) {
            sb.append("\n[错误片段]");
            for (String excerpt : envelope.errorExcerpts()) {
                sb.append('\n').append(excerpt);
            }
        }
        return sb.toString();
    }

    /**
     * 合并 stdout/stderr 信封为结果级信封。
     * head 优先 stdout；tail 优先 stderr（错误更可能在 stderr 尾部）；
     * sha256 为两路 hash 的派生组合。
     */
    static com.clawkit.tools.OutputEnvelope combineEnvelopes(
            com.clawkit.tools.OutputEnvelope stdout, com.clawkit.tools.OutputEnvelope stderr) {
        if (stdout == null && stderr == null) return null;
        if (stdout == null) return stderr;
        if (stderr == null) return stdout;
        var excerpts = new java.util.ArrayList<>(stdout.errorExcerpts());
        excerpts.addAll(stderr.errorExcerpts());
        String head = !stdout.head().isEmpty() ? stdout.head() : stderr.head();
        String tail = !stderr.tail().isEmpty() ? stderr.tail() : stdout.tail();
        String reason = stdout.truncationReason() != null
            ? stdout.truncationReason() : stderr.truncationReason();
        String sha = com.clawkit.tools.action.Digests.sha256Hex(
            "stdout:" + stdout.sha256() + " stderr:" + stderr.sha256());
        long total = stdout.totalBytes() + stderr.totalBytes();
        long returned = head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + tail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        returned = Math.min(total, returned);
        return new com.clawkit.tools.OutputEnvelope(
            head, tail, excerpts,
            total, returned, total - returned,
            total > returned ? reason : null, sha, java.util.List.of(),
            stdout.redactionApplied() || stderr.redactionApplied(), "UTF-8");
    }

    private static String shellCmd(String cmd) {
        return cmd.length() <= 80 ? cmd : cmd.substring(0, 77) + "...";
    }

    /**
     * 长度截断保护 — 纯 OOM 兜底。
     * P1-G2 后语义截断由 BoundedOutputCollector（head/tail/错误片段）完成，
     * 此处上限放宽为 2×MAX_OUTPUT_BYTES，仅防御异常超长拼装。
     */
    private String truncate(String output) {
        int cap = MAX_OUTPUT_BYTES * 2;
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > cap) {
            String head = new String(bytes, 0, cap, StandardCharsets.UTF_8);
            return head + "\n\n...[终端输出过长，已截断至前 " + cap + " 字节]...";
        }
        return output;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static boolean needsCmdExe(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) return false;
        // Check if the first word (the executable) ends with .cmd or .bat
        String exe = parts[0].toLowerCase();
        return exe.endsWith(".cmd") || exe.endsWith(".bat")
            // Also detect "cmd.exe /c ..." being passed to bash (double wrapping)
            || exe.equals("cmd.exe") || exe.equals("cmd");
    }
}
