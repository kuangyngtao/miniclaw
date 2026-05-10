package com.miniclaw.tools.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.miniclaw.tools.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GrepToolTest {

    private static final String TEST_JAVA = """
        package test;
        public class App {
            @Override
            public String toString() {
                return "App";
            }
        }
        """;

    @Test
    void findsRegexInFiles() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-grep-ut");
        Files.writeString(workDir.resolve("App.java"), TEST_JAVA, StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"@Override\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("@Override"));
        assertTrue(output.contains("App.java:3"));
    }

    @Test
    void searchWithGlobFilter() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-grep-ut2");
        Files.createDirectories(workDir.resolve("src"));
        Files.writeString(workDir.resolve("src/App.java"), "@Override", StandardCharsets.UTF_8);
        Files.writeString(workDir.resolve("src/README.md"), "@Override in markdown", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"@Override\",\"glob\":\"**/*.java\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("App.java"));
        assertFalse(output.contains("README.md"));
    }

    @Test
    void noMatchesReturnsOk() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-grep-ut3");
        Files.writeString(workDir.resolve("x.txt"), "hello world", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"NONEXISTENT\"}");

        assertInstanceOf(Result.Ok.class, r);
        assertTrue(((Result.Ok<String>) r).data().contains("未找到"));
    }

    @Test
    void invalidRegexReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-grep-ut4");
        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"[unclosed\"}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
        assertTrue(((Result.Err<String>) r).error().message().contains("语法"));
    }

    @Test
    void skipsIgnoredDirectories() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-grep-ut5");
        Files.createDirectories(workDir.resolve(".git"));
        Files.createDirectories(workDir.resolve("target"));
        Files.writeString(workDir.resolve(".git/config"), "TOKEN=secret", StandardCharsets.UTF_8);
        Files.writeString(workDir.resolve("target/build.log"), "TOKEN=secret", StandardCharsets.UTF_8);
        Files.createDirectories(workDir.resolve("src"));
        Files.writeString(workDir.resolve("src/App.java"), "no secret here", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"TOKEN\"}");

        assertInstanceOf(Result.Ok.class, r);
        assertTrue(((Result.Ok<String>) r).data().contains("未找到"));
    }

    @Test
    void missingPatternReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-grep-ut6");
        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
    }
}
