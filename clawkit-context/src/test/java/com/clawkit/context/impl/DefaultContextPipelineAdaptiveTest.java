package com.clawkit.context.impl;

import com.clawkit.context.AdaptiveCompactionPolicy;
import com.clawkit.context.AnchorKind;
import com.clawkit.context.AnchorProvenance;
import com.clawkit.context.CompactionAnchor;
import com.clawkit.context.CompactionHint;
import com.clawkit.context.CompactionLevel;
import com.clawkit.context.CompactionProfile;
import com.clawkit.context.CompactionRequest;
import com.clawkit.context.ContextBudgetAnalyzer;
import com.clawkit.context.ContextBudgetPolicy;
import com.clawkit.context.Summarizer;
import com.clawkit.tools.schema.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextPipelineAdaptiveTest {
    private final CharFallbackTokenizer tokenizer = new CharFallbackTokenizer();

    @Test
    void staysAtL0WhenContextIsBelowWarningThreshold() {
        var pipeline = pipeline(2_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        List<Message> input = List.of(Message.system("stable"), Message.user("short request"));

        var result = pipeline.compact(new CompactionRequest(input, 0, 1));

        assertThat(result.compacted()).isFalse();
        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L0_NONE);
        assertThat(result.messages()).isEqualTo(input);
    }

    @Test
    void reservesAnchorsForTheDecisionButDoesNotDuplicateThemAtL0() {
        var pipeline = pipeline(4_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        var anchor = new CompactionAnchor("incident-1", AnchorKind.INCIDENT,
            "short incident", null, true, CompactionAnchor.CONFIRMED,
            AnchorProvenance.WORKFLOW_STATE, Instant.EPOCH);

        var result = pipeline.compact(new CompactionRequest(
            List.of(Message.user("short request")), 0, 1,
            new CompactionHint(CompactionProfile.OPS_DIAGNOSIS, List.of(anchor))));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L0_NONE);
        assertThat(result.messages()).noneMatch(message -> message.content() != null
            && message.content().startsWith("[Runtime][Compaction Anchors]"));
    }

    @Test
    void usesL1ToDeduplicateRebuildableRuntimeFragments() {
        var pipeline = pipeline(1_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        String duplicate = "[Runtime] " + "x".repeat(1_200);
        List<Message> input = List.of(
            Message.system("stable"), Message.system(duplicate), Message.system(duplicate));

        var result = pipeline.compact(new CompactionRequest(input, 0, 1));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L1_DETERMINISTIC);
        assertThat(result.messages()).filteredOn(message -> duplicate.equals(message.content()))
            .hasSize(1);
        assertThat(result.afterReport().totalTokens()).isLessThan(result.beforeReport().totalTokens());
    }

    @Test
    void escalatesToL3AndReinsertsCanonicalRequiredAnchors() {
        AtomicInteger summaries = new AtomicInteger();
        List<String> summaryInputs = java.util.Collections.synchronizedList(new ArrayList<>());
        Summarizer summarizer = messages -> {
            summaries.incrementAndGet();
            summaryInputs.add(messages.stream().map(Message::content)
                .filter(java.util.Objects::nonNull).reduce("", (left, right) -> left + "\n" + right));
            return "summary deliberately omits every anchor";
        };
        var pipeline = pipeline(1_600, summarizer,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        List<Message> input = longConversation(26, 180);
        var anchor = new CompactionAnchor("fact-1", AnchorKind.CONFIRMED_FACT,
            "database lock confirmed", "evidence://run/call/slice", true,
            CompactionAnchor.CONFIRMED, AnchorProvenance.TOOL_EVIDENCE,
            Instant.parse("2026-07-22T10:00:00Z"));

        var result = pipeline.compact(new CompactionRequest(input, 0, 26,
            new CompactionHint(CompactionProfile.OPS_DIAGNOSIS, List.of(anchor))));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L3_GENERATIVE);
        assertThat(result.audit().failureCode()).isNull();
        assertThat(result.audit().retainedAnchorIds()).containsExactly("fact-1");
        assertThat(result.audit().lostRequiredAnchorIds()).isEmpty();
        assertThat(result.audit().evictedGroups()).isGreaterThan(0);
        assertThat(result.messages()).filteredOn(message -> message.content() != null
            && message.content().startsWith("[Runtime][Compaction Anchors]"))
            .singleElement().satisfies(message -> assertThat(message.content())
                .contains("id=fact-1", "database lock confirmed"));
        assertThat(summaries).hasPositiveValue();
        assertThat(summaryInputs).anyMatch(inputText -> inputText.contains("question 1 "))
            .anyMatch(inputText -> inputText.contains("question 10 "));
    }

    @Test
    void failsClosedWhenRequiredAnchorsExceedTheirBudget() {
        var pipeline = pipeline(1_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.01, 10));
        var anchor = new CompactionAnchor("required-1", AnchorKind.USER_CONSTRAINT,
            "must preserve this long and important constraint", null, true,
            CompactionAnchor.CONFIRMED, AnchorProvenance.USER, Instant.EPOCH);

        var result = pipeline.compact(new CompactionRequest(
            List.of(Message.user("x".repeat(800))), 0, 1,
            new CompactionHint(CompactionProfile.GENERAL, List.of(anchor)),
            0, 0, 200));

        assertThat(result.compacted()).isTrue();
        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L4_FAILED);
        assertThat(result.audit().failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
    }

    @Test
    void failsClosedWhenProtectedSystemContentStillExceedsHardLimit() {
        var pipeline = pipeline(400, messages -> "small summary",
            new AdaptiveCompactionPolicy(0, 64, 0.50, 200));
        List<Message> input = List.of(Message.system("S".repeat(2_000)), Message.user("hello"));

        var result = pipeline.compact(new CompactionRequest(input, 0, 1));

        assertThat(result.audit().level()).isEqualTo(CompactionLevel.L4_FAILED);
        assertThat(result.audit().failureCode()).isEqualTo("COMPACT_HARD_LIMIT");
    }

    @Test
    void deduplicatesAnchorUpdatesAndExtractsLegacyConstraintsBeforeMasking() {
        var pipeline = pipeline(1_600, messages -> "bounded summary",
            new AdaptiveCompactionPolicy(0, 64, 0.80, 1_000));
        var old = new CompactionAnchor("state-1", AnchorKind.OPEN_HYPOTHESIS,
            "old", null, false, CompactionAnchor.OPEN, AnchorProvenance.MODEL_DERIVED,
            Instant.parse("2026-07-22T09:00:00Z"));
        var latest = new CompactionAnchor("state-1", AnchorKind.OPEN_HYPOTHESIS,
            "latest", null, false, CompactionAnchor.OPEN, AnchorProvenance.MODEL_DERIVED,
            Instant.parse("2026-07-22T10:00:00Z"));

        List<Message> conversation = new ArrayList<>(longConversation(26, 80));
        conversation.set(1, Message.user("inspect /tmp/orders.log and keep A-123 "
            + "x".repeat(80)));
        var result = pipeline.compact(new CompactionRequest(
            conversation, 0, 26,
            new CompactionHint(CompactionProfile.GENERAL, List.of(old, latest))));

        String snapshot = result.messages().stream().map(Message::content)
            .filter(content -> content != null && content.startsWith("[Runtime][Compaction Anchors]"))
            .findFirst().orElseThrow();
        assertThat(snapshot).contains("id=state-1", "summary=latest", "/tmp/orders.log", "A-123")
            .doesNotContain("summary=old");
        assertThat(snapshot.split("id=state-1", -1)).hasSize(2);
    }

    @Test
    void includesReservedOutputSafetyMarginAndRunBudgetInTheDecision() {
        var pipeline = pipeline(4_000, null,
            new AdaptiveCompactionPolicy(0, 64, 0.50, 1_000));
        List<Message> input = List.of(Message.user("x".repeat(400)));

        var normal = pipeline.compact(new CompactionRequest(input, 0, 1));
        var budgetConstrained = pipeline.compact(new CompactionRequest(
            input, 0, 1, CompactionHint.GENERAL, 50, 20, 200));

        assertThat(normal.audit().level()).isEqualTo(CompactionLevel.L0_NONE);
        assertThat(budgetConstrained.audit().level()).isEqualTo(CompactionLevel.L2_EXTRACTIVE);
        assertThat(budgetConstrained.audit().decisionReason()).isEqualTo("above-compact-threshold");
    }

    private DefaultContextPipeline pipeline(int contextWindow, Summarizer summarizer,
                                            AdaptiveCompactionPolicy adaptivePolicy) {
        var budget = new ContextBudgetPolicy(contextWindow, 0.50, 0.70, 0.95, 0.40);
        var analyzer = new ContextBudgetAnalyzer(tokenizer, budget);
        var compactor = new LadderedCompactor(summarizer, tokenizer);
        return new DefaultContextPipeline(compactor, analyzer, tokenizer, budget, adaptivePolicy);
    }

    private List<Message> longConversation(int turns, int contentSize) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("stable system prompt"));
        for (int turn = 1; turn <= turns; turn++) {
            messages.add(Message.user("question " + turn + " " + "u".repeat(contentSize)));
            messages.add(Message.assistant("answer " + turn + " " + "a".repeat(contentSize)));
            messages.add(Message.toolResult("call-" + turn, "tool " + "t".repeat(contentSize)));
        }
        return messages;
    }
}
