package com.clawkit.engine.impl;

import com.clawkit.engine.AgentRuntimeDependencies;
import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;
import com.clawkit.reliability.attempt.ActionAttempt;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionIdentity;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Digests;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P1-G5：独立 Verification Run 的隔离性。 */
class VerificationIsolationTest {

    @TempDir
    Path dir;

    /** 记录每次请求（scope + messages）的 fake gateway */
    static class SpyGateway implements ProviderGateway {
        record Seen(RunScope scope, List<com.clawkit.tools.schema.Message> messages,
                    List<com.clawkit.tools.schema.ToolDefinition> tools) {}
        final List<Seen> requests = new ArrayList<>();

        @Override
        public ModelResponse generate(ModelRequest request, RunScope scope) {
            requests.add(new Seen(scope, List.copyOf(request.messages()),
                request.tools() != null ? List.copyOf(request.tools()) : List.of()));
            return new ModelResponse("独立复查完成：与确定性断言一致。", null, null, null, null);
        }

        @Override
        public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver o) {
            return generate(request, scope);
        }
    }

    private static ActionAttempt attempt(ActionDescriptor descriptor) {
        return new ActionAttempt("att-x",
            ActionIdentity.of("act-1", 1, descriptor.targetKey(), descriptor.fingerprint()),
            descriptor, AttemptState.VERIFICATION_PENDING, 5, 1,
            "remediation-run-42", null, "ACTION", null, Instant.now(), Instant.now());
    }

    @Test
    void verificationRunIsIsolatedFromRemediationContext() throws Exception {
        // 修复动作产物：文件 hash 匹配 expected effect
        Path file = dir.resolve("fixed.txt");
        String content = "repaired";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        String hash = Digests.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        var descriptor = new ActionDescriptor("file.write", "file:" + file, "d1",
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.idempotentSetter(), VerificationMode.WORKFLOW,
            List.of(), List.of("file-sha256:" + file + ":" + hash), "", "");

        SpyGateway gateway = new SpyGateway();
        ToolRegistry registry = new ToolRegistry(); // 空注册表：验证 run 无写工具
        var deps = new AgentRuntimeDependencies(gateway, null, registry, 128_000, null);
        var launcher = new VerificationRunLauncher(deps, dir.toString());

        var result = launcher.verify(attempt(descriptor));

        // 1. 确定性断言先行且具备判定权威
        assertThat(result.deterministicPassed()).isTrue();
        assertThat(result.relatedAttemptId()).isEqualTo("att-x");

        // 2. 新 root run：不继承修复 run 的 runId/parentRunId
        assertThat(gateway.requests).isNotEmpty();
        var scope = gateway.requests.get(0).scope();
        assertThat(scope.runId()).isNotEqualTo("remediation-run-42");
        assertThat(scope.parentRunId()).isNull();

        // 3. 验证输入只包含 Action Contract：不含修复会话的任何内容
        String allText = gateway.requests.get(0).messages().stream()
            .map(m -> m.content() != null ? m.content() : "")
            .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("[VERIFICATION]");
        assertThat(allText).contains("file.write");
        assertThat(allText).doesNotContain("remediation-run-42");

        // 4. 模型结论被记录但不具判定权威（由确定性断言承担）
        assertThat(result.modelConclusion()).isNotBlank();
    }

    @Test
    void deterministicFailureIsAuthoritativeEvenIfModelSaysOk() throws Exception {
        Path file = dir.resolve("fixed.txt");
        Files.writeString(file, "wrong content", StandardCharsets.UTF_8);
        var descriptor = new ActionDescriptor("file.write", "file:" + file, "d1",
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.idempotentSetter(), VerificationMode.WORKFLOW,
            List.of(), List.of("file-sha256:" + file + ":" + "0".repeat(64)), "", "");

        SpyGateway gateway = new SpyGateway(); // 模型总说"一致"
        var deps = new AgentRuntimeDependencies(gateway, null, new ToolRegistry(), 128_000, null);
        var launcher = new VerificationRunLauncher(deps, dir.toString());

        var result = launcher.verify(attempt(descriptor));

        assertThat(result.deterministicPassed())
            .as("模型解释不能推翻确定性断言")
            .isFalse();
        assertThat(result.deterministicDetail()).contains("mismatch");
    }
}
