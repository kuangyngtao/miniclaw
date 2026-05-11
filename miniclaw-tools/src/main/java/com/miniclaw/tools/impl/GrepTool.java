package com.miniclaw.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 在文件内容中搜索正则表达式模式。支持通过 glob 参数限定搜索的文件范围。
 */
public class GrepTool implements Tool {

    private static final int MAX_MATCHES = 50;
    private static final int MAX_LINE_CHARS = 200;
    private static final int MAX_OUTPUT_BYTES = 8000;

    private static final Set<String> IGNORE_DIRS = Set.of(
        ".git", "node_modules", "target", "__pycache__", ".idea", ".vscode",
        ".miniclaw", "build", "dist", "vendor", ".svn");

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "要搜索的正则表达式，如 'public class \\\\w+'、'@Override'、'import.*List'"
            },
            "glob": {
              "type": "string",
              "description": "可选。限定文件搜索范围的 glob 模式，如 '**/*.java'。不填则搜索所有文件。"
            }
          },
          "required": ["pattern"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;

    public GrepTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在文件内容中搜索正则表达式模式。可通过 glob 参数限定搜索的文件范围。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() { return true; }

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

        // 2. 编译正则
        Pattern regex;
        try {
            regex = Pattern.compile(patternNode.asText());
        } catch (PatternSyntaxException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-002",
                "正则表达式语法错误: " + e.getMessage()));
        }

        // 3. 构建文件过滤 Matcher
        JsonNode globNode = argsNode.get("glob");
        final PathMatcher fileMatcher = (globNode != null && !globNode.asText().isEmpty())
            ? FileSystems.getDefault().getPathMatcher("glob:" + globNode.asText())
            : null;

        // 4. 收集候选文件
        List<Path> candidateFiles;
        try (Stream<Path> stream = Files.walk(workDir)) {
            candidateFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> !isIgnored(p))
                .filter(p -> {
                    if (fileMatcher == null) return true;
                    String relStr = workDir.relativize(p).toString().replace('\\', '/');
                    return fileMatcher.matches(Path.of(relStr));
                })
                .sorted(Comparator.naturalOrder())
                .toList();
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "遍历文件系统失败: " + e.getMessage()));
        }

        // 5. 逐文件逐行搜索
        List<Match> matches = new ArrayList<>();

        for (Path file : candidateFiles) {
            try {
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (MalformedInputException e) {
                    continue; // 跳过二进制文件
                }

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (regex.matcher(line).find()) {
                        String relPath = workDir.relativize(file).toString().replace('\\', '/');
                        String displayLine = line.length() > MAX_LINE_CHARS
                            ? line.substring(0, MAX_LINE_CHARS) + "..."
                            : line;
                        matches.add(new Match(relPath, i + 1, displayLine));
                        if (matches.size() >= MAX_MATCHES + 1) break; // 多取一个判断是否超限
                    }
                }
            } catch (IOException e) {
                continue; // 无法读取的文件静默跳过
            }
            if (matches.size() > MAX_MATCHES) break;
        }

        // 6. 格式化输出
        if (matches.isEmpty()) {
            return new Result.Ok<>("未找到匹配 \"" + patternNode.asText() + "\" 的文本。");
        }

        boolean truncated = matches.size() > MAX_MATCHES;
        if (truncated) {
            matches = matches.subList(0, MAX_MATCHES);
        }

        StringBuilder sb = new StringBuilder();
        for (Match m : matches) {
            sb.append(m.path).append(":").append(m.lineNo).append(":  ").append(m.content).append("\n");
        }
        sb.append("找到 ").append(matches.size()).append(" 处匹配");
        if (truncated) sb.append("（已截断至前 ").append(MAX_MATCHES).append(" 条）");
        sb.append("。");

        return new Result.Ok<>(truncate(sb.toString()));
    }

    /** 检查路径是否在忽略目录下 */
    private boolean isIgnored(Path path) {
        for (Path part : path) {
            if (IGNORE_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            String head = new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8);
            return head + "\n\n...[输出过长，已截断至前 " + MAX_OUTPUT_BYTES + " 字节]...";
        }
        return output;
    }

    private record Match(String path, int lineNo, String content) {}
}
