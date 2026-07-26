package com.clawkit.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Result of an MCP {@code initialize} handshake.
 *
 * <p>Contains the protocol version, server identity, and the raw
 * serverInfo extension fields for downstream attestation.
 * This is a general MCP type — it carries no Clawkit-specific
 * profile knowledge.
 */
public record McpInitializeResult(
    String protocolVersion,
    String serverName,
    String serverVersion,
    JsonNode serverInfo
) {
    public McpInitializeResult {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(serverVersion, "serverVersion");
        Objects.requireNonNull(serverInfo, "serverInfo");
    }

    /** Convenience: read a text field from serverInfo. */
    public String serverInfoText(String field) {
        JsonNode v = serverInfo.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
