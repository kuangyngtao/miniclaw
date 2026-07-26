package com.clawkit.ops.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteIncidentDeliveryMainTest {

    @Test void missingTargetReturns4() {
        assertThat(RemoteIncidentDeliveryMain.run(new String[]{}))
            .isEqualTo(4);
    }

    @Test void unknownArgReturns4() {
        assertThat(RemoteIncidentDeliveryMain.run(new String[]{"--unknown"}))
            .isEqualTo(4);
    }

    @Test void missingArgValueReturns4() {
        // --target without value → ConfigException → exit 4
        assertThat(RemoteIncidentDeliveryMain.run(new String[]{"--target"}))
            .isEqualTo(4);
    }

    @Test void notifyFlagParsedWithoutError() {
        // Should fail on missing env (exit 4), not on --notify parsing
        int rc = RemoteIncidentDeliveryMain.run(new String[]{"--target", "test", "--notify"});
        assertThat(rc).isIn(4, 3); // config error or SSH failure
    }

    @Test void usageForUnknownArgs() {
        // Empty args should show usage pattern
        int rc = RemoteIncidentDeliveryMain.run(new String[]{"--output", "/tmp"});
        assertThat(rc).isEqualTo(4); // missing --target
    }
}
