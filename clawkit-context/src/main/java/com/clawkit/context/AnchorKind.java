package com.clawkit.context;

/** P1-A6：compaction anchor 的语义类型。 */
public enum AnchorKind {
    USER_CONSTRAINT,
    INCIDENT,
    CONFIRMED_FACT,
    COUNTER_EVIDENCE,
    OPEN_HYPOTHESIS,
    PENDING_CHECK,
    APPROVAL_BOUNDARY,
    EVIDENCE
}
