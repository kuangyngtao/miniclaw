package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Diagnosis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RepairPolicyGateTest {

    @Test
    void appDownPlusOrderApiShouldBeAllowed() {
        Diagnosis d = appDownDiagnosis();
        RepairSuggestion s = restartOrderApiSuggestion();

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void dbLockWaitShouldBeDenied() {
        Diagnosis d = new Diagnosis("DB_LOCK_WAIT", 0.9,
            List.of("e-1"), List.of(), List.of(), List.of(),
            "ESCALATE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, null, Diagnosis.ResolutionAttribution.NONE);
        RepairSuggestion s = restartOrderApiSuggestion();

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("DB_LOCK_WAIT");
    }

    @Test
    void nonOrderApiServiceIdShouldBeDenied() {
        Diagnosis d = appDownDiagnosis();
        RepairSuggestion s = new RepairSuggestion(
            "inc-1", "restart_service", "postgres",
            "restart postgres", 0.8, List.of());

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("postgres");
        assertThat(decision.reason()).contains("allowlist");
    }

    @Test
    void unknownActionCodeShouldBeDenied() {
        Diagnosis d = appDownDiagnosis();
        RepairSuggestion s = new RepairSuggestion(
            "inc-1", "destroy_database", "order-api",
            "nuke it", 0.9, List.of());

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("destroy_database");
        assertThat(decision.reason()).contains("restart_service");
    }

    @Test
    void inconclusiveDiagnosisShouldBeDenied() {
        Diagnosis d = new Diagnosis("APP_DOWN", 0.3,
            List.of(), List.of(), List.of(), List.of("e-1"),
            "ESCALATE", false, "1", Diagnosis.DiagnosisStatus.INCONCLUSIVE,
            Diagnosis.CurrentCondition.UNKNOWN, null, Diagnosis.ResolutionAttribution.NONE);
        RepairSuggestion s = restartOrderApiSuggestion();

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("INCONCLUSIVE");
    }

    @Test
    void cpuPressureShouldBeDenied() {
        Diagnosis d = new Diagnosis("CPU_PRESSURE", 0.9,
            List.of("e-1"), List.of(), List.of(), List.of(),
            "ESCALATE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, null, Diagnosis.ResolutionAttribution.NONE);
        RepairSuggestion s = restartOrderApiSuggestion();

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("CPU_PRESSURE");
    }

    @Test
    void nullSuggestionShouldBeDenied() {
        Diagnosis d = appDownDiagnosis();

        GateDecision decision = RepairPolicyGate.evaluate(d, null);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("null");
    }

    @Test
    void nullDiagnosisShouldBeDenied() {
        RepairSuggestion s = restartOrderApiSuggestion();

        GateDecision decision = RepairPolicyGate.evaluate(null, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("null");
    }

    @Test
    void connectionExhaustionShouldBeDenied() {
        Diagnosis d = new Diagnosis("CONNECTION_EXHAUSTION", 0.85,
            List.of("e-1"), List.of(), List.of(), List.of(),
            "ESCALATE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, null, Diagnosis.ResolutionAttribution.NONE);
        RepairSuggestion s = restartOrderApiSuggestion();

        GateDecision decision = RepairPolicyGate.evaluate(d, s);
        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).contains("CONNECTION_EXHAUSTION");
    }

    // ── helpers ──

    private static Diagnosis appDownDiagnosis() {
        return new Diagnosis("APP_DOWN", 0.95,
            List.of("e-1", "e-2"), List.of(), List.of(), List.of(),
            "RESTART_SERVICE", false, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, null, Diagnosis.ResolutionAttribution.NONE);
    }

    private static RepairSuggestion restartOrderApiSuggestion() {
        return new RepairSuggestion(
            "inc-1", "restart_service", "order-api",
            "order-api container is stopped, restart is required to restore service",
            0.9, List.of("e-1", "e-2"));
    }
}
