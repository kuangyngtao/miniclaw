package com.clawkit.ops.loop;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void acceptsReadOnlyHappyPathAndRecordsEveryTransition() {
        Incident incident = new Incident("inc-1", CLOCK);

        incident.transition(IncidentState.COLLECTING, "collect", "run-1", List.of());
        incident.transition(IncidentState.EVIDENCE_READY, "ready", "run-1", List.of("e-1"));
        incident.transition(IncidentState.DIAGNOSED, "diagnosed", "run-1", List.of("e-1"));
        incident.transition(IncidentState.READ_ONLY_COMPLETE, "complete", "run-1", List.of("e-1"));

        assertThat(incident.state()).isEqualTo(IncidentState.READ_ONLY_COMPLETE);
        assertThat(incident.transitions()).hasSize(4);
        assertThat(incident.transitions()).allMatch(t -> t.runId().equals("run-1"));
    }

    @Test
    void rejectsSkippedState() {
        Incident incident = new Incident("inc-1", CLOCK);

        assertThatThrownBy(() -> incident.transition(
            IncidentState.DIAGNOSED, "skip", "run-1", List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DISCOVERED -> DIAGNOSED");
    }
}
