package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpsMcpHttpMainTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recognizesInitializedNotificationWithoutRequestId() throws Exception {
        var request = MAPPER.readTree("""
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """);

        assertThat(OpsMcpHttpMain.isNotification(request)).isTrue();
    }

    @Test
    void doesNotTreatIdLessToolCallAsNotification() throws Exception {
        var request = MAPPER.readTree("""
            {"jsonrpc":"2.0","method":"tools/call","params":{"name":"logs"}}
            """);

        assertThat(OpsMcpHttpMain.isNotification(request)).isFalse();
    }
}
