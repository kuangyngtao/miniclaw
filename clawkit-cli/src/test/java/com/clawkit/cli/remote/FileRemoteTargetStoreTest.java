package com.clawkit.cli.remote;

import com.clawkit.tools.remote.CredentialRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link FileRemoteTargetStore} — config parsing, store CRUD,
 * and credential reference resolution.
 */
class FileRemoteTargetStoreTest {

    @TempDir Path tempDir;

    private Path storeFile;
    private Path knownHostsFile;
    private Path keyFile;
    private FileRemoteTargetStore store;

    @BeforeEach
    void setUp() throws Exception {
        storeFile = tempDir.resolve("remote-targets.yaml");
        knownHostsFile = tempDir.resolve("known_hosts");
        keyFile = tempDir.resolve("id_test");
        Files.createFile(knownHostsFile);
        Files.createFile(keyFile);
        store = new FileRemoteTargetStore(storeFile);
    }

    // ── Config parsing ────────────────────────────────────────────────

    @Test
    void shouldParseValidConfig() throws Exception {
        Path configFile = validConfigFile();
        RemoteTargetConfig config = RemoteTargetConfig.parse(configFile);
        assertThat(config.targetId()).isEqualTo("test-server");
        assertThat(config.schemaVersion()).isEqualTo(1);
        assertThat(config.endpoint().host()).isEqualTo("203.0.113.10");
        assertThat(config.endpoint().port()).isEqualTo(22);
        assertThat(config.endpoint().user()).isEqualTo("opsro");
        assertThat(config.attestation().expectedCapabilityProfile()).isEqualTo("APP_DOWN_V1");
    }

    @Test
    void shouldRejectUnknownSchemaVersion() throws Exception {
        Path configFile = writeConfig("""
            schemaVersion: 99
            targetId: test-server
            endpoint:
              host: 203.0.113.10
              user: opsro
              identityFileRef: "file:%s"
              knownHostsFile: "%s"
            attestation:
              expectedServerName: clawkit-ops-mcp
              expectedProtocolVersion: "2024-11-05"
              expectedProbeVersion: "1"
              expectedCapabilityProfile: APP_DOWN_V1
              expectedToolSetHash: d822b006a5dcb84c
              expectedToolContractHash: "test-hash-required"
            """.formatted(fwd(keyFile), fwd(knownHostsFile)));

        assertThatThrownBy(() -> RemoteTargetConfig.parse(configFile))
            .isInstanceOf(RemoteTargetConfig.ConfigValidationException.class)
            .hasMessageContaining("schemaVersion");
    }

    @Test
    void shouldRejectInlinePrivateKeyInConfig() throws Exception {
        Path configFile = writeConfig("""
            schemaVersion: 1
            targetId: test-server
            endpoint:
              host: 203.0.113.10
              user: opsro
              identityFileRef: "-----BEGIN OPENSSH PRIVATE KEY-----"
              knownHostsFile: "%s"
            attestation:
              expectedServerName: clawkit-ops-mcp
              expectedProtocolVersion: "2024-11-05"
              expectedProbeVersion: "1"
              expectedCapabilityProfile: APP_DOWN_V1
              expectedToolSetHash: d822b006a5dcb84c
              expectedToolContractHash: "test-hash-required"
            """.formatted(fwd(knownHostsFile)));

        assertThatThrownBy(() -> RemoteTargetConfig.parse(configFile))
            .isInstanceOf(RemoteTargetConfig.ConfigValidationException.class);
    }

