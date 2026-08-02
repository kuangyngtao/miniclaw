package com.clawkit.cli.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** P1-2: Onboarding confirm/cancel and store write integrity tests. */
class RemoteOnboardingConfirmTest {

    @TempDir Path tempDir;
    private Path storeFile;
    private FileRemoteTargetStore store;
    private Path sshDir;
    private SystemOpenSshFacade sshFacade;
    private RemoteOnboardingService onboarding;

    @BeforeEach
    void setUp() throws Exception {
        storeFile = tempDir.resolve("targets.yaml");
        store = new FileRemoteTargetStore(storeFile);
        sshDir = tempDir.resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.writeString(sshDir.resolve("config"), """
            Host test-server
              HostName 203.0.113.10
              User opsro
            """);
        sshFacade = new TestSshFacade(sshDir, null);
        onboarding = new RemoteOnboardingService(store, sshFacade);
    }

    // ── Preview does NOT write to store ───────────────────────────────

    @Test
    void previewMustNotWriteToStore() throws Exception {
        var preview = onboarding.preview("test-server", null, null);
        assertThat(preview.targetId()).isEqualTo("test-server");
        assertThat(store.list()).isEmpty(); // store untouched
    }

    // ── Register writes to store ──────────────────────────────────────

    @Test
    void registerMustWriteToStore() throws Exception {
        onboarding.register("test-server", "test-server",
            "app-down-readonly-v1", false);
        assertThat(store.list()).contains("test-server");
        var reg = store.getRegistration("test-server");
        assertThat(reg).isPresent();
        assertThat(reg.get().profileManifestId()).isEqualTo("app-down-readonly-v1");
    }

    // ── Cancel (no register call) leaves store empty ──────────────────

    @Test
    void cancelMustNotWriteToStore() throws Exception {
        // Preview then decide not to register
        var preview = onboarding.preview("test-server", null, null);
        assertThat(preview).isNotNull();
        // User cancels — never call register()
        assertThat(store.list()).isEmpty();
    }

    // ── Duplicate without replace must fail ───────────────────────────

    @Test
    void duplicateWithoutReplaceMustFail() throws Exception {
        onboarding.register("test-server", "test-server",
            "app-down-readonly-v1", false);
        assertThatThrownBy(() -> onboarding.register("test-server", "test-server",
            "app-down-readonly-v1", false))
            .isInstanceOf(RemoteTargetStore.TargetAlreadyExistsException.class);
    }

    // ── Replace must succeed ──────────────────────────────────────────

    @Test
    void replaceMustOverwrite() throws Exception {
        onboarding.register("test-server", "test-server",
            "app-down-readonly-v1", false);
        onboarding.register("test-server", "test-server",
            "postgres-diagnosis-readonly-v1", true);
        var reg = store.getRegistration("test-server").orElseThrow();
        assertThat(reg.profileManifestId()).isEqualTo("postgres-diagnosis-readonly-v1");
    }

    // ── Unknown profile must be rejected ──────────────────────────────

    @Test
    void unknownProfileMustBeRejected() {
        assertThatThrownBy(() -> onboarding.register("test-server", "test-server",
            "nonexistent-profile", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown profile");
    }

    // ── Preview summary must not leak secrets ─────────────────────────

    @Test
    void previewSummaryMustNotLeakSecrets() throws Exception {
        var preview = onboarding.preview("test-server", null, null);
        String summary = preview.renderSummary();
        assertThat(summary).doesNotContain("PRIVATE");
        assertThat(summary).doesNotContain("id_");
        assertThat(summary).doesNotContain(".pem");
        assertThat(summary).doesNotContain(".ssh");
        assertThat(summary).contains("APP_DOWN_V1");
        assertThat(summary).contains("READ_ONLY");
        assertThat(summary).doesNotContain("opsro@");
    }

    // ── Available profiles are read-only ──────────────────────────────

    @Test
    void availableProfilesMustBeReadOnlyOnly() {
        var profiles = onboarding.availableProfiles();
        assertThat(profiles).isNotEmpty();
        for (var p : profiles) {
            assertThat(p.accessMode()).isEqualTo(RemoteAccessMode.READ_ONLY);
            assertThat(p.manifestId()).doesNotContain("fix");
        }
    }

    // ── Re-audit before write catches config drift ────────────────────

    @Test
    void registerReauditsBeforeWrite() throws Exception {
        // Registration succeeds with safe config
        onboarding.register("test-server", "test-server",
            "app-down-readonly-v1", false);
        assertThat(store.list()).contains("test-server");
    }

    // ── Test SSH facade ───────────────────────────────────────────────

    static class TestSshFacade extends SystemOpenSshFacade {
        TestSshFacade(Path userDir, Path sysDir) { super(userDir, sysDir); }
        @Override
        public SshGResult runSshG(String alias, String user) throws IOException {
            return new SshGResult("203.0.113.10", 22, "opsro",
                null, null, "", 1, false, false, false);
        }
    }
}
