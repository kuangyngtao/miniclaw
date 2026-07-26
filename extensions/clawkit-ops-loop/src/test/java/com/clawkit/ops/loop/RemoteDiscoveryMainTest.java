package com.clawkit.ops.loop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiscoveryMainTest {

    @Test void missingTargetReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{"--profile", "APP_DOWN_V1"}))
            .isEqualTo(4);
    }

    @Test void unknownArgReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{"--unknown", "x"}))
            .isEqualTo(4);
    }

    @Test void missingRequiredEnvReturns4() {
        // No env vars set → require() exits with 4
        assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
            .isEqualTo(4);
    }

    @Test void invalidProfileReturns4() {
        try {
            System.setProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE", "INVALID");
            assertThat(RemoteDiscoveryMain.run(new String[]{"--target", "test"}))
                .isEqualTo(4);
        } finally {
            System.clearProperty("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE");
        }
    }

    @Test void noArgsShowsUsageReturns4() {
        assertThat(RemoteDiscoveryMain.run(new String[]{}))
            .isEqualTo(4);
    }
}
