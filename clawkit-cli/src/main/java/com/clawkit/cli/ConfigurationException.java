package com.clawkit.cli;

/** Safe, actionable configuration failure suitable for terminal display. */
public final class ConfigurationException extends RuntimeException {
    private final String code;
    private final String impact;
    private final String nextAction;

    public ConfigurationException(String code, String reason, String impact, String nextAction) {
        super(reason);
        this.code = code;
        this.impact = impact;
        this.nextAction = nextAction;
    }

    public String code() { return code; }
    public String impact() { return impact; }
    public String nextAction() { return nextAction; }
}
