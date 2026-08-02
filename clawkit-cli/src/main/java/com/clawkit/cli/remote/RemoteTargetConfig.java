package com.clawkit.cli.remote;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * YAML-based single-target import configuration.
 *
 * <p>Matches the schema in REMOTE-0 §6.3. Only contains target identity
 * and endpoint fields — never stores the private key content, only a
 * credential reference string.
 *
 * <p>Example:
 * <pre>{@code
 * schemaVersion: 1
 * targetId: test-server
 * endpoint:
 *   host: 203.0.113.10
 *   port: 22
 *   user: opsro
 *   identityFileRef: env:CLAWKIT_TEST_SERVER_KEY_FILE
 *   knownHostsFile: D:/keys/known_hosts_clawkit
 *   connectTimeoutSeconds: 10
 *   requestTimeoutSeconds: 15
 *   maxOutputBytes: 32768
 * attestation:
 *   expectedServerName: clawkit-ops-mcp
 *   expectedProtocolVersion: "2024-11-05"
 *   expectedProbeVersion: "1"
 *   expectedCapabilityProfile: APP_DOWN_V1
 *   expectedToolSetHash: d822b006a5dcb84c
 *   expectedToolContractHash: ...
 * }</pre>
 */
public class RemoteTargetConfig {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonProperty("schemaVersion")
    private int schemaVersion;

    @JsonProperty("targetId")
    private String targetId;

    @JsonProperty("endpoint")
    private EndpointConfig endpoint;

    @JsonProperty("attestation")
    private AttestationConfig attestation;

    // ── Nested types ──────────────────────────────────────────────────

    public static class EndpointConfig {
        @JsonProperty("host")
        private String host;

        @JsonProperty("port")
        private int port = 22;

        @JsonProperty("user")
        private String user;

        @JsonProperty("identityFileRef")
        private String identityFileRef;

        @JsonProperty("knownHostsFile")
        private String knownHostsFile;

        @JsonProperty("connectTimeoutSeconds")
        private int connectTimeoutSeconds = 10;

        @JsonProperty("requestTimeoutSeconds")
        private int requestTimeoutSeconds = 15;

        @JsonProperty("maxOutputBytes")
        private int maxOutputBytes = 32768;

        public String host() { return host; }
        public int port() { return port; }
        public String user() { return user; }
        public String identityFileRef() { return identityFileRef; }
        public String knownHostsFile() { return knownHostsFile; }
        public int connectTimeoutSeconds() { return connectTimeoutSeconds; }
        public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
        public int maxOutputBytes() { return maxOutputBytes; }

        void validate(String targetId) {
            if (host == null || host.isBlank())
                throw new ConfigValidationException(targetId, "endpoint.host is required");
            if (user == null || user.isBlank())
                throw new ConfigValidationException(targetId, "endpoint.user is required");
            if (identityFileRef == null || identityFileRef.isBlank())
                throw new ConfigValidationException(targetId, "endpoint.identityFileRef is required");
            if (identityFileRef.contains("-----BEGIN") || identityFileRef.contains("PRIVATE KEY"))
                throw new ConfigValidationException(targetId,
                    "endpoint.identityFileRef appears to contain key material — use a path reference");
            if (identityFileRef.contains("\n") || identityFileRef.contains("\r"))
                throw new ConfigValidationException(targetId,
                    "endpoint.identityFileRef contains newlines");
            if (knownHostsFile == null || knownHostsFile.isBlank())
                throw new ConfigValidationException(targetId, "endpoint.knownHostsFile is required");
            if (port < 1 || port > 65535)
                throw new ConfigValidationException(targetId, "endpoint.port out of range: " + port);
            if (connectTimeoutSeconds < 1)
                throw new ConfigValidationException(targetId, "endpoint.connectTimeoutSeconds must be >= 1");
            if (requestTimeoutSeconds < 1)
                throw new ConfigValidationException(targetId, "endpoint.requestTimeoutSeconds must be >= 1");
            if (maxOutputBytes < 256)
                throw new ConfigValidationException(targetId, "endpoint.maxOutputBytes must be >= 256");
        }
    }

    public static class AttestationConfig {
        @JsonProperty("expectedServerName")
        private String expectedServerName;

        @JsonProperty("expectedProtocolVersion")
        private String expectedProtocolVersion;

        @JsonProperty("expectedProbeVersion")
        private String expectedProbeVersion;

        @JsonProperty("expectedCapabilityProfile")
        private String expectedCapabilityProfile;

        @JsonProperty("expectedToolSetHash")
        private String expectedToolSetHash;

        @JsonProperty("expectedToolContractHash")
        private String expectedToolContractHash;

