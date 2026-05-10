package com.miniclaw.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 按文件名模式查找文件。支持 ** 递归匹配通配符。
 */
public class GlobTool implements Tool {

    private static final int MAX_FILES = 200;
    private static final int MAX_OUTPUT_BYTES = 8000;

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "文件名的 glob 模式，如 '**/*.java'、'src/**/*Test*'。* 匹配任意字符(不跨目录)，** 递归匹配任意层级。"
            }
          },
          "required": ["pattern"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;

    public GlobTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "按文件名模式查找文件。支持 ** 递归匹配。返回匹配文件的相对路径列表。";
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

        JsonNode patternNode = argsNode.get("pattern");
        if (patternNode == null || patternNode.asText().isEmpty()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'pattern'"));
        }
        String pattern = patternNode.asText();

        // 2. 遍历工作区，收集匹配项
        List<String> matches = new ArrayList<>();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        try (Stream<Path> stream = Files.walk(workDir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path absPath = it.next();
                if (Files.isDirectory(absPath)) continue;

                Path relPath = workDir.relativize(absPath);
                String relStr = relPath.toString().replace('\\', '/');
                if (matcher.matches(Path.of(relStr))) {
                    matches.add(relStr);
                    if (matches.size() >= MAX_FILES + 1) break; // 多取一个用于判断是否超限
                }
            }
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "遍历文件系统失败: " + e.getMessage()));
        }

        // 3. 格式化输出
        if (matches.isEmpty()) {
            return new Result.Ok<>("未找到匹配 \"" + pattern + "\" 的文件。");
        }

        matches.sort(Comparator.naturalOrder());

        boolean truncated = matches.size() > MAX_FILES;
        if (truncated) {
            matches = matches.subList(0, MAX_FILES);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(matches.size()).append(" 个匹配 \"").append(pattern).append("\" 的文件");
        if (truncated) sb.append("（已截断至前 ").append(MAX_FILES).append(" 条）");
        sb.append(":\n");
        for (String m : matches) {
            sb.append("  ").append(m).append("\n");
        }

        return new Result.Ok<>(truncate(sb.toString()));
    }

    private String truncate(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            String head = new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8);
            return head + "\n\n...[输出过长，已截断至前 " + MAX_OUTPUT_BYTES + " 字节]...";
        }
        return output;
    }
}
