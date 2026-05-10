package com.miniclaw.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 在工作区内执行 bash 命令。
 * 核心安全机制：超时控制 + 工作区绑定 + 错误原样回传 + 输出截断。
 */
public class BashTool implements Tool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 8000;

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "要执行的 bash 命令，例如: ls -la 或 go test ./..."
            }
          },
          "required": ["command"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path workDir;
    private final String shell;
    private final String shellFlag;

    /** 静态检测：Windows 上优先用 Git Bash 完整路径（避开 WSL shim），不存在则回退 cmd */
    private static final String[] DETECTED_SHELL = detectShell();

    private static String[] detectShell() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows) {
            return new String[]{"bash", "-c"};
        }
        // 优先找 Git Bash，避免命中 C:\Windows\System32\bash.exe (WSL shim)
        for (String candidate : new String[]{
                "D:\\Git\\usr\\bin\\bash.exe",
                "D:\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "C:\\Program Files\\Git\\bin\\bash.exe"}) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(candidate))) {
                return new String[]{candidate, "-c"};
            }
        }
        return new String[]{"cmd", "/c"};
    }

    public BashTool(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.shell = DETECTED_SHELL[0];
        this.shellFlag = DETECTED_SHELL[1];
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "在当前工作区执行任意的 bash 命令。支持链式命令(如 &&)。返回标准输出和标准错误。";
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

        JsonNode cmdNode = argsNode.get("command");
        if (cmdNode == null || cmdNode.asText().isEmpty()) {
            return new Result.Err<>(new Result.ErrorInfo("T-002", "缺少必需参数 'command'"));
        }

        // 2. 构建进程：绑定 workDir + shell 包装
        ProcessBuilder pb = new ProcessBuilder(shell, shellFlag, cmdNode.asText());
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        // 3. 执行 + 超时控制
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "启动进程失败: " + e.getMessage()));
        }

        String output;
        boolean timedOut = false;
        try {
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                timedOut = true;
            }
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result.Err<>(new Result.ErrorInfo("T-004", "命令执行被中断"));
        } catch (IOException e) {
            return new Result.Err<>(new Result.ErrorInfo("T-005", "读取命令输出失败: " + e.getMessage()));
        }

        // 超时警告
        if (timedOut) {
            output = output + "\n[警告: 命令执行超时(" + TIMEOUT_SECONDS + "s)，已被系统强制终止。如果是启动常驻服务，请尝试将其转入后台。]";
            return new Result.Ok<>(truncate(output));
        }

        // 空输出
        if (output.isEmpty()) {
            return new Result.Ok<>("命令执行成功，无终端输出。");
        }

        return new Result.Ok<>(truncate(output));
    }

    /** 长度截断保护 — 防 OOM */
    private String truncate(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            String head = new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8);
            return head + "\n\n...[终端输出过长，已截断至前 " + MAX_OUTPUT_BYTES + " 字节]...";
        }
        return output;
    }
}
