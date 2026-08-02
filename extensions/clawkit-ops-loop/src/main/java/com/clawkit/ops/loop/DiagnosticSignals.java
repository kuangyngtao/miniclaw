package com.clawkit.ops.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic, non-LLM facts extracted from the bounded baseline evidence. */
public record DiagnosticSignals(
    String candidateRootCause,
    String candidateCurrentCondition,
    boolean incidentWindowDegraded,
    boolean currentWindowHealthy,
    boolean applicationPoolSaturated,
    boolean currentDatabaseLockWait,
    boolean completedLockTransaction,
    double maxOrderApiCpuPercent,
    List<String> relevantEvidence
) {
    private static final long DEGRADED_P95_MS = 500;
    private static final double CPU_PRESSURE_PERCENT = 100.0;

    public DiagnosticSignals {
        relevantEvidence = relevantEvidence == null ? List.of() : List.copyOf(relevantEvidence);
    }

    public static DiagnosticSignals extract(List<Evidence> evidence) {
        boolean incidentDegraded = false;
        boolean currentHealthy = false;
        boolean poolSaturated = false;
        boolean lockWait = false;
        boolean lockAcquired = false;
        boolean lockReleased = false;
        boolean appDown = false;
        double maxCpu = 0;
        List<String> relevant = new ArrayList<>();

        for (Evidence item : evidence) {
            if (item.collectionStatus() != Evidence.CollectionStatus.OBSERVED
                || item.freshness() != Evidence.Freshness.CURRENT
                || !item.fact().path("success").asBoolean(false)) continue;
            var data = item.fact().path("data");
            switch (item.type()) {
                case BUSINESS_METRIC -> {
                    int window = data.path("windowSeconds").asInt(60);
                    long requests = data.path("requestCount").asLong();
                    long errors = data.path("errorCount").asLong();
                    long p95 = data.path("p95LatencyMs").asLong();
                    if (window >= 30 && requests > 0 && (errors > 0 || p95 >= DEGRADED_P95_MS)) {
                        incidentDegraded = true;
                        relevant.add(item.evidenceId());
                    }
                    if (window <= 10 && requests > 0 && errors == 0 && p95 < DEGRADED_P95_MS) {
                        currentHealthy = true;
                        relevant.add(item.evidenceId());
                    }
                    var pool = data.path("pool");
                    if (pool.path("max").asInt() > 0
                        && pool.path("active").asInt() >= pool.path("max").asInt()
                        && pool.path("pending").asInt() > 0) {
                        poolSaturated = true;
                        relevant.add(item.evidenceId());
                    }
                }
                case DB_LOCK_GRAPH -> {
                    if (data.path("rowCount").asInt() > 0) {
                        lockWait = true;
                        relevant.add(item.evidenceId());
                    }
                }
                case CONTAINER_RESOURCE -> {
                    if ("order-api".equals(item.scope())) {
                        double cpu = percent(data.path("CPUPerc").asText());
                        if (cpu > maxCpu) maxCpu = cpu;
                        if (cpu >= CPU_PRESSURE_PERCENT) relevant.add(item.evidenceId());
                    }
                }
                case LOGS -> {
                    if (!"order-api".equals(item.scope())) break;
                    String text = data.path("text").asText("").toLowerCase(Locale.ROOT);
                    lockAcquired |= text.contains("transaction acquired account row lock");
                    lockReleased |= text.contains("transaction released account row lock");
                    if (lockAcquired || lockReleased) relevant.add(item.evidenceId());
                }
                case SERVICE_STATUS, CONTAINER_STATUS -> {
                    if (!item.scope().contains("order-api")) break;
                    String state = data.path("State").asText("");
                    if (state.contains("exited") || state.contains("stopped")
                        || state.contains("down") || state.contains("unhealthy")) {
                        appDown = true;
                        relevant.add(item.evidenceId());
                    }
                }
                default -> { }
            }
        }

        boolean completedLock = lockAcquired && lockReleased;
        String root = appDown ? "APP_DOWN"
            : lockWait ? "DB_LOCK_WAIT"
            : poolSaturated ? "CONNECTION_EXHAUSTION"
            : maxCpu >= CPU_PRESSURE_PERCENT ? "CPU_PRESSURE"
            : completedLock && incidentDegraded ? "DB_LOCK_WAIT"
            : "INCONCLUSIVE";
        boolean currentCauseActive = appDown || lockWait || poolSaturated
            || maxCpu >= CPU_PRESSURE_PERCENT;
        String condition = currentCauseActive ? "ACTIVE"
            : currentHealthy ? "RECOVERED"
            : incidentDegraded ? "ACTIVE" : "UNKNOWN";
        return new DiagnosticSignals(root, condition, incidentDegraded, currentHealthy,
            poolSaturated, lockWait, completedLock, maxCpu, relevant.stream().distinct().toList());
    }

    private static double percent(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Double.parseDouble(value.replace("%", "").trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
