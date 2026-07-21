package com.clawkit.ops.mcp;

public record CommandResult(
    int exitCode,
    String stdout,
    String stderr,
    boolean timedOut,
    boolean truncated,
    long totalOutputBytes
) {
    public boolean success() {
        return exitCode == 0 && !timedOut;
    }
}
