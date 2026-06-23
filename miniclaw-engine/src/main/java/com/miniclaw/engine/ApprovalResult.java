package com.miniclaw.engine;

public sealed interface ApprovalResult {
    record Approve() implements ApprovalResult {}
    record ApproveAllSameType(String toolName) implements ApprovalResult {}
    record Reject(String reason) implements ApprovalResult {}
    record ModifyParams(String guidance) implements ApprovalResult {}
}
