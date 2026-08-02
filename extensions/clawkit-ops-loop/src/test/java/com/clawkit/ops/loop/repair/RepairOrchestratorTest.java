package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.DiscoveryStatus;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.reliability.attempt.ActionAttemptCoordinator;
import com.clawkit.reliability.attempt.FileActionAttemptStore;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.VerificationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RepairOrchestratorTest {

    @TempDir Path tempDir;
    private FileActionAttemptStore store;
    private RepairOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        store = new FileActionAttemptStore(tempDir.resolve("attempts"));
        orchestrator = new RepairOrchestrator(store, java.time.Clock.systemUTC());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldSuggestRestartForAppDown() {
        Diagnosis d = appDownDiagnosis();
        DiscoveryResult discovery = mockDiscovery("stopped");

        RepairSuggestion s = orchestrator.generateSuggestion(d, discovery);

        assertThat(s.actionCode()).isEqualTo("restart_service");
        assertThat(s.serviceId()).isEqualTo("order-api");
        assertThat(s.isApplicable(d)).isTrue();
    }

    @Test
    void shouldNotSuggestRestartForDbLockWait() {
        Diagnosis d = new Diagnosis("DB_LOCK_WAIT", 0.9,
            List.of("e-1"), List.of(), List.of(), List.of(), "ESCALATE", false);
        DiscoveryResult discovery = mockDiscovery("stopped");

        RepairSuggestion s = orchestrator.generateSuggestion(d, discovery);
        assertThat(s.isApplicable(d)).isFalse();
        assertThat(s.justification()).contains("Not applicable");
    }

    @Test
    void policyGateShouldRejectDbLockWait() {
        Diagnosis d = new Diagnosis("DB_LOCK_WAIT", 0.9,
            List.of("e-1"), List.of(), List.of(), List.of(), "ESCALATE", false);
        RepairSuggestion s = new RepairSuggestion(
            "inc-1", "restart_service", "order-api", "restart", 0.8, List.of());

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
    }

    @Test
    void policyGateShouldRejectNonOrderApiService() {
        Diagnosis d = appDownDiagnosis();
        RepairSuggestion s = new RepairSuggestion(
            "inc-1", "restart_service", "postgres", "restart postgres", 0.8, List.of());

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("postgres");
    }

    @Test
    void expiredApprovalGrantShouldBeInvalid() {
        ActionDescriptor desc = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        var past = Instant.now().minus(Duration.ofMinutes(10));
        var expiredAt = past.plus(Duration.ofMinutes(5));
        ApprovalGrant grant = new ApprovalGrant(
            "g-1", "inc-1", "target:test", desc.fingerprint(),
            "snap1", "operator", past, expiredAt);

        assertThat(grant.isExpired()).isTrue();
    }

    @Test
    void toctouSnapshotDriftShouldInvalidateGrant() {
        ActionDescriptor desc = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "operator", Duration.ofMinutes(5));

        var result = grant.validate(desc, "snap2", "inc-1", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("TOCTOU");
    }

    @Test
    void crossIncidentReplayShouldBeRejected() {
        ActionDescriptor desc = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "operator", Duration.ofMinutes(5));

        var result = grant.validate(desc, "snap1", "inc-2", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("incidentId");
    }

    @Test
    void attemptIdShouldBeStableForSameFingerprint() {
        ActionDescriptor d1 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        ActionDescriptor d2 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");

        assertThat(d1.contentDerivedActionId()).isEqualTo(d2.contentDerivedActionId());
    }

    @Test
    void attemptIdShouldChangeForDifferentParameters() {
        ActionDescriptor d1 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        ActionDescriptor d2 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash2");

        assertThat(d1.contentDerivedActionId()).isNotEqualTo(d2.contentDerivedActionId());
    }

    @Test
    void targetMutexShouldPreventConcurrentRepairs() {
        ActionDescriptor desc = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        String actionId = desc.contentDerivedActionId();

        var ticket1 = orchestrator.coordinator().begin(desc, actionId, "run-1", true);
        assertThat(ticket1).isNotNull();

        var ticket2 = orchestrator.coordinator().begin(desc, actionId, "run-2", true);
        assertThat(ticket2.attemptId()).isEqualTo(ticket1.attemptId());
    }

    @Test
    void verificationModeShouldBeWorkflow() {
        ActionDescriptor d = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        assertThat(d.verificationMode()).isEqualTo(VerificationMode.WORKFLOW);
    }

    @Test
    void nonOrderApiServiceShouldFailDescriptorCreation() {
        assertThatThrownBy(() ->
            RepairAction.RESTART_SERVICE.toActionDescriptor("target:test", "postgres", "hash1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("postgres");
    }

    // ── helpers ──

    private Diagnosis appDownDiagnosis() {
        return new Diagnosis("APP_DOWN", 0.95,
            List.of("e-1", "e-2"), List.of(), List.of(), List.of(),
            "RESTART_SERVICE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, null, Diagnosis.ResolutionAttribution.NONE);
    }

    private DiscoveryResult mockDiscovery(String serviceState) {
        ObjectNode node = MAPPER.createObjectNode()
            .put("State", serviceState).put("success", !serviceState.contains("stop"));
        var e = new Evidence("e-1", "inc-1", EvidenceType.SERVICE_STATUS,
            "mcp:ops/service_status", Instant.now(), Instant.now(),
            "compose/order-api", Evidence.Kind.FACT,
            node, "run://run-1/e-1", Evidence.Freshness.CURRENT,
            Evidence.Redaction.NONE, "2",
            Evidence.CollectionStatus.OBSERVED,
            Instant.now().plusSeconds(300), null);
        return new DiscoveryResult("inc-1", "run-1", "APP_DOWN_V1",
            new EvidenceBundle("inc-1", "run-1", Instant.now(), List.of(e)),
            DiscoveryStatus.COMPLETE, 6, 8, Instant.now());
    }
}
