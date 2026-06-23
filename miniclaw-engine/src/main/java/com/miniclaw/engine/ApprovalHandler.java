package com.miniclaw.engine;

@FunctionalInterface
public interface ApprovalHandler {
    ApprovalResult handle(ApprovalRequest request);
}
