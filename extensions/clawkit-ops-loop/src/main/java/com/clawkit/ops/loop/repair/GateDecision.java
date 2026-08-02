package com.clawkit.ops.loop.repair;

/**
 * Deterministic policy gate decision for repair actions.
 * The model can only suggest; the gate evaluates and a human approves.
 */
public record GateDecision(boolean allowed, String reason) {

    public static final GateDecision ALLOWED = new GateDecision(true, "policy gate passed");

    public static GateDecision denied(String reason) {
        return new GateDecision(false, reason);
    }

    public boolean denied() {
        return !allowed;
    }
}
