package com.clawkit.engine.impl;

import com.clawkit.engine.ExecutionMode;
import com.clawkit.engine.RunPhase;
import com.clawkit.engine.RunScope;
import com.clawkit.observability.ProviderCallCompletedPayload;
import com.clawkit.observability.ProviderCallStartedPayload;
import com.clawkit.observability.RunEventPayload;
import com.clawkit.provider.FinishReason;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.ProviderDescriptor;
import com.clawkit.provider.ProviderDialect;
import com.clawkit.provider.ProviderResponseMetadata;
import com.clawkit.provider.TokenUsage;
import com.clawkit.provider.UsageSource;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservingProviderGatewayP2Test {

    @Test
    void recordsUniqueIdsFingerprintAndActualUsage() {
        List<RunEventPayload> events = new ArrayList<>();
        LLMProvider provider = new LLMProvider() {
            @Override
            public Message generate(List<Message> messages, List<ToolDefinition> tools) {
                return Message.assistant("done");
            }

            @Override
            public ModelResponse generate(ModelRequest request) {
                return new ModelResponse("done", List.of(), FinishReason.STOP,
                    new TokenUsage(100, 20, 120, 70, 30, 12, UsageSource.ACTUAL),
                    new ProviderResponseMetadata("deepseek-v4-flash", "resp-1", 0));
            }

            @Override
            public ProviderDescriptor descriptor() {
                return new ProviderDescriptor(ProviderDialect.DEEPSEEK_V4,
                    "deepseek-v4-flash");
            }
        };
        var gateway = new ObservingProviderGateway(provider,
            (payload, runId, parentRunId, turn, at) -> events.add(payload));
        var request = ModelRequest.of(List.of(Message.system("stable"), Message.user("run")),
            List.of());
        var scope = new RunScope("run-1", null, 1, RunPhase.REACT,
            ExecutionMode.REACT, ExecutionControl.none());

        gateway.generate(request, scope);
        gateway.generate(request, scope);

        var started = events.stream().filter(ProviderCallStartedPayload.class::isInstance)
            .map(ProviderCallStartedPayload.class::cast).toList();
        var completed = events.stream().filter(ProviderCallCompletedPayload.class::isInstance)
            .map(ProviderCallCompletedPayload.class::cast).toList();
        assertThat(started).hasSize(2);
        assertThat(started.get(0).providerCallId()).isNotEqualTo(started.get(1).providerCallId());
        assertThat(started.get(0).promptFingerprint()).hasSize(64);
        assertThat(started.get(0).requestedModel()).isEqualTo("deepseek-v4-flash");
        assertThat(started.get(0).providerDialect()).isEqualTo("DEEPSEEK_V4");
        assertThat(completed.get(0).usageSource()).isEqualTo("ACTUAL");
        assertThat(completed.get(0).tokensEstimated()).isFalse();
        assertThat(completed.get(0).promptCacheHitTokens()).isEqualTo(70);
        assertThat(completed.get(0).reasoningTokens()).isEqualTo(12);
    }
}
