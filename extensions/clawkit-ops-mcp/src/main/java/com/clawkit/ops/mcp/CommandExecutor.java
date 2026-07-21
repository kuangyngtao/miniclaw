package com.clawkit.ops.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface CommandExecutor {
    CommandResult execute(List<String> command, Map<String, String> environment,
                          Duration timeout, int maxOutputBytes);
}
