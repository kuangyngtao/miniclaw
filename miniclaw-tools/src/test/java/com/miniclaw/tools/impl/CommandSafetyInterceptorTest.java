package com.miniclaw.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniclaw.tools.SafetyInterceptor;
import com.miniclaw.tools.schema.ToolCall;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandSafetyInterceptorTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final SafetyInterceptor interceptor = new CommandSafetyInterceptor();

    private ToolCall bashCall(String cmd) {
        ObjectNode args = mapper.createObjectNode().put("command", cmd);
        return new ToolCall("call_1", "bash", args);
    }

    private ToolCall nonBashCall() {
        ObjectNode args = mapper.createObjectNode().put("path", "/tmp/test.java");
        return new ToolCall("call_1", "read", args);
    }

    @Test
    void shouldBlockRmRfRoot() {
        assertThat(interceptor.check(bashCall("rm -rf /"))).isNotNull();
    }

    @Test
    void shouldBlockSudoRm() {
        assertThat(interceptor.check(bashCall("sudo rm /etc/hosts"))).isNotNull();
    }

    @Test
    void shouldBlockChmod777() {
        assertThat(interceptor.check(bashCall("chmod 777 /etc/passwd"))).isNotNull();
    }

    @Test
    void shouldAllowLs() {
        assertThat(interceptor.check(bashCall("ls -la"))).isNull();
    }

    @Test
    void shouldAllowNpmInstall() {
        assertThat(interceptor.check(bashCall("npm install"))).isNull();
    }

    @Test
    void shouldAllowNonBashTool() {
        assertThat(interceptor.check(nonBashCall())).isNull();
    }

    @Test
    void shouldBlockRmRfWildcard() {
        assertThat(interceptor.check(bashCall("rm -rf *"))).isNotNull();
    }

    @Test
    void shouldBlockDdCommand() {
        assertThat(interceptor.check(bashCall("dd if=/dev/zero of=/dev/sda"))).isNotNull();
    }
}
