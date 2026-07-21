package com.clawkit.ops.loop;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Incident {
    private static final Map<IncidentState, Set<IncidentState>> ALLOWED =
        allowedTransitions();

    private final String incidentId;
    private final Instant discoveredAt;
    private final Clock clock;
    private final List<IncidentTransition> transitions = new ArrayList<>();
    private IncidentState state = IncidentState.DISCOVERED;

    public Incident(String incidentId, Clock clock) {
        if (incidentId == null || incidentId.isBlank()) {
            throw new IllegalArgumentException("incidentId must not be blank");
        }
        this.incidentId = incidentId;
        this.clock = clock;
        this.discoveredAt = clock.instant();
    }

    public synchronized void transition(
        IncidentState target, String reason, String runId, List<String> evidenceReferences
    ) {
        if (!ALLOWED.getOrDefault(state, Set.of()).contains(target)) {
            throw new IllegalStateException("invalid incident transition: " + state + " -> " + target);
        }
        transitions.add(new IncidentTransition(
            state, target, reason, clock.instant(), runId, evidenceReferences));
        state = target;
    }

    public String incidentId() { return incidentId; }
    public Instant discoveredAt() { return discoveredAt; }
    public synchronized IncidentState state() { return state; }
    public synchronized List<IncidentTransition> transitions() {
        return List.copyOf(transitions);
    }

    private static Map<IncidentState, Set<IncidentState>> allowedTransitions() {
        Map<IncidentState, Set<IncidentState>> map = new EnumMap<>(IncidentState.class);
        map.put(IncidentState.DISCOVERED, Set.of(IncidentState.COLLECTING));
        map.put(IncidentState.COLLECTING,
            Set.of(IncidentState.EVIDENCE_READY, IncidentState.INCONCLUSIVE));
        map.put(IncidentState.EVIDENCE_READY,
            Set.of(IncidentState.DIAGNOSED, IncidentState.INCONCLUSIVE));
        map.put(IncidentState.DIAGNOSED, Set.of(IncidentState.READ_ONLY_COMPLETE));
        map.put(IncidentState.INCONCLUSIVE, Set.of(IncidentState.ESCALATED));
        return Map.copyOf(map);
    }
}
