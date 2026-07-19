package com.clawkit.reliability.attempt;

import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionIdentity;

import java.time.Instant;
import java.util.Objects;

/**
 * 持久化的动作 Attempt（P1-G3）。
 * version 用于 CAS：迟到响应/旧 ticket 不能覆盖新状态或人工结论。
 */
public record ActionAttempt(
    String attemptId,
    ActionIdentity identity,
    ActionDescriptor descriptor,
    AttemptState state,
    long version,
    int attemptSeq,
    String runId,
    String relatedAttemptId,
    String purpose,
    String reason,
    Instant createdAt,
    Instant updatedAt
) {
    public ActionAttempt {
        Objects.requireNonNull(attemptId, "attemptId required");
        Objects.requireNonNull(identity, "identity required");
        Objects.requireNonNull(descriptor, "descriptor required");
        Objects.requireNonNull(state, "state required");
        if (version < 0) throw new IllegalArgumentException("version must be >= 0");
        if (attemptSeq < 1) throw new IllegalArgumentException("attemptSeq must be >= 1");
        if (purpose == null || purpose.isBlank()) purpose = "ACTION";
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    public String targetKey() {
        return identity.targetKey();
    }

    public ActionAttempt with(AttemptState newState, String newReason, Instant at) {
        return new ActionAttempt(attemptId, identity, descriptor, newState,
            version + 1, attemptSeq, runId, relatedAttemptId, purpose,
            newReason != null ? newReason : reason, createdAt, at);
    }
}