    @Test
    void shouldRejectNewlinesInIdentityRef() throws Exception {
        // identityFileRef with \n in YAML text block produces raw newlines → rejected at parse time
        Path configFile = writeConfig("""
            schemaVersion: 1
            targetId: test-server
            endpoint:
              host: 203.0.113.10
              user: opsro
              identityFileRef: "line1\\nline2"
              knownHostsFile: "%s"
            attestation:
              expectedServerName: clawkit-ops-mcp
              expectedProtocolVersion: "2024-11-05"
              expectedProbeVersion: "1"
              expectedCapabilityProfile: APP_DOWN_V1
              expectedToolSetHash: d822b006a5dcb84c
              expectedToolContractHash: "test-hash-required"
            """.formatted(fwd(knownHostsFile)));

        // YAML double-quoted \n is parsed as literal newline → EndpointConfig rejects it
        assertThatThrownBy(() -> RemoteTargetConfig.parse(configFile))
            .isInstanceOf(RemoteTargetConfig.ConfigValidationException.class)
            .hasMessageContaining("newlines");
    }

    @Test
    void shouldRejectInvalidTargetId() throws Exception {
        Path configFile = writeConfig("""
            schemaVersion: 1
            targetId: "INVALID UPPERCASE"
            endpoint:
              host: 203.0.113.10
              user: opsro
              identityFileRef: "file:%s"
              knownHostsFile: "%s"
            attestation:
              expectedServerName: clawkit-ops-mcp
              expectedProtocolVersion: "2024-11-05"
              expectedProbeVersion: "1"
              expectedCapabilityProfile: APP_DOWN_V1
              expectedToolSetHash: d822b006a5dcb84c
              expectedToolContractHash: "test-hash-required"
            """.formatted(fwd(keyFile), fwd(knownHostsFile)));

        assertThatThrownBy(() -> RemoteTargetConfig.parse(configFile))
            .isInstanceOf(RemoteTargetConfig.ConfigValidationException.class)
            .hasMessageContaining("targetId");
    }

    @Test
    void shouldRejectMissingRequiredFields() throws Exception {
        Path configFile = writeConfig("""
            schemaVersion: 1
            targetId: test-server
            endpoint:
              host: 203.0.113.10
              user: opsro
              identityFileRef: "file:%s"
            """.formatted(fwd(keyFile)));

        assertThatThrownBy(() -> RemoteTargetConfig.parse(configFile))
            .isInstanceOf(RemoteTargetConfig.ConfigValidationException.class);
    }

    // ── Store CRUD ────────────────────────────────────────────────────

    @Test
    void shouldAddAndListTargets() throws Exception {
        Path configFile = validConfigFile();
        store.add("test-server", configFile, false);

        assertThat(store.list()).containsExactly("test-server");
        assertThat(store.get("test-server")).isPresent();
    }

    @Test
    void shouldRejectDuplicateTarget() throws Exception {
        Path configFile = validConfigFile();
        store.add("test-server", configFile, false);

        assertThatThrownBy(() -> store.add("test-server", configFile, false))
            .isInstanceOf(RemoteTargetStore.TargetAlreadyExistsException.class);
    }

    @Test
    void shouldAllowReplaceDuplicate() throws Exception {
        Path configFile = validConfigFile();
        store.add("test-server", configFile, false);
        store.add("test-server", configFile, true); // --replace

        assertThat(store.list()).containsExactly("test-server");
    }

    @Test
    void shouldRemoveTarget() throws Exception {
        Path configFile = validConfigFile();
        store.add("test-server", configFile, false);
        store.remove("test-server");

        assertThat(store.list()).isEmpty();
    }

    @Test
    void shouldRejectRemoveActiveTarget() throws Exception {
        Path configFile = validConfigFile();
        store.add("test-server", configFile, false);
        store.markActive("test-server");

        assertThatThrownBy(() -> store.remove("test-server"))
            .isInstanceOf(RemoteTargetStore.TargetInUseException.class);
    }

    @Test
    void shouldRejectRemoveUnknownTarget() {
        assertThatThrownBy(() -> store.remove("nonexistent"))
            .isInstanceOf(RemoteTargetStore.TargetNotFoundException.class);
    }

    @Test
    void shouldPersistAndReload() throws Exception {
        Path configFile = validConfigFile();
        store.add("test-server", configFile, false);

        // Reload from same file
        var store2 = new FileRemoteTargetStore(storeFile);
        assertThat(store2.list()).containsExactly("test-server");
        assertThat(store2.get("test-server")).isPresent();
    }

