package com.clawkit.ops.loop.repair;

import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import com.clawkit.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ApprovalGrantContractTest {

    @Test
    void validGrantShouldPassValidation() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "operator", Duration.ofMinutes(5));

        var result = grant.validate(desc, "snap1", "inc-1", "target:test");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void expiredGrantShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        Instant expiredAt = past.plus(Duration.ofMinutes(5));
        ApprovalGrant grant = new ApprovalGrant(
            "g-1", "inc-1", "target:test", desc.fingerprint(),
            "snap1", "operator", past, expiredAt);

        assertThat(grant.isExpired()).isTrue();
        var result0 = grant.validate(desc, "snap1");
        assertThat(result0.valid()).isFalse();
    }

    @Test
    void fingerprintMismatchShouldBeRejected() {
        ActionDescriptor desc1 = buildDescriptor("target:test", "order-api", "hash1");
        ActionDescriptor desc2 = buildDescriptor("target:test", "order-api", "hash2");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc1, "snap1", "operator", Duration.ofMinutes(5));

        var result = grant.validate(desc2, "snap1", "inc-1", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("fingerprint");
    }

    @Test
    void snapshotHashMismatchShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "operator", Duration.ofMinutes(5));

        var result = grant.validate(desc, "snap2", "inc-1", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("TOCTOU");
    }

    @Test
    void crossIncidentReplayShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "operator", Duration.ofMinutes(5));

        // Try to use grant for a different incident
        var result = grant.validate(desc, "snap1", "inc-2", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("incidentId");
        assertThat(result.reason()).contains("inc-2");
    }

    @Test
    void crossTargetReplayShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:A", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:A", desc, "snap1", "operator", Duration.ofMinutes(5));

        var result = grant.validate(desc, "snap1", "inc-1", "target:B");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("canonicalTarget");
    }

    @Test
    void nullTtlShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        assertThatThrownBy(() -> ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "op", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroOrNegativeTtlShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        assertThatThrownBy(() -> ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "op", Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void nullDescriptorShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "op", Duration.ofMinutes(5));

        var result = grant.validate(null, "snap1", "inc-1", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("null");
    }

    @Test
    void blankSnapshotHashShouldBeRejected() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "op", Duration.ofMinutes(5));

        var result = grant.validate(desc, "", "inc-1", "target:test");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("blank");
    }

    @Test
    void defaultTtlShouldBeFiveMinutes() {
        ActionDescriptor desc = buildDescriptor("target:test", "order-api", "hash1");
        ApprovalGrant grant = ApprovalGrant.create(
            "inc-1", "target:test", desc, "snap1", "operator",
            ApprovalGrant.DEFAULT_TTL);

        Duration duration = Duration.between(grant.approvedAt(), grant.expiresAt());
        assertThat(duration).isEqualTo(ApprovalGrant.DEFAULT_TTL);
    }

    // ── helpers ──

    private static ActionDescriptor buildDescriptor(String target, String serviceId,
                                                      String snapshot) {
        return new ActionDescriptor(
            "restart_service", target,
            "serviceId=" + serviceId + " snapshot=" + snapshot,
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.none(), VerificationMode.WORKFLOW,
            List.of("service_status reports order-api as down"),
            List.of("order-api container is running"),
            "compensation", "order-api only");
    }
}
