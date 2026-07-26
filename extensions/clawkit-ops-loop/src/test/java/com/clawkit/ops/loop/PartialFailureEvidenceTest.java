package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-0 security guardrail tests for partial failure during evidence
 * collection.
 *
 * <p>These tests define the contract for PR-4 (Discovery Profile and partial
 * failure semantics). Currently {@code @Disabled} because
 * {@link McpEvidenceCollector#collect(String, String)} is hardcoded and
 * throws on the first failure — it does not yet produce per-tool
 * {@code COLLECTION_FAILED} evidence or {@code NOT_COLLECTED_TRANSPORT_LOST}
 * entries.
 *
 * <p>Tests marked without {@code @Disabled} verify invariants of the
 * existing {@link Evidence} and {@link EvidenceBundle} types that are
 * already enforced.
 */
class PartialFailureEvidenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"),
        java.time.ZoneOffset.UTC);

    // ── Evidence contract tests (verify now against existing types) ──

    @Test
    void evidenceCollectionStatusDefaultsToObservedWhenSuccessful() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);
        fact.put("data", "container is running");

        Evidence e = new Evidence(
            "e-1", "inc-1", EvidenceType.CONTAINER_STATUS,
            "mcp:ops/container_status",
            CLOCK.instant(), CLOCK.instant(), "container/gateway",
            Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);

        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.OBSERVED);
        assertThat(e.freshness()).isEqualTo(Evidence.Freshness.CURRENT);
    }

    @Test
    void evidenceCollectionStatusDefaultsToCollectionFailedWhenNotSuccessful() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", false);
        fact.put("errorCode", "COMMAND_TIMEOUT");

        Evidence e = new Evidence(
            "e-2", "inc-1", EvidenceType.CONTAINER_STATUS,
            "mcp:ops/container_status",
            CLOCK.instant(), CLOCK.instant(), "container/gateway",
            Evidence.Kind.FACT, fact,
            "run://r1/tool/tc2",
            Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);

        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.COLLECTION_FAILED);
    }

    @Test
    void evidenceExplicitCollectionStatusOverridesDefault() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", false);

        Evidence e = new Evidence(
            "e-3", "inc-1", EvidenceType.LOGS,
            "mcp:ops/logs",
            CLOCK.instant(), CLOCK.instant(), "container/gateway",
            Evidence.Kind.FACT, fact,
            "run://r1/tool/tc3",
            Evidence.Freshness.STALE, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.COLLECTION_FAILED, null, null);

        assertThat(e.collectionStatus()).isEqualTo(Evidence.CollectionStatus.COLLECTION_FAILED);
        assertThat(e.schemaVersion()).isEqualTo("2");
    }

    @Test
    void evidenceBundleRequiresNonEmptyEvidenceList() {
        assertThatThrownBy(() -> new EvidenceBundle("inc-1", "r1",
            CLOCK.instant(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void evidenceBundleRequiresAllEvidenceBelongToSameIncident() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);

        Evidence e1 = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", CLOCK.instant(), CLOCK.instant(),
            "compose/gateway", Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);
        Evidence e2 = new Evidence("e-2", "inc-2", // different incident!
            EvidenceType.SERVICE_STATUS, "mcp:ops/service_status",
            CLOCK.instant(), CLOCK.instant(), "compose/demo-api",
            Evidence.Kind.FACT, fact,
            "run://r1/tool/tc2", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);

        assertThatThrownBy(() -> new EvidenceBundle("inc-1", "r1",
            CLOCK.instant(), List.of(e1, e2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("belong to the incident");
    }

    @Test
    void evidenceBundleIsImmutable() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);

        Evidence e = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", CLOCK.instant(), CLOCK.instant(),
            "compose/gateway", Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE);

        EvidenceBundle bundle = new EvidenceBundle("inc-1", "r1",
            CLOCK.instant(), List.of(e));

        assertThatThrownBy(() -> bundle.evidence().add(e))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Partial failure contract tests (skeletons for PR-4) ──

    @Test
    @Disabled("PR-4: McpEvidenceCollector currently throws on first failure")
    void successfulEvidenceMustBePreservedWhenLaterToolFails() {
        // Design doc §8.4: "每个 operation 无论成功或失败都生成 Evidence"
        //
        // When tool 1-3 succeed and tool 4 fails, the EvidenceBundle must
        // contain all 4 evidence records — 3 OBSERVED + 1 COLLECTION_FAILED.
        // The bundle must NOT be empty and must NOT throw away the first
        // three successful collections.
        //
        // Test approach (PR-4):
        // 1. Set up discovery profile with 4 evidence specs
        // 2. Tools 1-3 succeed, tool 4 throws
        // 3. Verify bundle.evidence() has size 4
        // 4. Verify e1-e3 have collectionStatus=OBSERVED
        // 5. Verify e4 has collectionStatus=COLLECTION_FAILED
    }

    @Test
    @Disabled("PR-4: transport disconnect handling not yet implemented")
    void transportDisconnectMustProduceNotCollectedForRemainingEvidence() {
        // Design doc §8.4: "transport 断开后，尚未执行项生成
        // NOT_COLLECTED_TRANSPORT_LOST，不尝试用旧 session 继续"
        //
        // When transport disconnects after collecting 3 of 7 evidence items,
        // the remaining 4 must be recorded as NOT_COLLECTED_TRANSPORT_LOST.
        // The collector must NOT try to reconnect or use the dead session.
        //
        // Test approach (PR-4):
        // 1. Set up fake transport that dies after 3 requests
        // 2. Profile has 7 evidence specs
        // 3. Verify 7 evidence records in bundle
        // 4. Verify items 1-3 are OBSERVED
        // 5. Verify items 4-7 have collectionStatus=COLLECTION_FAILED
        // 6. Verify items 4-7 error contains "TRANSPORT_LOST"
    }

    @Test
    @Disabled("PR-4: discovery profile not yet implemented")
    void completenessGateMustBlockDiagnosisWhenRequiredEvidenceMissing() {
        // Design doc §8.4: "required Evidence 失败时，Completeness Gate
        // 返回缺失列表"
        //
        // If the profile says service_status is REQUIRED and it failed,
        // the gate must prevent the diagnosis step. DeepSeek must NOT be
        // called with incomplete evidence.
        //
        // Test approach (PR-4):
        // 1. Profile with REQUIRED service_status and OPTIONAL logs
        // 2. service_status fails, logs succeeds
        // 3. CompletenessGate.evaluate() returns missing=[service_status]
        // 4. Coordinator must skip diagnosis and go to INCONCLUSIVE
    }

    @Test
    @Disabled("PR-4: discovery profile not yet implemented")
    void optionalEvidenceFailureMustNotBlockDiagnosis() {
        // Design doc §8.4: optional evidence failure is recorded but
        // doesn't block the gate.
        //
        // Test approach (PR-4):
        // 1. Profile with REQUIRED container_status and OPTIONAL logs
        // 2. container_status succeeds, logs fails
        // 3. CompletenessGate.evaluate() returns passable=true
        // 4. Missing list contains logs but doesn't block
    }

    @Test
    @Disabled("PR-4: external HTTP failure is separate error category")
    void externalHttpProbeFailureIsBusinessEvidenceNotTransportError() {
        // Design doc §8.4: "外部 HTTP 失败是业务证据，不等同于 SSH 失败"
        //
        // An external HTTP probe failure (e.g. timeout to the app's
        // health endpoint) is evidence about the application — not about
        // the SSH transport. It must be recorded as COLLECTION_FAILED
        // business evidence, not as a transport-level error.
        //
        // Test approach (PR-4):
        // 1. All remote tools succeed
        // 2. Local external http_probe times out
        // 3. Verify bundle contains OBSERVED evidence for remote tools
        // 4. Verify bundle contains COLLECTION_FAILED for http_probe
        // 5. Verify http_probe failure is NOT classified as SSH error
    }

    @Test
    @Disabled("PR-4: discovery profile not yet implemented")
    void evidenceIdsArePredeterminedBeforeCollection() {
        // Design doc §8.5: "Evidence ID 在执行前按 Profile 顺序分配，
        // 避免并发或失败改变 ID"
        //
        // Evidence IDs must be allocated before execution starts, so that
        // a concurrent or failed collection doesn't create gaps or change
        // the numbering. The first evidence spec in the profile always
        // produces e-1, regardless of success or failure.
        //
        // Test approach (PR-4):
        // 1. Profile with 4 evidence specs
        // 2. Spec 2 fails
        // 3. Verify e-1, e-2, e-3, e-4 all exist
        // 4. Verify e-2 exists even though collection failed
        // 5. Verify no e-5 (no gaps from retry)
    }

    @Test
    @Disabled("PR-4: bundle freezing not yet implemented")
    void evidenceBundleMustBeFrozenAfterCollection() {
        // Design doc §8.5: "Bundle 冻结后不可追加；补采必须生成新
        // discoveryId 和新 Bundle"
        //
        // Once the bundle is frozen (after collection completes or fails),
        // no new evidence can be added. Any re-collection must create a
        // new discoveryId and new bundle.
        //
        // Test approach (PR-4):
        // 1. Complete collection → freeze bundle
        // 2. Attempt to append evidence → IllegalStateException
        // 3. New collection with same Incident → new discoveryId, new bundle
    }

    // ── Evidence validity / freshness guardrails (test now) ──

    @Test
    void evidenceRejectsCollectedAtBeforeObservedAt() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);

        Instant observed = CLOCK.instant();
        Instant collected = observed.minusSeconds(1); // before observed

        assertThatThrownBy(() -> new Evidence(
            "e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", observed, collected,
            "compose/gateway", Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("collectedAt must not precede observedAt");
    }

    @Test
    void evidenceRejectsValidUntilBeforeObservedAt() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);

        Instant observed = CLOCK.instant();
        Instant validUntil = observed.minusSeconds(1); // before observed

        assertThatThrownBy(() -> new Evidence(
            "e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", observed, observed,
            "compose/gateway", Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED, validUntil, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("validUntil must not precede observedAt");
    }

    @Test
    void evidenceIsCurrentAtChecksFreshnessNotJustValidityWindow() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);

        Instant observed = CLOCK.instant();
        // Historical evidence — even within validity — is not "current"
        Evidence historical = new Evidence(
            "e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", observed, observed,
            "compose/gateway", Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1", Evidence.Freshness.HISTORICAL, Evidence.Redaction.NONE);

        // Checked 1 second after observation; within any validity window
        // but historical freshness → not current
        assertThat(historical.isCurrentAt(observed.plusSeconds(1))).isFalse();
    }

    @Test
    void evidenceIsCurrentAtReturnsFalseAfterValidUntil() {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", true);

        Instant observed = CLOCK.instant();
        Instant validUntil = observed.plusSeconds(60);

        Evidence e = new Evidence(
            "e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", observed, observed,
            "compose/gateway", Evidence.Kind.FACT, fact,
            "run://r1/tool/tc1", Evidence.Freshness.CURRENT, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.OBSERVED, validUntil, null);

        // Within validity window
        assertThat(e.isCurrentAt(observed.plusSeconds(30))).isTrue();
        // After validity window
        assertThat(e.isCurrentAt(observed.plusSeconds(61))).isFalse();
    }
}
