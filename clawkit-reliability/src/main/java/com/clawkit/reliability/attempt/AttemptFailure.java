package com.clawkit.reliability.attempt;

/** 可靠性控制面异常族（P1-G3）。 */
public sealed interface AttemptFailure {

    /** 目标被活跃 Attempt 占用（含 OUTCOME_UNKNOWN sticky 锁）。 */
    final class TargetBusyException extends RuntimeException implements AttemptFailure {
        private final String targetKey;
        private final String holderAttemptId;
        public TargetBusyException(String targetKey, String holderAttemptId) {
            super("target busy: " + targetKey + " held by attempt " + holderAttemptId);
            this.targetKey = targetKey;
            this.holderAttemptId = holderAttemptId;
        }
        public String targetKey() { return targetKey; }
        public String holderAttemptId() { return holderAttemptId; }
    }

    /** 非法状态迁移。 */
    final class IllegalTransitionException extends RuntimeException implements AttemptFailure {
        public IllegalTransitionException(String attemptId, AttemptState from, AttemptState to) {
            super("illegal transition " + from + " -> " + to + " on attempt " + attemptId);
        }
    }

    /** version CAS 失败：迟到事件不得覆盖新状态或人工结论。 */
    final class StaleVersionException extends RuntimeException implements AttemptFailure {
        public StaleVersionException(String attemptId, long expected, long actual) {
            super("stale version on attempt " + attemptId
                + ": expected " + expected + " but is " + actual);
        }
    }

    /** Reliability Journal 不可写：控制面 fail closed，阻断写动作。 */
    final class StoreUnavailableException extends RuntimeException implements AttemptFailure {
        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 同一幂等键被不同动作指纹或目标复用。 */
    final class IdempotencyConflictException extends RuntimeException implements AttemptFailure {
        public IdempotencyConflictException(String key) {
            super("idempotency key reused with different action identity: " + key);
        }
    }

    /** 次数上限。 */
    final class MaxAttemptsExceededException extends RuntimeException implements AttemptFailure {
        public MaxAttemptsExceededException(String logicalActionId, int maxAttempts) {
            super("max attempts (" + maxAttempts + ") exceeded for " + logicalActionId);
        }
    }

    /** 冷却窗口未过。 */
    final class CooldownActiveException extends RuntimeException implements AttemptFailure {
        public CooldownActiveException(String targetKey, java.time.Instant until) {
            super("cooldown active on " + targetKey + " until " + until);
        }
    }
}
