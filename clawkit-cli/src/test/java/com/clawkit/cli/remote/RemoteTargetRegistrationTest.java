package com.clawkit.cli.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for v2 registration, profile catalog, and mixed v1/v2 store.
 *
 * <p>PR-2 gate: v1 still works, v2 is secret-free, FIX not selectable,
 * unknown manifest fail closed.
 */
class RemoteTargetRegistrationTest {

    @TempDir Path tempDir;

    // ── v2 registration ──────────────────────────────────────────────

    @Test
    void shouldCreateValidRegistration() {
        var reg = new RemoteTargetRegistration(
            2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "app-down-readonly-v1");
        assertThat(reg.schemaVersion()).isEqualTo(2);
        assertThat(reg.targetId()).isEqualTo("test-server");
        assertThat(reg.connection()).isInstanceOf(OpenSshAliasReference.class);
    }

    @Test
    void shouldRejectUnknownManifestId() {
        assertThatThrownBy(() -> new RemoteTargetRegistration(
            2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "fix-order-api-v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown profile");
    }

    @Test
    void shouldRejectWriteProfileInCatalog() {
        // FIX profile must not be in the built-in catalog
        assertThat(RemoteProfileCatalog.isKnown("fix-order-api-v1")).isFalse();
    }

    // ── Profile catalog ──────────────────────────────────────────────

    @Test
    void catalogShouldContainAppDown() {
        var manifest = RemoteProfileCatalog.lookup("app-down-readonly-v1");
        assertThat(manifest).isPresent();
        assertThat(manifest.get().accessMode()).isEqualTo(RemoteAccessMode.READ_ONLY);
        assertThat(manifest.get().serverName()).isEqualTo("clawkit-ops-mcp");
        assertThat(manifest.get().capabilityProfile()).isEqualTo("APP_DOWN_V1");
    }

    @Test
    void catalogShouldContainPostgres() {
        var manifest = RemoteProfileCatalog.lookup("postgres-diagnosis-readonly-v1");
        assertThat(manifest).isPresent();
        assertThat(manifest.get().accessMode()).isEqualTo(RemoteAccessMode.READ_ONLY);
        assertThat(manifest.get().capabilityProfile()).isEqualTo("POSTGRES_DIAGNOSIS_V1");
    }

    @Test
    void catalogShouldNotContainFix() {
        assertThat(RemoteProfileCatalog.lookup("fix-order-api-v1")).isEmpty();
    }

    @Test
    void unknownManifestShouldFailClosed() {
        assertThat(RemoteProfileCatalog.lookup("nonexistent")).isEmpty();
    }

    // ── Resolver ─────────────────────────────────────────────────────

    @Test
    void shouldResolveDescriptorFromRegistration() {
        var reg = new RemoteTargetRegistration(
            2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "app-down-readonly-v1");
        var descriptor = RemoteTargetResolver.resolveDescriptor(reg);
        assertThat(descriptor.targetId()).isEqualTo("test-server");
        assertThat(descriptor.expectedServerName()).isEqualTo("clawkit-ops-mcp");
        assertThat(descriptor.expectedCapabilityProfile()).isEqualTo("APP_DOWN_V1");
    }

    @Test
    void shouldResolveConnectionSpecFromRegistration() {
        var reg = new RemoteTargetRegistration(
            2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "app-down-readonly-v1");
        var spec = RemoteTargetResolver.resolveConnectionSpec(reg);
        assertThat(spec).isInstanceOf(
            com.clawkit.tools.remote.OpenSshAliasConnectionSpec.class);
    }

    // ── Mixed v1/v2 store ────────────────────────────────────────────

    @Test
    void shouldSupportMixedV1V2Store() throws Exception {
        Path storeFile = tempDir.resolve("targets.yaml");
        var store = new FileRemoteTargetStore(storeFile);

        // Add v2
        var reg = new RemoteTargetRegistration(
            2, "v2-server",
            new OpenSshAliasReference("v2-server", "opsro"),
            "app-down-readonly-v1");
        store.add(reg, false);

        // Add v1
        Path knownHosts = tempDir.resolve("kh");
        Path keyFile = tempDir.resolve("id_test");
        Files.createFile(knownHosts);
        Files.createFile(keyFile);
        Path configFile = writeV1Config("v1-server", keyFile, knownHosts);
        store.add("v1-server", configFile, false);

        // Both should be listed
        assertThat(store.list()).contains("v1-server", "v2-server");

        // v1 lookup
        assertThat(store.get("v1-server")).isPresent();
        // v2 lookup
        assertThat(store.getRegistration("v2-server")).isPresent();
    }

    @Test
    void v2RegistrationShouldNotContainSecretFields() {
        var reg = new RemoteTargetRegistration(
            2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "app-down-readonly-v1");

        // No key paths, IPs, or hashes in the registration
        String str = reg.toString();
        assertThat(str).doesNotContain(".ssh");
        assertThat(str).doesNotContain("id_");
        assertThat(str).doesNotContain("PRIVATE");
        assertThat(str).doesNotContain("known_hosts");
        assertThat(str).doesNotContain("hash");
    }

    @Test
    void shouldRejectDuplicateV2WithoutReplace() {
        Path storeFile = tempDir.resolve("targets.yaml");
        var store = new FileRemoteTargetStore(storeFile);

        var reg = new RemoteTargetRegistration(
            2, "test-server",
            new OpenSshAliasReference("test-server", "opsro"),
            "app-down-readonly-v1");
        store.add(reg, false);

        assertThatThrownBy(() -> store.add(reg, false))
            .isInstanceOf(RemoteTargetStore.TargetAlreadyExistsException.class);
    }

    @Test
    void shouldReloadMixedStore() throws Exception {
        Path storeFile = tempDir.resolve("targets.yaml");
        Path knownHosts = tempDir.resolve("kh");
        Path keyFile = tempDir.resolve("id_test");
        Files.createFile(knownHosts);
        Files.createFile(keyFile);

        // Write v1 + v2
        var store1 = new FileRemoteTargetStore(storeFile);
        var reg = new RemoteTargetRegistration(
            2, "v2-server",
            new OpenSshAliasReference("v2-server", "opsro"),
            "app-down-readonly-v1");
        store1.add(reg, false);
        Path configFile = writeV1Config("v1-server", keyFile, knownHosts);
        store1.add("v1-server", configFile, false);

        // Reload
        var store2 = new FileRemoteTargetStore(storeFile);
        assertThat(store2.list()).contains("v1-server", "v2-server");
        assertThat(store2.get("v1-server")).isPresent();
        assertThat(store2.getRegistration("v2-server")).isPresent();
    }

    @Test
    void shouldRejectV2WithUnknownManifestOnLoad() throws Exception {
        Path storeFile = tempDir.resolve("targets.yaml");
        String badYaml = """
            targets:
              - schemaVersion: 2
                targetId: bad-server
                connection:
                  type: openssh-alias
                  alias: bad-server
                  remoteUser: opsro
                profileManifestId: unknown-profile
            """;
        Files.writeString(storeFile, badYaml);

        var store = new FileRemoteTargetStore(storeFile);
        // Bad entry should be skipped
        assertThat(store.list()).doesNotContain("bad-server");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Path writeV1Config(String targetId, Path keyFile, Path knownHosts)
            throws Exception {
        String yaml = """
            schemaVersion: 1
            targetId: %s
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
            """.formatted(targetId,
                keyFile.toAbsolutePath().toString().replace("\\", "\\\\"),
                knownHosts.toAbsolutePath().toString().replace("\\", "\\\\"));
        Path file = tempDir.resolve(targetId + ".yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
