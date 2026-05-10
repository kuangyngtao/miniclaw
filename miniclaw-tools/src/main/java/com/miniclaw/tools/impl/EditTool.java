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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 编辑工具 — 四级容错降级替换。
 * 安全底线：唯一性校验贯穿四级，匹配到多处绝不盲目替换。
 */
public class EditTool implements Tool {

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "要修改的文件路径"
            },
            "old_text": {
              "type": "string",
              "description": "文件中原有的文本。必须包含足够的上下文（建议上下各多包含几行），以确保在文件中的唯一性。"
            },
            "new_text": {
              "type": "string",
              "description": "要替换成的新文本"
            }
          },
          "required": ["path", "old_text", "new_text"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;

    public EditTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "对现有文件进行局部字符串替换。请提供足够的 old_text 上下文以确保匹配的唯一性。";
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
        JsonNode oldNode = argsNode.get("old_text");
        if (oldNode == null || oldNode.asText().isEmpty()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'old_text'"));
        }
        JsonNode newNode = argsNode.get("new_text");
        if (newNode == null) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'new_text'"));
        }

        // 2. 路径穿越防护
        Path resolved = workDir.resolve(pathNode.asText()).normalize();
        if (!resolved.startsWith(workDir)) {
            return new Result.Err<>(new Result.ErrorInfo("T-003", "禁止访问工作区外的路径: " + pathNode.asText()));
        }

        // 3. 读取原文件
        String originalContent;
        try {
            originalContent = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "读取文件失败: " + e.getMessage()));
        }

        // 4. 四级模糊替换
        String newContent;
        try {
            newContent = fuzzyReplace(originalContent, oldNode.asText(), newNode.asText());
        } catch (FuzzyMatchException e) {
            return new Result.Err<>(new Result.ErrorInfo(e.errorCode, e.getMessage()));
        }

        // 5. 写回文件
        try {
            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "写回文件失败: " + e.getMessage()));
        }

        return new Result.Ok<>("成功修改文件: " + pathNode.asText());
    }

    // ---- 四级容错降级算法 ----

    static String fuzzyReplace(String originalContent, String oldText, String newText) throws FuzzyMatchException {
        // L1: 精确匹配
        int count = countOccurrences(originalContent, oldText);
        if (count == 1) {
            return originalContent.replace(oldText, newText);
        }
        if (count > 1) {
            throw new FuzzyMatchException("E-001",
                "old_text 匹配到了 " + count + " 处，请提供更多的上下文代码以确保唯一性");
        }

        // L2: 换行符归一化
        String normalized = originalContent.replace("\r\n", "\n");
        String normalizedOld = oldText.replace("\r\n", "\n");

        count = countOccurrences(normalized, normalizedOld);
        if (count == 1) {
            return normalized.replace(normalizedOld, newText);
        }
        if (count > 1) {
            throw new FuzzyMatchException("E-001",
                "old_text 匹配到了 " + count + " 处，请提供更多的上下文代码以确保唯一性");
        }

        // L3: Trim 首尾空行/空格
        String trimmedOld = normalizedOld.strip();
        if (!trimmedOld.isEmpty()) {
            count = countOccurrences(normalized, trimmedOld);
            if (count == 1) {
                return normalized.replace(trimmedOld, newText);
            }
            if (count > 1) {
                throw new FuzzyMatchException("E-001",
                    "old_text 匹配到了 " + count + " 处，请提供更多的上下文代码以确保唯一性");
            }
        }

        // L4: 逐行去缩进滑动窗口匹配
        return lineByLineReplace(normalized, trimmedOld.isEmpty() ? normalizedOld : trimmedOld, newText);
    }

    /**
     * L4: 将文本按行切分，去掉每行首尾空格后进行滑动窗口匹配。
     * 这是最强力的容错——消除大模型遗漏缩进、多余缩进的幻觉。
     */
    private static String lineByLineReplace(String content, String oldText, String newText)
        throws FuzzyMatchException {

        String[] contentLines = content.split("\n", -1);
        String[] oldLines = oldText.split("\n", -1);

        // 清理 oldLines：每行去首尾空格
        for (int i = 0; i < oldLines.length; i++) {
            oldLines[i] = oldLines[i].strip();
        }

        if (oldLines.length == 0 || contentLines.length < oldLines.length) {
            throw new FuzzyMatchException("E-002",
                "在文件中未找到 old_text，请先调用 read 仔细确认文件内容和缩进");
        }

        int matchCount = 0;
        int matchStart = -1;
        int matchEnd = -1;

        // 滑动窗口匹配
        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            boolean isMatch = true;
            for (int j = 0; j < oldLines.length; j++) {
                if (!contentLines[i + j].strip().equals(oldLines[j])) {
                    isMatch = false;
                    break;
                }
            }
            if (isMatch) {
                matchCount++;
                matchStart = i;
                matchEnd = i + oldLines.length;
            }
        }

        if (matchCount == 0) {
            throw new FuzzyMatchException("E-002",
                "在文件中未找到 old_text，请先调用 read 仔细确认文件内容和缩进");
        }
        if (matchCount > 1) {
            throw new FuzzyMatchException("E-001",
                "模糊匹配到了 " + matchCount + " 处相似代码，请提供更多上下行代码以精确定位");
        }

        // 执行替换：保留匹配块前后的原始行，中间插入 newText
        List<String> resultLines = new ArrayList<>();
        resultLines.addAll(Arrays.asList(contentLines).subList(0, matchStart));
        resultLines.add(newText);
        resultLines.addAll(Arrays.asList(contentLines).subList(matchEnd, contentLines.length));

        return String.join("\n", resultLines);
    }

    /** 统计非重叠出现次数 */
    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 模糊匹配内部异常，携带 errorCode */
    static class FuzzyMatchException extends Exception {
        final String errorCode;

        FuzzyMatchException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }
}
