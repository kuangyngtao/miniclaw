package com.miniclaw.tools.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.miniclaw.tools.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GlobToolTest {

    @Test
    void findsJavaFiles() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-glob-ut");
        Files.createDirectories(workDir.resolve("src/main/java/com/test"));
        Files.createFile(workDir.resolve("src/main/java/com/test/App.java"));
        Files.createFile(workDir.resolve("src/main/java/com/test/Util.java"));
        Files.createFile(workDir.resolve("README.md"));

        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"**/*.java\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("App.java"));
        assertTrue(output.contains("Util.java"));
        assertFalse(output.contains("README.md"));
    }

    @Test
    void findsSpecificDirectory() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-glob-ut2");
        Files.createDirectories(workDir.resolve("src/main/java"));
        Files.createDirectories(workDir.resolve("src/test/java"));
        Files.createFile(workDir.resolve("src/main/java/App.java"));
        Files.createFile(workDir.resolve("src/test/java/AppTest.java"));

        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"src/test/**/*.java\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("AppTest.java"));
        assertFalse(output.contains("App.java"));
    }

    @Test
    void noMatchesReturnsOk() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-glob-ut3");
        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"**/*.rs\"}");

        assertInstanceOf(Result.Ok.class, r);
        assertTrue(((Result.Ok<String>) r).data().contains("未找到"));
    }

    @Test
    void missingPatternReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-glob-ut4");
        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
    }

    @Test
    void emptyPatternReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("miniclaw-glob-ut5");
        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"\"}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
    }
}
