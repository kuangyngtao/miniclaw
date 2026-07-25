package com.clawkit.ops.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Applies only explicit taxonomy rules backed by current evidence; ambiguity stays with the model. */
final class DiagnosisReconciler {
    private static final Set<EvidenceType> DIAGNOSTIC_TYPES = Set.of(
        EvidenceType.BUSINESS_METRIC, EvidenceType.CONTAINER_RESOURCE,
        EvidenceType.LOGS, EvidenceType.DB_ACTIVITY, EvidenceType.DB_LOCK_GRAPH,
        EvidenceType.DB_CONNECTION_STATS);

    private DiagnosisReconciler() { }

    static Diagnosis reconcile(
        Diagnosis model, DiagnosticSignals signals, List<Evidence> evidence, Instant evaluatedAt
    ) {
        LinkedHashSet<String> supporting = new LinkedHashSet<>(model.supportingEvidence());
        evidence.stream()
            .filter(item -> item.rawReference().startsWith("run://baseline-"))
            .filter(item -> DIAGNOSTIC_TYPES.contains(item.type()))
            .filter(item -> item.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(item -> item.fact().path("success").asBoolean(false))
            .filter(item -> item.isCurrentAt(evaluatedAt))
            .map(Evidence::evidenceId).forEach(supporting::add);

        if ("INCONCLUSIVE".equals(signals.candidateRootCause())) {
            return copy(model, model.rootCauseCode(), model.confidence(), List.copyOf(supporting),
                model.alternatives(), model.diagnosisStatus(), model.currentCondition(),
                model.resolutionAttribution(), evaluatedAt);
        }

        boolean recoveredLock = "DB_LOCK_WAIT".equals(signals.candidateRootCause())
            && signals.completedLockTransaction() && signals.currentWindowHealthy()
            && !signals.currentDatabaseLockWait();
        Diagnosis.DiagnosisStatus status = recoveredLock
            ? Diagnosis.DiagnosisStatus.PROBABLE : Diagnosis.DiagnosisStatus.CONFIRMED;
        Diagnosis.CurrentCondition condition = recoveredLock
            ? Diagnosis.CurrentCondition.RECOVERED
            : Diagnosis.CurrentCondition.valueOf(signals.candidateCurrentCondition());
        Diagnosis.ResolutionAttribution attribution = recoveredLock
            ? Diagnosis.ResolutionAttribution.SELF_RECOVERED
            : Diagnosis.ResolutionAttribution.NONE;
        LinkedHashSet<String> alternatives = new LinkedHashSet<>(model.alternatives());
        if (!signals.candidateRootCause().equals(model.rootCauseCode())) {
            alternatives.add(model.rootCauseCode());
        }
        alternatives.remove(signals.candidateRootCause());
        if (alternatives.isEmpty()) alternatives.add("INCONCLUSIVE");
        return copy(model, signals.candidateRootCause(),
            Math.max(model.confidence(), recoveredLock ? 0.85 : 0.9),
            List.copyOf(supporting), List.copyOf(alternatives), status,
            condition, attribution, evaluatedAt);
    }

    private static Diagnosis copy(
        Diagnosis original, String root, double confidence, List<String> supporting,
        List<String> alternatives, Diagnosis.DiagnosisStatus status,
        Diagnosis.CurrentCondition condition, Diagnosis.ResolutionAttribution attribution,
        Instant evaluatedAt
    ) {
        List<String> contradicting = original.contradictingEvidence().stream()
            .filter(id -> !supporting.contains(id)).toList();
        return new Diagnosis(root, confidence, supporting,
            contradicting, alternatives,
            new ArrayList<>(original.missingEvidence()), "ESCALATE", false, "2",
            status, condition, evaluatedAt, attribution);
    }
}
