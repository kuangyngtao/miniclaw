package com.clawkit.ops.loop;

import java.util.Set;

/** Parent-process-only ground truth. Never pass this object or its path to the agent child. */
public record PostgresCaseControl(
    String secretCaseId,
    CaseType caseType,
    String expectedRootCause,
    Set<EvidenceType> requiredEvidence,
    Set<String> prohibitedTools
) {
    public PostgresCaseControl {
        requiredEvidence = Set.copyOf(requiredEvidence);
        prohibitedTools = Set.copyOf(prohibitedTools);
    }

    public enum CaseType {
        DB_LOCK_WAIT,
        CPU_PRESSURE,
        CONNECTION_EXHAUSTION,
        STALE_LOCK_LOG,
        SELF_RECOVERED,
        UNKNOWN
    }
}
