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
 * 读取指定路径的文件内容，限制在工作区范围内，超过阈值自动截断。
 */
public class ReadTool implements Tool {

    private static final int MAX_BYTES = 8000;

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "要读取的文件路径，如 src/main/App.java"
            }
          },
          "required": ["path"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;

    public ReadTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "读取指定路径的文件内容。请提供相对工作区的路径。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public Result<String> execute(String arguments) {
        // 1. 解析参数
        final JsonNode argsNode;
        try {
            argsNode = mapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "参数 JSON 解析失败: " + e.getMessage()));
        }

        JsonNode pathNode = argsNode.get("path");
        if (pathNode == null || pathNode.asText().isEmpty()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'path'"));
        }

        // 2. 路径解析 + 穿越防护
        Path resolved = workDir.resolve(pathNode.asText()).normalize();
        if (!resolved.startsWith(workDir)) {
            return new Result.Err<>(new Result.ErrorInfo("T-003", "禁止访问工作区外的路径: " + pathNode.asText()));
        }

        // 3. 物理读取
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolved);
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "读取文件失败: " + e.getMessage()));
        }

        // 4. 截断保护
        if (bytes.length > MAX_BYTES) {
            String head = new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8);
            String truncated = head + "\n\n...[由于内容过长，已被系统截断至前 " + MAX_BYTES + " 字节]...";
            return new Result.Ok<>(truncated);
        }

        return new Result.Ok<>(new String(bytes, StandardCharsets.UTF_8));
    }
}
