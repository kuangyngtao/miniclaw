package com.clawkit.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolBehavior;
import com.clawkit.tools.ToolExecutionPolicy;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.ToolSideEffect;
import com.clawkit.tools.WorkspacePathPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 创建或覆盖写入文件，自动创建父目录，限制在工作区范围内。
 *
 * <p>V2：增加 overwrite 参数 + 原子写入 + symlink 防护。
 */
public class WriteTool implements Tool {

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "要写入的文件路径，如 src/main/App.java"
            },
            "content": {
              "type": "string",
              "description": "要写入的完整文件内容"
            },
            "overwrite": {
              "type": "boolean",
              "description": "是否覆盖已存在的非空文件。默认 false。",
              "default": false
            }
          },
          "required": ["path", "content"]
        }""";

    private static final Logger log = LoggerFactory.getLogger(WriteTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final WorkspacePathPolicy pathPolicy;
    private final Path workDir;

    public WriteTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.pathPolicy = new WorkspacePathPolicy(workDir);
    }

    public WriteTool(Path workDir, WorkspacePathPolicy pathPolicy) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.pathPolicy = pathPolicy;
    }

    @Override
    public String name() { return "write"; }

    @Override
    public String description() {
        return "创建或覆盖写入一个文件。如果目录不存在会自动创建。请提供相对工作区的路径。";
    }

    @Override
    public String inputSchema() { return SCHEMA; }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
            name(), description(), null, null,
            new ToolBehavior(false, ToolRiskLevel.HIGH, true, false, false, false,
                Set.of(ToolSideEffect.FILE_WRITE)),
            new ToolExecutionPolicy(Duration.ofSeconds(10), 200,
                ToolExecutionPolicy.OutputTruncation.HEAD, ToolExecutionPolicy.ToolConcurrency.SERIAL),
            ToolMetadataProvenance.builtin(name())
        );
    }

    @Override
    public Result<String> execute(String arguments) {
        // 1. 解析参数
        JsonNode argsNode;
        try {
            argsNode = mapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "参数 JSON 解析失败: " + e.getMessage()));
        }

        JsonNode pathNode = argsNode.get("path");
        if (pathNode == null || pathNode.asText().isEmpty()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'path'"));
        }
        JsonNode contentNode = argsNode.get("content");
        if (contentNode == null) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'content'"));
        }

        boolean overwrite = argsNode.has("overwrite") && argsNode.get("overwrite").asBoolean(false);

        // 2. 路径解析 + symlink 防护
        Path resolved;
        try {
            resolved = pathPolicy.resolve(pathNode.asText(), true);
        } catch (WorkspacePathPolicy.PathEscapeException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-003", e.getMessage()));
        }

        // 3. 覆盖检查
        if (Files.exists(resolved) && Files.isRegularFile(resolved)) {
            try {
                long size = Files.size(resolved);
                if (size > 0 && !overwrite) {
                    return new Result.Err<>(new Result.ErrorInfo("OVERWRITE_REQUIRED",
                        "文件已存在且非空（" + size + " bytes）。请设置 overwrite=true 以确认覆盖。"));
                }
            } catch (IOException e) {
                return new Result.Err<>(new Result.ErrorInfo("T-005", "无法读取文件状态: " + e.getMessage()));
            }
        }

        // 4. 自动创建父目录
        try {
            Files.createDirectories(resolved.getParent());
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "创建父目录失败: " + e.getMessage()));
        }

        // 5. 原子写入：临时文件 + move
        Path tmpFile = resolved.resolveSibling("." + resolved.getFileName() + ".tmp");
        try {
            Files.writeString(tmpFile, contentNode.asText(), StandardCharsets.UTF_8);
            // 写入后再检查一次边界（TOCTOU 防护）
            pathPolicy.requireWriteAccess(resolved);
            Files.move(tmpFile, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 清理临时文件
            try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            log.warn("[Write] {} → FAILED: {}", pathNode.asText(), e.getMessage());
            return new Result.Err<>(new Result.ErrorInfo("T-005", "写入文件失败: " + e.getMessage()));
        } catch (WorkspacePathPolicy.PathEscapeException e) {
            try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            return new Result.Err<>(new Result.ErrorInfo("T-003", e.getMessage()));
        }

        log.info("[Write] {} → {} bytes", pathNode.asText(), contentNode.asText().length());
        return new Result.Ok<>("成功将内容写入到文件: " + pathNode.asText());
    }
}
