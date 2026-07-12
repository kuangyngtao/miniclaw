package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionScope;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolOutputStats;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.ToolSideEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * MCP 工具 → clawkit Tool 接口适配（V2）。
 *
 * <p>V2 变更：
 * <ul>
 *   <li>metadata() 从 MCP annotations 映射，untrusted server 保守处理</li>
 *   <li>坏 JSON 参数 → INVALID_ARGUMENTS，不调用 transport</li>
 *   <li>isError=true → TOOL_ERROR</li>
 *   <li>移除 McpAuditLogger（审计由 ToolCallExecutor 统一生成）</li>
 * </ul>
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final String fullName;
    private final String description;
    private final String inputSchema;
    private final McpClient client;
    private final boolean trusted;
    private final JsonNode annotations;
    private final JsonNode outputSchema;
    private final ToolMetadata cachedMetadata;

    public McpToolAdapter(String serverName, McpToolDef toolDef, McpClient client,
                          String sanitizedSchema, boolean trusted) {
        this.serverName = serverName;
        this.toolName = toolDef.name();
        this.fullName = "mcp__" + serverName + "__" + toolName;
        this.description = toolDef.description();
        this.inputSchema = sanitizedSchema;
        this.client = client;
        this.trusted = trusted;
        this.annotations = toolDef.annotations();
        this.outputSchema = toolDef.outputSchema();
        this.cachedMetadata = buildMetadata();
    }

    // ── Tool 接口 ─────────────────────────────────────────────────

    @Override
    public String name() { return fullName; }

    @Override
    public String description() {
        return "[MCP:" + serverName + "] " + description;
    }

    @Override
    public String inputSchema() { return inputSchema; }

    @Override
    public boolean isReadOnly() {
        return cachedMetadata.readOnly();
    }

    @Override
    public ToolMetadata metadata() {
        return cachedMetadata;
    }

    /**
     * 旧接口（deprecated）。委托给结构化执行。
     */
    @Override
    @Deprecated
    public Result<String> execute(String arguments) {
        try {
            JsonNode args = MAPPER.readTree(arguments);
            var req = new ToolExecutionRequest("legacy", fullName, args, (ToolExecutionScope) null);
            ToolExecutionResult result = execute(req);
            if (result.success()) {
                return new Result.Ok<>(result.output());
            }
            return new Result.Err<>(new Result.ErrorInfo(
                result.errorCode() != null ? result.errorCode() : "MCP_ERROR",
                result.output()));
        } catch (Exception e) {
            return new Result.Err<>(new Result.ErrorInfo("MCP_ERROR", e.getMessage()));
        }
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest req) {
        long start = System.currentTimeMillis();

        // ── 解析参数：坏 JSON → INVALID_ARGUMENTS ────────────
        JsonNode argsNode;
        try {
            String argsJson = req.arguments() != null ? req.arguments().toString() : "{}";
            argsNode = MAPPER.readTree(argsJson);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolExecutionResult.invalidArguments(
                req.toolCallId(), fullName,
                "Invalid JSON arguments: " + e.getMessage(),
                duration, cachedMetadata);
        }

        // ── 调用 transport ──────────────────────────────────
        try {
            McpCallResult callResult = client.callTool(toolName, argsNode);
            long duration = System.currentTimeMillis() - start;
            byte[] outBytes = callResult.text().getBytes(StandardCharsets.UTF_8);

            if (callResult.isError()) {
                return ToolExecutionResult.of(
                    req.toolCallId(), fullName, callResult.text(),
                    ToolExecutionStatus.TOOL_ERROR,
                    com.clawkit.tools.ToolError.fatal("MCP_ERROR", callResult.text()),
                    duration,
                    new ToolOutputStats(outBytes.length, outBytes.length, false),
                    null, cachedMetadata, null);
            }

            return ToolExecutionResult.success(
                req.toolCallId(), fullName, callResult.text(),
                duration, cachedMetadata);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[MCP] {} failed: {}", fullName, e.getMessage());
            return ToolExecutionResult.of(
                req.toolCallId(), fullName,
                "[MCP:" + serverName + "] " + toolName + " — " + e.getMessage(),
                ToolExecutionStatus.TOOL_ERROR,
                com.clawkit.tools.ToolError.fatal("MCP_ERROR", e.getMessage()),
                duration,
                ToolOutputStats.EMPTY, null, cachedMetadata, null);
        }
    }

    // ── metadata 构建 ─────────────────────────────────────────────

    private ToolMetadata buildMetadata() {
        if (!trusted || annotations == null) {
            // untrusted server → 最保守默认值
            return new ToolMetadata(
                fullName, description, null, outputSchema,
                new ToolBehavior(false, ToolRiskLevel.HIGH, true, false, true, true, Set.of()),
                new ToolExecutionPolicy(Duration.ofSeconds(60), 8000,
                    ToolExecutionPolicy.OutputTruncation.HEAD, ToolExecutionPolicy.ToolConcurrency.SERIAL),
                ToolMetadataProvenance.mcp(serverName, toolName, false)
            );
        }

        // trusted server → 从 annotations 映射，缺失字段使用 MCP 保守默认值
        // MCP spec: missing destructiveHint → true, missing openWorldHint → true
        boolean readOnly = fieldBool(annotations, "readOnlyHint", false);
        boolean destructive = fieldBool(annotations, "destructiveHint", true);
        boolean idempotent = fieldBool(annotations, "idempotentHint", false);
        boolean openWorld = fieldBool(annotations, "openWorldHint", true);

        // 检查 annotations 冲突
        boolean annotationsConflict = (destructive && readOnly)
            || (openWorld && !readOnly);

        ToolRiskLevel riskLevel;
        boolean requiresApproval;

        if (annotationsConflict) {
            riskLevel = ToolRiskLevel.HIGH;
            requiresApproval = true;
        } else if (destructive) {
            riskLevel = ToolRiskLevel.HIGH;
            requiresApproval = false;
        } else if (readOnly) {
            if (openWorld) {
                riskLevel = ToolRiskLevel.MEDIUM;  // readOnly + openWorld → MEDIUM
                requiresApproval = false;
            } else {
                riskLevel = ToolRiskLevel.LOW;
                requiresApproval = false;
            }
        } else {
            // readOnlyHint=false 或缺失 → 至少 MEDIUM
            riskLevel = ToolRiskLevel.MEDIUM;
            requiresApproval = true;  // 非只读 → 要求审批
        }

        return new ToolMetadata(
            fullName, description, null, outputSchema,
            new ToolBehavior(readOnly, riskLevel, destructive, idempotent, openWorld, requiresApproval, Set.of()),
            new ToolExecutionPolicy(Duration.ofSeconds(60), 8000,
                ToolExecutionPolicy.OutputTruncation.HEAD, ToolExecutionPolicy.ToolConcurrency.SERIAL),
            ToolMetadataProvenance.mcp(serverName, toolName, true)
        );
    }

    /** 读取 annotations 布尔字段：存在则用字段值，缺失则用保守默认值 */
    private static boolean fieldBool(JsonNode node, String field, boolean defaultValue) {
        if (node == null || !node.has(field)) return defaultValue;
        return node.get(field).asBoolean(defaultValue);
    }
}
