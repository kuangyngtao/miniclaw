package com.clawkit.evaluation;

import com.clawkit.provider.LLMException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.clawkit.tools.schema.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptedProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<ToolDefinition> EMPTY_TOOLS = List.of();
    private static final List<ToolDefinition> READ_TOOLS = List.of(
        new ToolDefinition("read", "Read a file", MAPPER.createObjectNode()));

    @Test
    void shouldConsumeStepsSequentially() {
        var provider = ScriptedProvider.builder()
            .text("Hello")
            .text("World")
            .build();

        var msg1 = provider.generate(List.of(), EMPTY_TOOLS);
        assertThat(msg1.content()).isEqualTo("Hello");

        var msg2 = provider.generate(List.of(), EMPTY_TOOLS);
        assertThat(msg2.content()).isEqualTo("World");

        assertThat(provider.allStepsConsumed()).isTrue();
        assertThat(provider.consumedSteps()).isEqualTo(2);
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void shouldThrowScriptExhaustedWhenStepsConsumed() {
        var provider = ScriptedProvider.builder()
            .text("only one")
            .build();

        provider.generate(List.of(), EMPTY_TOOLS);

        assertThatThrownBy(() -> provider.generate(List.of(), EMPTY_TOOLS))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("SCRIPT_EXHAUSTED");
    }

    @Test
    void shouldThrowErrorStep() {
        var provider = ScriptedProvider.builder()
            .error(new LLMException("custom error"))
            .build();

        assertThatThrownBy(() -> provider.generate(List.of(), EMPTY_TOOLS))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("custom error");
    }

    @Test
    void shouldReturnToolCallMessage() {
        var args = MAPPER.createObjectNode().put("path", "test.txt");
        var call = new ToolCall("call_1", "read", args);

        var provider = ScriptedProvider.builder()
            .toolCall(Message.assistantWithTools(List.of(call)))
            .build();

        var msg = provider.generate(List.of(), READ_TOOLS);
        assertThat(msg.toolCalls()).hasSize(1);
        assertThat(msg.toolCalls().getFirst().name()).isEqualTo("read");
    }

    @Test
    void shouldValidatePhase() {
        // Step expects phase1 (no tools) but we pass tools → phase2
        var step = ScriptedStep.text("phase1", null, false, null,
            "should be phase1");
        var provider = new ScriptedProvider(List.of(step));

        assertThatThrownBy(() -> provider.generate(List.of(), READ_TOOLS))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("SCRIPT_PHASE_MISMATCH");
    }

    @Test
    void shouldValidateStreaming() {
        var step = ScriptedStep.text("phase2", null, true, null,
            "should be streaming");
        var provider = new ScriptedProvider(List.of(step));

        assertThatThrownBy(() -> provider.generate(List.of(), EMPTY_TOOLS))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("SCRIPT_PHASE_MISMATCH");
    }

    @Test
    void shouldValidateAvailableTools() {
        var step = ScriptedStep.text("phase2", null, false,
            Set.of("read", "bash"),
            "expecting read+bash");
        var provider = new ScriptedProvider(List.of(step));

        // Only "read" available, but step expects "read"+"bash"
        assertThatThrownBy(() -> provider.generate(List.of(), READ_TOOLS))
            .isInstanceOf(LLMException.class)
            .hasMessageContaining("SCRIPT_TOOLS_MISMATCH");
    }

    @Test
    void shouldPassWhenToolsMatch() {
        var step = ScriptedStep.text("phase2", null, false,
            Set.of("read"),
            "expecting read only");
        var provider = new ScriptedProvider(List.of(step));

        var msg = provider.generate(List.of(), READ_TOOLS);
        assertThat(msg.content()).isEqualTo("expecting read only");
    }

    @Test
    void shouldNotValidateToolsWhenNull() {
        var step = ScriptedStep.text("phase2", null, false, null,
            "no tool validation");
        var provider = new ScriptedProvider(List.of(step));

        var msg = provider.generate(List.of(), READ_TOOLS);
        assertThat(msg.content()).isEqualTo("no tool validation");
    }

    @Test
    void shouldTrackCallCountAcrossGenerateAndStream() {
        var provider = new ScriptedProvider(List.of(
            ScriptedStep.text(null, null, false, null, "one"),
            ScriptedStep.text(null, null, true, null, "two")
        ));

        provider.generate(List.of(), EMPTY_TOOLS);
        provider.generateStream(List.of(), EMPTY_TOOLS, t -> {});

        assertThat(provider.callCount()).isEqualTo(2);
        assertThat(provider.allStepsConsumed()).isTrue();
    }

    @Test
    void shouldReportDiagnostics() {
        var provider = ScriptedProvider.builder()
            .text("a")
            .text("b")
            .text("c")
            .build();

        assertThat(provider.totalSteps()).isEqualTo(3);
        assertThat(provider.consumedSteps()).isEqualTo(0);
        assertThat(provider.isExhausted()).isFalse();
        assertThat(provider.allStepsConsumed()).isFalse();

        provider.generate(List.of(), EMPTY_TOOLS);
        assertThat(provider.consumedSteps()).isEqualTo(1);
        assertThat(provider.isExhausted()).isFalse();

        provider.generate(List.of(), EMPTY_TOOLS);
        provider.generate(List.of(), EMPTY_TOOLS);
        assertThat(provider.allStepsConsumed()).isTrue();
        assertThat(provider.isExhausted()).isTrue();
    }
}
