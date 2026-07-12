package com.clawkit.tools.impl;

import com.clawkit.tools.Result;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitReadToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private GitReadTool tool;

    @BeforeEach
    void setUp() throws Exception {
        new ProcessBuilder("git", "init")
            .directory(tempDir.toFile())
            .start()
            .waitFor(5, TimeUnit.SECONDS);

        Files.writeString(tempDir.resolve("test.txt"), "hello world");

        new ProcessBuilder("git", "add", ".")
            .directory(tempDir.toFile())
            .start()
            .waitFor(5, TimeUnit.SECONDS);

        new ProcessBuilder("git", "-c", "user.name=test", "-c", "user.email=test@test.com",
            "commit", "-m", "initial commit")
            .directory(tempDir.toFile())
            .start()
            .waitFor(5, TimeUnit.SECONDS);

        Files.writeString(tempDir.resolve("test.txt"), "hello world\nmodified line");

        tool = new GitReadTool(tempDir);
    }

    // ── status ────────────────────────────────────────────────────

    @Test
    void shouldReturnStatusInPorcelainFormat() {
        String args = json(op -> op.put("operation", "status"));
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String>) result).data()).contains("test.txt");
    }

    // ── diff ──────────────────────────────────────────────────────

    @Test
    void shouldReturnDiffWhenModified() {
        String args = json(op -> op.put("operation", "diff"));
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String>) result).data()).contains("modified line");
    }

    @Test
    void shouldReturnEmptyDiffWhenClean() throws Exception {
        new ProcessBuilder("git", "add", ".")
            .directory(tempDir.toFile())
            .start()
            .waitFor(5, TimeUnit.SECONDS);
        new ProcessBuilder("git", "-c", "user.name=test", "-c", "user.email=test@test.com",
            "commit", "-m", "commit all")
            .directory(tempDir.toFile())
            .start()
            .waitFor(5, TimeUnit.SECONDS);

        String args = json(op -> op.put("operation", "diff"));
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String>) result).data()).isEqualTo("(无输出)");
    }

    // ── log ───────────────────────────────────────────────────────

    @Test
    void shouldReturnLogWithDefaultCount() {
        String args = json(op -> op.put("operation", "log"));
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String>) result).data()).contains("initial commit");
    }

    @Test
    void shouldRespectMaxCount() {
        String args = json(op -> {
            op.put("operation", "log");
            op.put("max_count", 1);
        });
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String>) result).data().lines().count()).isEqualTo(1);
    }

    // ── show ──────────────────────────────────────────────────────

    @Test
    void shouldShowHeadCommit() {
        String args = json(op -> {
            op.put("operation", "show");
            op.put("target", "HEAD");
        });
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(((Result.Ok<String>) result).data()).contains("initial commit");
    }

    // ── 错误处理 ──────────────────────────────────────────────────

    @Test
    void shouldRejectInvalidOperation() {
        String args = json(op -> op.put("operation", "push"));
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) result).error().message()).contains("无效的 operation");
    }

    @Test
    void shouldFailOnNonGitDirectory() throws Exception {
        Path nonGitDir = Files.createTempDirectory("nongit");
        GitReadTool nonGitTool = new GitReadTool(nonGitDir);

        String args = json(op -> op.put("operation", "status"));
        Result<String> result = nonGitTool.execute(args);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) result).error().errorCode()).isEqualTo("GIT_ERROR");
        assertThat(((Result.Err<String>) result).error().message()).contains("不是 git 仓库");
    }

    // ── P0: 选项注入防御 ──────────────────────────────────────────

    @Test
    void shouldRejectTargetWithLeadingDash() {
        String args = json(op -> {
            op.put("operation", "show");
            op.put("target", "--output=leak.txt");
        });
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) result).error().message()).contains("不能以 '-' 开头");
    }

    @Test
    void shouldRejectTargetWithLeadingDashInLog() {
        String args = json(op -> {
            op.put("operation", "log");
            op.put("target", "--output=leak.txt");
        });
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<String>) result).error().message()).contains("不能以 '-' 开头");
    }

    @Test
    void shouldAcceptValidRefName() {
        String args = json(op -> {
            op.put("operation", "show");
            op.put("target", "HEAD");
        });
        Result<String> result = tool.execute(args);

        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    // ── P0: 零文件副作用 ─────────────────────────────────────────

    @Test
    void shouldNotCreateFilesOutsideRepo() throws Exception {
        Path outsideFile = Files.createTempFile("outside", ".txt");
        Files.deleteIfExists(outsideFile); // 确保初始不存在

        // 即使构造恶意 target，执行也不应在仓库外创建文件
        String args = json(op -> {
            op.put("operation", "show");
            op.put("target", "--output=" + outsideFile.toString().replace("\\", "/"));
        });
        tool.execute(args); // 应该被拒绝（target 以 - 开头）

        assertThat(Files.exists(outsideFile)).isFalse();
    }

    // ── P1: 结构化终态 ───────────────────────────────────────────

    @Test
    void shouldReturnStructuredResultWithStatus() {
        var req = new ToolExecutionRequest("tc-1", "git_read",
            mapper.createObjectNode().put("operation", "status"), (com.clawkit.tools.ToolExecutionScope) null);
        ToolExecutionResult result = tool.execute(req);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldReturnInvalidArgumentsForBadOperation() {
        var req = new ToolExecutionRequest("tc-1", "git_read",
            mapper.createObjectNode().put("operation", "push"), (com.clawkit.tools.ToolExecutionScope) null);
        ToolExecutionResult result = tool.execute(req);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.INVALID_ARGUMENTS);
    }

    // ── metadata ──────────────────────────────────────────────────

    @Test
    void shouldBeReadOnly() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    @Test
    void shouldHaveMediumRisk() {
        assertThat(tool.metadata().riskLevel()).isEqualTo(com.clawkit.tools.ToolRiskLevel.MEDIUM);
    }

    @Test
    void shouldNotRequireApproval() {
        assertThat(tool.metadata().requiresApproval()).isFalse();
    }

    @Test
    void shouldHaveInputSchemaInMetadata() {
        assertThat(tool.metadata().inputSchema()).isNotNull();
        assertThat(tool.metadata().inputSchema().has("properties")).isTrue();
        assertThat(tool.metadata().inputSchema().path("properties").path("operation")).isNotNull();
    }

    // ── helper ────────────────────────────────────────────────────

    private String json(java.util.function.Consumer<ObjectNode> builder) {
        ObjectNode node = mapper.createObjectNode();
        builder.accept(node);
        return node.toString();
    }
}
