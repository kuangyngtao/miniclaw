package com.clawkit.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.ProcessRunner;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolError;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolOutputStats;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.WorkspacePathPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git 只读工具。封装高频只读 git 操作，减少模型对 Bash 的依赖。
 *
 * <p>支持操作：status / diff / log / show。
 * 所有操作通过 {@code git -C <workDir>} 绑定工作区，不修改仓库状态。
 *
 * <p>安全措施：
 * <ul>
 *   <li>target 拒绝 {@code -} 前缀，使用 {@code --end-of-options} 隔离</li>
 *   <li>清除 GIT_EXTERNAL_DIFF / GIT_PAGER / GIT_CONFIG_* 等外部程序触发变量</li>
 *   <li>所有命令加 {@code --no-pager}；diff/show 加 {@code --no-ext-diff --no-textconv}</li>
 *   <li>复用 ProcessRunner 进行进程树终止 + 环境白名单 + 流式泵</li>
 *   <li>path 参数经 WorkspacePathPolicy 校验</li>
 * </ul>
 */
public class GitReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GitReadTool.class);

    private static final int MAX_OUTPUT_BYTES = 8000;
    private static final int TIMEOUT_SECONDS = 15;
    private static final int MAX_LOG_COUNT = 50;
    private static final int DEFAULT_LOG_COUNT = 10;

    private static final Set<String> VALID_OPERATIONS = Set.of("status", "diff", "log", "show");

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "operation": {
              "type": "string",
              "enum": ["status", "diff", "log", "show"],
              "description": "Git只读操作: status=工作区状态, diff=未暂存变更, log=提交历史, show=提交详情"
            },
            "path": {
              "type": "string",
              "description": "可选。文件或目录的相对路径（相对于工作区）。不填则对整个仓库操作。"
            },
            "target": {
              "type": "string",
              "description": "可选。git show 的 commit/ref, git log 的起始 ref。仅接受 commit hash 或 ref 名称（不含前导-）。默认 HEAD。"
            },
            "max_count": {
              "type": "integer",
              "description": "可选。git log 返回的最大条数。默认 10，最小 1，最大 50。",
              "default": 10,
              "minimum": 1,
              "maximum": 50
            }
          },
          "required": ["operation"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ProcessRunner processRunner;
    private final WorkspacePathPolicy pathPolicy;
    private final ToolMetadata cachedMetadata;

    public GitReadTool(java.nio.file.Path workDir) {
        this(workDir, new DefaultProcessRunner());
    }

    public GitReadTool(java.nio.file.Path workDir, ProcessRunner processRunner) {
        this.processRunner = processRunner;
        this.pathPolicy = new WorkspacePathPolicy(workDir);
        this.cachedMetadata = buildMetadata();
    }

    // ── Tool 接口 ─────────────────────────────────────────────────

    @Override public String name() { return "git_read"; }

    @Override
    public String description() {
        return """
            对当前 git 仓库执行只读操作。
            当你需要查看 git 状态、diff、log 或 show 时，请使用本工具而非 bash——更快、更安全。
            操作说明：
            - status: 查看工作区状态（文件变更、未跟踪文件）
            - diff: 查看未暂存的代码变更
            - log: 查看提交历史（默认最近10条）
            - show: 查看指定提交的详细信息（默认 HEAD）""";
    }

    @Override public String inputSchema() { return SCHEMA; }
    @Override public boolean isReadOnly() { return true; }
    @Override public ToolMetadata metadata() { return cachedMetadata; }

    // ── V2 结构化执行 ─────────────────────────────────────────────

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest req) {
        long start = System.currentTimeMillis();
        try {
            JsonNode argsNode = req.arguments();
            if (argsNode == null) {
                return invalidArgs(req, "参数 JSON 解析失败", start);
            }

            String operation = validateOperation(argsNode);
            if (operation == null) {
                JsonNode opNode = argsNode.get("operation");
                String val = opNode != null ? opNode.asText() : "";
                return invalidArgs(req, "无效的 operation: " + val + "。支持: status, diff, log, show", start);
            }

            // 构建命令 + 安全校验
            List<String> cmd;
            try {
                cmd = buildCommand(operation, argsNode);
            } catch (IllegalArgumentException e) {
                return invalidArgs(req, e.getMessage(), start);
            }

            // 通过 ProcessRunner 执行（进程树终止 + 环境白名单 + 流式泵）
            ProcessRunner.ProcessExecutionResult pr = processRunner.runDirect(
                cmd, pathPolicy.getWorkDir(),
                Duration.ofSeconds(TIMEOUT_SECONDS), MAX_OUTPUT_BYTES);
            long duration = System.currentTimeMillis() - start;

            String output = formatGitOutput(pr);
            ToolOutputStats stats = new ToolOutputStats(
                pr.totalOutputBytes(), pr.totalOutputBytes(), pr.truncated());

            if (pr.timedOut()) {
                return ToolExecutionResult.timedOut(
                    req.toolCallId(), name(), truncate(output), duration, stats, metadata());
            }

            if (pr.exitCode() != 0) {
                String friendlyMsg = mapGitError(pr.exitCode(), output);
                return ToolExecutionResult.of(
                    req.toolCallId(), name(), truncate(friendlyMsg),
                    ToolExecutionStatus.TOOL_ERROR,
                    ToolError.fatal("GIT_ERROR", "[exit=" + pr.exitCode() + "]"),
                    duration, stats, pr.exitCode(), metadata(), null);
            }

            if (output.isEmpty()) {
                output = "(无输出)";
            }
            return ToolExecutionResult.success(
                req.toolCallId(), name(), truncate(output), duration, stats, metadata());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String msg = e.getMessage();
            if (msg != null && (msg.contains("No such file") || msg.contains("Cannot run program"))) {
                return ToolExecutionResult.of(
                    req.toolCallId(), name(), "git 命令不可用。请确保 git 已安装并在 PATH 中。",
                    ToolExecutionStatus.TOOL_ERROR, ToolError.fatal("T-005", msg),
                    duration, ToolOutputStats.EMPTY, null, metadata(), null);
            }
            return ToolExecutionResult.internalError(
                req.toolCallId(), name(), msg, duration, metadata());
        }
    }

    @Override
    @Deprecated
    public Result<String> execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            var req = new ToolExecutionRequest("legacy", name(), args, (com.clawkit.tools.ToolExecutionScope) null);
            ToolExecutionResult result = execute(req);
            if (result.success()) {
                return new Result.Ok<>(result.output());
            }
            return new Result.Err<>(new Result.ErrorInfo(
                result.errorCode() != null ? result.errorCode() : "GIT_ERROR", result.output()));
        } catch (Exception e) {
            return new Result.Err<>(new Result.ErrorInfo("GIT_ERROR", e.getMessage()));
        }
    }

    // ── 命令构建 + 安全校验 ──────────────────────────────────────

    private String validateOperation(JsonNode args) {
        JsonNode opNode = args.get("operation");
        if (opNode == null || opNode.asText().isEmpty()) return null;
        String op = opNode.asText().toLowerCase().trim();
        return VALID_OPERATIONS.contains(op) ? op : null;
    }

    private List<String> buildCommand(String operation, JsonNode args) {
        String target = args.has("target") ? args.get("target").asText().trim() : "HEAD";
        String userPath = args.has("path") ? args.get("path").asText() : null;

        // P0: 拒绝以 - 开头的 target（防止选项注入）
        if (target.startsWith("-")) {
            throw new IllegalArgumentException(
                "target 不能以 '-' 开头。请提供 commit hash 或 ref 名称。");
        }

        // P2: 校验 path 在工作区内
        if (userPath != null) {
            try {
                pathPolicy.resolve(userPath, false); // 只读校验
            } catch (WorkspacePathPolicy.PathEscapeException e) {
                throw new IllegalArgumentException("路径无效: " + e.getMessage());
            }
        }

        int maxCount = args.has("max_count")
            ? Math.max(1, Math.min(args.get("max_count").asInt(DEFAULT_LOG_COUNT), MAX_LOG_COUNT))
            : DEFAULT_LOG_COUNT;

        // 共享前缀：git -C <workDir> --no-pager
        List<String> prefix = List.of("git", "-C", pathPolicy.getWorkDir().toString(), "--no-pager");

        return switch (operation) {
            case "status" -> {
                var cmd = new ArrayList<>(prefix);
                cmd.add("status");
                cmd.add("--porcelain");
                if (userPath != null) { cmd.add("--"); cmd.add(userPath); }
                yield cmd;
            }
            case "diff" -> {
                var cmd = new ArrayList<>(prefix);
                cmd.add("diff");
                cmd.add("--no-ext-diff");   // P1: 禁止外部 diff
                cmd.add("--no-textconv");   // P1: 禁止 textconv
                if (userPath != null) { cmd.add("--"); cmd.add(userPath); }
                yield cmd;
            }
            case "log" -> {
                var cmd = new ArrayList<>(prefix);
                cmd.add("log");
                cmd.add("--oneline");
                cmd.add("-" + maxCount);
                cmd.add("--end-of-options"); // P0: 隔离 target
                cmd.add(target);
                yield cmd;
            }
            case "show" -> {
                var cmd = new ArrayList<>(prefix);
                cmd.add("show");
                cmd.add("--no-ext-diff");
                cmd.add("--no-textconv");
                cmd.add("--end-of-options"); // P0: 隔离 target
                cmd.add(target);
                if (userPath != null) { cmd.add("--"); cmd.add(userPath); }
                yield cmd;
            }
            default -> throw new IllegalArgumentException("未知 operation: " + operation);
        };
    }

    // ── 输出处理 ─────────────────────────────────────────────────

    private String formatGitOutput(ProcessRunner.ProcessExecutionResult pr) {
        StringBuilder sb = new StringBuilder();
        if (!pr.stdout().isEmpty()) sb.append(pr.stdout());
        if (!pr.stderr().isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(pr.stderr());
        }
        return sb.toString();
    }

    private String mapGitError(int exitCode, String output) {
        if (exitCode == 128) {
            if (output.contains("not a git repository")) {
                return "当前目录不是 git 仓库。请在 git 仓库中使用此工具。";
            }
            if (output.contains("bad revision") || output.contains("unknown revision")
                || output.contains("does not have any commits")) {
                return "指定的 ref/commit 不存在，或仓库尚无提交: " + output.trim();
            }
        }
        return "[exit=" + exitCode + "] " + output;
    }

    private String truncate(String output) {
        if (output == null || output.isEmpty()) return "";
        byte[] bytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) return output;
        String head = new String(bytes, 0, MAX_OUTPUT_BYTES, java.nio.charset.StandardCharsets.UTF_8);
        return head + "\n[... 输出已截断，总 " + bytes.length + " 字节]";
    }

    // ── Metadata 构建 ─────────────────────────────────────────────

    private ToolMetadata buildMetadata() {
        com.fasterxml.jackson.databind.JsonNode schemaNode = null;
        try { schemaNode = mapper.readTree(SCHEMA); } catch (Exception ignored) {}
        return new ToolMetadata(
            name(), description(), schemaNode, null,
            new ToolBehavior(
                true, ToolRiskLevel.MEDIUM, false, true, false, false, Set.of()),
            new ToolExecutionPolicy(
                Duration.ofSeconds(TIMEOUT_SECONDS), MAX_OUTPUT_BYTES,
                ToolExecutionPolicy.OutputTruncation.HEAD,
                ToolExecutionPolicy.ToolConcurrency.PARALLEL_SAFE),
            ToolMetadataProvenance.builtin(name())
        );
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    private ToolExecutionResult invalidArgs(ToolExecutionRequest req, String msg, long start) {
        long duration = System.currentTimeMillis() - start;
        return ToolExecutionResult.invalidArguments(
            req.toolCallId(), name(), msg, duration, metadata());
    }
}
