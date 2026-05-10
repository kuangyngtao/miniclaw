package com.miniclaw.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 创建或覆盖写入文件，自动创建父目录，限制在工作区范围内。
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
            }
          },
          "required": ["path", "content"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;

    public WriteTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "创建或覆盖写入一个文件。如果目录不存在会自动创建。请提供相对工作区的路径。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
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

        // 2. 路径解析 + 穿越防护
        Path resolved = workDir.resolve(pathNode.asText()).normalize();
        if (!resolved.startsWith(workDir)) {
            return new Result.Err<>(new Result.ErrorInfo("T-003", "禁止访问工作区外的路径: " + pathNode.asText()));
        }

        // 3. 自动创建父目录
        try {
            Files.createDirectories(resolved.getParent());
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "创建父目录失败: " + e.getMessage()));
        }

        // 4. 写入文件
        try {
            Files.writeString(resolved, contentNode.asText(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "写入文件失败: " + e.getMessage()));
        }

        return new Result.Ok<>("成功将内容写入到文件: " + pathNode.asText());
    }
}
