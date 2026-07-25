package com.clawkit.ops.loop;

import com.clawkit.context.AnchorKind;
import com.clawkit.context.AnchorProvenance;
import com.clawkit.context.CompactionAnchor;
import com.clawkit.context.CompactionHint;
import com.clawkit.context.CompactionHintProvider;
import com.clawkit.context.CompactionProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Maps the read-only incident state into bounded task-aware compaction anchors. */
final class OpsCompactionHintProvider implements CompactionHintProvider {
    private final IncidentInput incident;
    private final DiagnosticSignals signals;
    private final Supplier<List<Evidence>> evidenceSnapshot;

    OpsCompactionHintProvider(IncidentInput incident, DiagnosticSignals signals,
                              Supplier<List<Evidence>> evidenceSnapshot) {
        this.incident = java.util.Objects.requireNonNull(incident, "incident required");
        this.signals = java.util.Objects.requireNonNull(signals, "signals required");
        this.evidenceSnapshot = java.util.Objects.requireNonNull(
            evidenceSnapshot, "evidenceSnapshot required");
    }

    @Override
    public CompactionHint snapshot(String runId, int turn) {
        List<Evidence> evidence = List.copyOf(evidenceSnapshot.get());
        Set<String> relevant = Set.copyOf(signals.relevantEvidence());
        List<CompactionAnchor> anchors = new ArrayList<>();
        anchors.add(new CompactionAnchor("incident-" + safeId(incident.incidentId()),
            AnchorKind.INCIDENT,
            bounded("incident=" + incident.incidentId() + "; detectedAt=" + incident.detectedAt()
                + "; symptom=" + incident.symptom() + "; capability=" + incident.capabilityProfile()),
            null, true, CompactionAnchor.CONFIRMED, AnchorProvenance.WORKFLOW_STATE,
            incident.detectedAt()));

        anchors.add(new CompactionAnchor("hypothesis-root-cause",
            AnchorKind.OPEN_HYPOTHESIS,
            bounded("candidateRootCause=" + signals.candidateRootCause()), null, false,
            CompactionAnchor.OPEN, AnchorProvenance.WORKFLOW_STATE, incident.detectedAt()));

        for (Evidence item : evidence) {
            boolean required = relevant.contains(item.evidenceId());
            String state = item.collectionStatus() == Evidence.CollectionStatus.COLLECTION_FAILED
                ? CompactionAnchor.OPEN : CompactionAnchor.CONFIRMED;
            anchors.add(new CompactionAnchor("evidence-" + safeId(item.evidenceId()),
                AnchorKind.EVIDENCE,
                bounded("type=" + item.type() + "; scope=" + item.scope()
                    + "; freshness=" + item.freshness() + "; fact=" + item.fact()),
                item.rawReference(), required, state, AnchorProvenance.TOOL_EVIDENCE,
                item.observedAt()));
        }
        return new CompactionHint(CompactionProfile.OPS_DIAGNOSIS, anchors);
    }

    private String bounded(String value) {
        String normalized = value == null ? "" : value.codePoints()
            .map(codePoint -> Character.isISOControl(codePoint) ? ' ' : codePoint)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
        if (normalized.codePointCount(0, normalized.length()) <= 512) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, 511)) + "…";
    }

    private String safeId(String value) {
        if (value != null && value.matches("[a-zA-Z0-9._-]{1,48}")) return value;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