        public String expectedServerName() { return expectedServerName; }
        public String expectedProtocolVersion() { return expectedProtocolVersion; }
        public String expectedProbeVersion() { return expectedProbeVersion; }
        public String expectedCapabilityProfile() { return expectedCapabilityProfile; }
        public String expectedToolSetHash() { return expectedToolSetHash; }
        public String expectedToolContractHash() { return expectedToolContractHash; }

        void validate(String targetId) {
            if (expectedServerName == null || expectedServerName.isBlank())
                throw new ConfigValidationException(targetId, "attestation.expectedServerName is required");
            if (expectedProtocolVersion == null || expectedProtocolVersion.isBlank())
                throw new ConfigValidationException(targetId, "attestation.expectedProtocolVersion is required");
            if (expectedProbeVersion == null || expectedProbeVersion.isBlank())
                throw new ConfigValidationException(targetId, "attestation.expectedProbeVersion is required");
            if (expectedCapabilityProfile == null || expectedCapabilityProfile.isBlank())
                throw new ConfigValidationException(targetId, "attestation.expectedCapabilityProfile is required");
            if (expectedToolSetHash == null || expectedToolSetHash.isBlank())
                throw new ConfigValidationException(targetId, "attestation.expectedToolSetHash is required");
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public int schemaVersion() { return schemaVersion; }
    public String targetId() { return targetId; }
    public EndpointConfig endpoint() { return endpoint; }
    public AttestationConfig attestation() { return attestation; }

    // ── Validation ────────────────────────────────────────────────────

    void validate() {
        if (targetId == null || targetId.isBlank())
            throw new ConfigValidationException("<unknown>", "targetId is required");
        if (!targetId.matches("[a-z0-9][a-z0-9_-]{0,62}"))
            throw new ConfigValidationException(targetId,
                "targetId must match [a-z0-9][a-z0-9_-]{0,62}");
        if (schemaVersion != CURRENT_SCHEMA_VERSION)
            throw new ConfigValidationException(targetId,
                "unsupported schemaVersion: " + schemaVersion + " (expected " + CURRENT_SCHEMA_VERSION + ")");
        if (endpoint == null)
            throw new ConfigValidationException(targetId, "endpoint is required");
        if (attestation == null)
            throw new ConfigValidationException(targetId, "attestation is required");
        endpoint.validate(targetId);
        attestation.validate(targetId);
    }

    // ── Parsing ───────────────────────────────────────────────────────

    public static RemoteTargetConfig parse(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("target config file not found: " + file);
        }
        String content = Files.readString(file);
        // Reject inline PEM/private key in YAML
        if (content.contains("PRIVATE KEY-----") || content.contains("-----BEGIN")) {
            throw new ConfigValidationException("<file>",
                "config file contains private key material — use a credential reference instead");
        }
        RemoteTargetConfig config = YAML.readValue(file.toFile(), RemoteTargetConfig.class);
        config.validate();
        return config;
    }

    // ── Conversion ────────────────────────────────────────────────────

    /**
     * Convert to the generic tools-level {@link com.clawkit.tools.remote.RemoteTargetDescriptor}.
     */
    public com.clawkit.tools.remote.RemoteTargetDescriptor toTargetDescriptor() {
        String contractHash = attestation.expectedToolContractHash();
        if (contractHash == null || contractHash.isBlank()) {
            throw new ConfigValidationException(targetId,
                "attestation.expectedToolContractHash is required (must be pre-pinned)");
        }
        return new com.clawkit.tools.remote.RemoteTargetDescriptor(
            targetId,
            attestation.expectedServerName(),
            attestation.expectedProtocolVersion(),
            attestation.expectedProbeVersion(),
            attestation.expectedCapabilityProfile(),
            attestation.expectedToolSetHash(),
            contractHash
        );
    }

    /**
     * Convert to the generic tools-level {@link com.clawkit.tools.remote.RemoteEndpointConfig}.
     */
    public com.clawkit.tools.remote.RemoteEndpointConfig toEndpointConfig(
            com.clawkit.tools.remote.CredentialRef credentialRef) {
        return new com.clawkit.tools.remote.RemoteEndpointConfig(
            endpoint.host(),
            endpoint.port(),
            endpoint.user(),
            credentialRef,
            Path.of(endpoint.knownHostsFile()),
            java.time.Duration.ofSeconds(endpoint.connectTimeoutSeconds()),
            java.time.Duration.ofSeconds(endpoint.requestTimeoutSeconds()),
            endpoint.maxOutputBytes()
        );
    }

    // ── Exception ─────────────────────────────────────────────────────

    public static class ConfigValidationException extends RuntimeException {
        private final String targetId;
        ConfigValidationException(String targetId, String message) {
            super(message);
            this.targetId = targetId;
        }
        public String targetId() { return targetId; }
    }
}
