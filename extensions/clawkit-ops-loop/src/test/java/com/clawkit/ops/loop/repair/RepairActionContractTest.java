package com.clawkit.ops.loop.repair;

import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RepairActionContractTest {

    @Test
    void shouldBuildValidRestartServiceDescriptor() {
        ActionDescriptor d = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:122.51.51.118", "order-api", "abc123");

        assertThat(d.actionCode()).isEqualTo("restart_service");
        assertThat(d.canonicalTarget()).isEqualTo("target:122.51.51.118");
        assertThat(d.parameterDigest()).contains("order-api");
        assertThat(d.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(d.reversibility()).isEqualTo(Reversibility.COMPENSATABLE);
        assertThat(d.verificationMode()).isEqualTo(VerificationMode.WORKFLOW);
        assertThat(d.preconditions()).isNotEmpty();
        assertThat(d.expectedEffects()).isNotEmpty();
        assertThat(d.compensationSummary()).isNotEmpty();
    }

    @Test
    void shouldRejectNonOrderApiServiceId() {
        assertThatThrownBy(() ->
            RepairAction.RESTART_SERVICE.toActionDescriptor(
                "target:test", "postgres", "abc123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("postgres")
            .hasMessageContaining("not in allowed set");
    }

    @Test
    void shouldPreserveActionCodeForGateValidation() {
        RepairAction custom = new RepairAction(
            "unknown_action", java.util.Set.of("order-api"), 1,
            java.util.List.of("pre"), java.util.List.of("eff"), "", "");
        assertThat(custom.actionCode()).isEqualTo("unknown_action");
        assertThat(custom.actionCode())
            .isNotEqualTo(RepairAction.RESTART_SERVICE.actionCode());
    }

    @Test
    void shouldDetectFingerprintChangesWithDifferentSnapshot() {
        ActionDescriptor d1 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        ActionDescriptor d2 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash2");
        assertThat(d1.fingerprint()).isNotEqualTo(d2.fingerprint());
    }

    @Test
    void shouldDetectFingerprintChangesWithDifferentTarget() {
        ActionDescriptor d1 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:A", "order-api", "hash1");
        ActionDescriptor d2 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:B", "order-api", "hash1");
        assertThat(d1.fingerprint()).isNotEqualTo(d2.fingerprint());
    }

    @Test
    void fingerprintShouldBeDeterministic() {
        ActionDescriptor d1 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        ActionDescriptor d2 = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        assertThat(d1.fingerprint()).isEqualTo(d2.fingerprint());
    }

    @Test
    void actionShouldUseWorkflowNotManualVerification() {
        ActionDescriptor d = RepairAction.RESTART_SERVICE
            .toActionDescriptor("target:test", "order-api", "hash1");
        // WORKFLOW allows the IndependentVerifier to auto-verify.
        // MANUAL_REQUIRED would block automatic VERIFIED_SUCCESS.
        assertThat(d.verificationMode()).isEqualTo(VerificationMode.WORKFLOW);
    }
}
