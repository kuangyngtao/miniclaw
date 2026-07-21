package com.clawkit.ops.mcp;

import java.time.Duration;

public interface OpsBackend {
    OpsToolResult serviceStatus(String service);
    OpsToolResult containerStatus(String service);
    OpsToolResult ports(String service, int containerPort);
    OpsToolResult httpProbe(String endpoint);
    OpsToolResult logs(String service, Duration window, int tail);
}
