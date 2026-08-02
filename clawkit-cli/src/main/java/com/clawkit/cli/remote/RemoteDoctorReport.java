package com.clawkit.cli.remote;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Structured doctor report and supporting types. Design: PRODUCT-1 §9. */
public record RemoteDoctorReport(
    String targetId,
    DoctorOverallStatus status,
    List<DoctorCheck> checks,
    Instant startedAt,
    Instant completedAt
) {
    public enum DoctorOverallStatus { READY, DEGRADED, FAILED }

    public record DoctorCheck(
        DoctorStage stage,
        DoctorCheckStatus status,
        String code,
        String summary,
        String nextAction,
        Map<String, String> safeDetails,
        Duration duration
    ) {}

    public enum DoctorStage {
        OPENSSH, SSH_CONFIG, SSH_SAFETY, HOST_IDENTITY,
        SSH_AGENT, SSH_TRANSPORT, REMOTE_MCP, ATTESTATION, CLEANUP
    }

    public enum DoctorCheckStatus { PASS, WARN, FAIL, SKIPPED }
}