    @Test
    void shouldSurviveBadEntryOnReload() throws Exception {
        // Write a broken YAML file directly with one good and one bad entry
        Files.writeString(storeFile, """
            targets:
              - targetId: good-one
                schemaVersion: 1
                endpoint:
                  host: 10.0.0.1
                  port: 22
                  user: opsro
                  identityFileRef: "file:%s"
                  knownHostsFile: "%s"
                attestation:
                  expectedServerName: clawkit-ops-mcp
                  expectedProtocolVersion: "2024-11-05"
                  expectedProbeVersion: "1"
                  expectedCapabilityProfile: APP_DOWN_V1
                  expectedToolSetHash: abc
                  expectedToolContractHash: "test-hash-required"
              - notEvenAMap: true
            """.formatted(fwd(keyFile), fwd(knownHostsFile)));

        var store2 = new FileRemoteTargetStore(storeFile);
        assertThat(store2.list()).contains("good-one");
    }

    // ── Credential resolution ─────────────────────────────────────────

    @Test
    void shouldResolveFileCredentialRef() {
        CredentialRef ref = CredentialRef.parse("file:" + fwd(keyFile));
        assertThat(ref).isInstanceOf(CredentialRef.FileRef.class);
        assertThat(ref.resolve()).isEqualTo(keyFile.toAbsolutePath().normalize());
    }

    @Test
    void shouldRejectEnvCredentialRefWhenVarMissing() {
        CredentialRef ref = CredentialRef.parse("env:NONEXISTENT_VAR_FOR_TEST_12345");
        assertThatThrownBy(() -> ref.resolve())
            .isInstanceOf(CredentialRef.CredentialRefException.class)
            .hasMessageContaining("not set or empty");
    }

    @Test
    void shouldRejectRelativePath() {
        CredentialRef ref = new CredentialRef.FileRef(Path.of("relative/path"));
        assertThatThrownBy(() -> ref.resolve())
            .isInstanceOf(CredentialRef.CredentialRefException.class)
            .hasMessageContaining("absolute");
    }

    // ── Config conversion ─────────────────────────────────────────────

    @Test
    void shouldConvertToTargetDescriptor() throws Exception {
        Path configFile = validConfigFile();
        RemoteTargetConfig config = RemoteTargetConfig.parse(configFile);

        var descriptor = config.toTargetDescriptor();
        assertThat(descriptor.targetId()).isEqualTo("test-server");
        assertThat(descriptor.expectedServerName()).isEqualTo("clawkit-ops-mcp");
        assertThat(descriptor.expectedProtocolVersion()).isEqualTo("2024-11-05");
        assertThat(descriptor.expectedCapabilityProfile()).isEqualTo("APP_DOWN_V1");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Convert path to forward-slash form for YAML compatibility on Windows. */
    private static String fwd(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private Path validConfigFile() throws Exception {
        return writeConfig("""
            schemaVersion: 1
            targetId: test-server
            endpoint:
              host: 203.0.113.10
              port: 22
              user: opsro
              identityFileRef: "file:%s"
              knownHostsFile: "%s"
              connectTimeoutSeconds: 10
              requestTimeoutSeconds: 15
              maxOutputBytes: 32768
            attestation:
              expectedServerName: clawkit-ops-mcp
              expectedProtocolVersion: "2024-11-05"
              expectedProbeVersion: "1"
              expectedCapabilityProfile: APP_DOWN_V1
              expectedToolSetHash: d822b006a5dcb84c
              expectedToolContractHash: "test-hash-required"
              expectedToolContractHash: "test-hash-not-real-but-required"
            """.formatted(fwd(keyFile), fwd(knownHostsFile)));
    }

    private Path writeConfig(String yaml) throws Exception {
        Path file = tempDir.resolve("target-" + System.nanoTime() + ".yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
