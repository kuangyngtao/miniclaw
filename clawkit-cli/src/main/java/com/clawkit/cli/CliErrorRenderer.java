package com.clawkit.cli;

/** Renders safe user-facing failures without stack traces. */
final class CliErrorRenderer {
    private CliErrorRenderer() {}

    static void render(ConfigurationException error) {
        System.err.println("[" + error.code() + "] " + error.getMessage());
        System.err.println("Impact: " + error.impact());
        System.err.println("Next: " + error.nextAction());
    }

    static String renderUnexpected(Throwable error) {
        String id = java.util.UUID.randomUUID().toString().substring(0, 8);
        System.err.println("[A-003] Unexpected internal error");
        System.err.println("Impact: the current operation stopped");
        System.err.println("Next: inspect ~/.clawkit/logs/clawkit.log (diagnostic " + id + ")");
        return id;
    }
}
