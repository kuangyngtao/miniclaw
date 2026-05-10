package com.miniclaw.tools.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.miniclaw.tools.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * EditTool 四级容错匹配单元测试。
 * 直接调用 fuzzyReplace 验证各级偏差输入被正确吸收。
 */
class EditToolTest {

    // 模拟 Go 源码：tab 缩进 + \n 换行
    private static final String ORIGINAL = """
        package main

        func main() {
        \t// TODO: 增加鉴权逻辑
        \tif user == nil {
        \t\tfmt.Println("no user, blocked")
        \t\treturn
        \t}
        \tfmt.Println("ok")
        }
        """;

    private static final String NEW_TEXT = """
        \t// TODO: 增加鉴权逻辑
        \tif !authenticated {
        \t\tfmt.Println("Forbidden!")
        \t\treturn
        \t}""";

    // === L1: 精确匹配 ===
    @Test
    void l1ExactMatch() throws EditTool.FuzzyMatchException {
        String oldText = """
            \t// TODO: 增加鉴权逻辑
            \tif user == nil {
            \t\tfmt.Println("no user, blocked")
            \t\treturn
            \t}""";

        String result = EditTool.fuzzyReplace(ORIGINAL, oldText, NEW_TEXT);
        assertTrue(result.contains("!authenticated"), "应包含新代码");
        assertFalse(result.contains("no user, blocked"), "旧代码应已移除");
    }

    // === L1: 匹配到多处应报错 ===
    @Test
    void l1MultipleMatches() {
        String dup = "package main\n\npackage main\n";
        var ex = assertThrows(EditTool.FuzzyMatchException.class,
            () -> EditTool.fuzzyReplace(dup, "package main", "foo"));
        assertEquals("E-001", ex.errorCode);
        assertTrue(ex.getMessage().contains("2"));
    }

    // === L2: 换行符归一化（\\r\\n → \\n）===
    @Test
    void l2NewlineNormalization() throws EditTool.FuzzyMatchException {
        // 模拟 LLM 用了 Windows 换行符
        String oldText = "\t// TODO: 增加鉴权逻辑\r\n\tif user == nil {\r\n\t\tfmt.Println(\"no user, blocked\")\r\n\t\treturn\r\n\t}";

        String result = EditTool.fuzzyReplace(ORIGINAL, oldText, NEW_TEXT);
        assertTrue(result.contains("!authenticated"), "旧代码应已移除");
    }

    // === L3: Trim 首尾多余空行 ===
    @Test
    void l3TrimBlankLines() throws EditTool.FuzzyMatchException {
        // 模拟 LLM 多给了首尾空行
        String oldText = """


            \t// TODO: 增加鉴权逻辑
            \tif user == nil {
            \t\tfmt.Println("no user, blocked")
            \t\treturn
            \t}

            """;

        String result = EditTool.fuzzyReplace(ORIGINAL, oldText, NEW_TEXT);
        assertTrue(result.contains("!authenticated"), "旧代码应已移除");
    }

    // === L4: 逐行去缩进匹配（核心容错）===
    @Test
    void l4StripIndentationMisMatch() throws EditTool.FuzzyMatchException {
        // 模拟 LLM 少了缩进（最常见幻觉：model 把 tab 当成空格或干脆丢了）
        String oldText = """
            // TODO: 增加鉴权逻辑
            if user == nil {
                fmt.Println("no user, blocked")
                return
            }""";

        String result = EditTool.fuzzyReplace(ORIGINAL, oldText, NEW_TEXT);
        assertTrue(result.contains("!authenticated"), "旧代码应已移除");
    }

    // === L4: 缩进不一致（tab vs 空格混淆）===
    @Test
    void l4MixedTabSpace() throws EditTool.FuzzyMatchException {
        // 模拟 LLM 用 4 空格替代了 tab
        String oldText = """
            // TODO: 增加鉴权逻辑
            if user == nil {
                fmt.Println("no user, blocked")
                return
            }""";

        String result = EditTool.fuzzyReplace(ORIGINAL, oldText, NEW_TEXT);
        assertTrue(result.contains("!authenticated"), "旧代码应已移除");
    }

    // === L4: 未找到应报错 ===
    @Test
    void l4NotFound() {
        var ex = assertThrows(EditTool.FuzzyMatchException.class,
            () -> EditTool.fuzzyReplace(ORIGINAL, "this line does not exist at all", "x"));
        assertEquals("E-002", ex.errorCode);
        assertTrue(ex.getMessage().contains("未找到"));
    }

    // === 集成 execute 方法：读文件 → 改 → 写回 ===
    @Test
    void executeIntegration() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-edit-ut");
        Path target = workDir.resolve("test.go");
        Files.writeString(target, ORIGINAL, StandardCharsets.UTF_8);

        EditTool tool = new EditTool(workDir);
        String args = """
            {"path":"test.go","old_text":"\\t// TODO: 增加鉴权逻辑\\n\\tif user == nil {\\n\\t\\tfmt.Println(\\"no user, blocked\\")\\n\\t\\treturn\\n\\t}","new_text":"\\treplaced ok"}""";

        Result<String> r = tool.execute(args);
        assertInstanceOf(Result.Ok.class, r);
        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("replaced ok"));
        assertFalse(content.contains("no user, blocked"));
    }

    // === 路径穿越防护 ===
    @Test
    void pathTraversalBlocked() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-edit-ut2");
        EditTool tool = new EditTool(workDir);
        String args = """
            {"path":"../secret.txt","old_text":"x","new_text":"y"}""";

        Result<String> r = tool.execute(args);
        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-003", ((Result.Err<String>) r).error().errorCode());
    }
}
