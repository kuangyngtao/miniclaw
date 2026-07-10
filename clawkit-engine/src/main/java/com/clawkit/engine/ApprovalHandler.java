package com.clawkit.engine;

@FunctionalInterface
public interface ApprovalHandler {
    ApprovalResult handle(ApprovalRequest request);
}
