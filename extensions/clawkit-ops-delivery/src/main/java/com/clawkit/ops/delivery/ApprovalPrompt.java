package com.clawkit.ops.delivery;

import java.util.List;
import java.util.Objects;

/**
 * What the user sees before approving a repair action.
 *
 * <p>Must be in Chinese, structured for quick decision-making (30 seconds).
 * No internal hashes, attempt IDs, or Java type names.
 *
 * <p>OPS-PRODUCT-LOOP-1 §9.
 */
public record ApprovalPrompt(
    String incidentId,
    String targetId,
    String serviceId,
    String finding,
    String whyAppDown,
    String suggestedAction,
    String impact,
    String riskLevel,
    String preExecutionProtection,
    String postExecutionProtection,
    String wontDo,
    String approvalValidity,
    List<String> evidenceSummary,
    /** Secret-free action fingerprint for grant binding. */
    String actionFingerprint,
    /** Current evidence snapshot hash for TOCTOU protection. */
    String snapshotHash
) {
    public ApprovalPrompt {
        Objects.requireNonNull(incidentId, "incidentId required");
        Objects.requireNonNull(targetId, "targetId required");
        Objects.requireNonNull(serviceId, "serviceId required");
        Objects.requireNonNull(finding, "finding required");
        Objects.requireNonNull(suggestedAction, "suggestedAction required");
        Objects.requireNonNull(actionFingerprint, "actionFingerprint required");
        Objects.requireNonNull(snapshotHash, "snapshotHash required");
    }
}
