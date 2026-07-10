package com.clawkit.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.clawkit.tools.SafetyInterceptor;
import com.clawkit.tools.schema.ToolCall;
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

    @Test
    void shouldBlockPkexec() {
        assertThat(interceptor.check(bashCall("pkexec rm /etc/hosts"))).isNotNull();
    }

    @Test
    void shouldBlockDoas() {
        assertThat(interceptor.check(bashCall("doas rm /etc/hosts"))).isNotNull();
    }

    @Test
    void shouldBlockChmodRecursive777() {
        assertThat(interceptor.check(bashCall("chmod -R 777 /var/www"))).isNotNull();
    }

    @Test
    void shouldBlockChmodOctal777() {
        assertThat(interceptor.check(bashCall("chmod 0777 /etc/passwd"))).isNotNull();
    }

    @Test
    void shouldAllowChmodNormal() {
        assertThat(interceptor.check(bashCall("chmod 644 /var/www"))).isNull();
    }

    @Test
    void shouldAllowChmod755() {
        assertThat(interceptor.check(bashCall("chmod 755 /usr/local/bin/script"))).isNull();
    }

    @Test
    void shouldBlockShutdown() {
        assertThat(interceptor.check(bashCall("shutdown -h now"))).isNotNull();
    }

    @Test
    void shouldBlockReboot() {
        assertThat(interceptor.check(bashCall("reboot"))).isNotNull();
    }

    @Test
    void shouldBlockHalt() {
        assertThat(interceptor.check(bashCall("halt"))).isNotNull();
    }

    @Test
    void shouldBlockPoweroff() {
        assertThat(interceptor.check(bashCall("poweroff"))).isNotNull();
    }

    @Test
    void shouldBlockInit0() {
        assertThat(interceptor.check(bashCall("init 0"))).isNotNull();
    }

    @Test
    void shouldBlockInit6() {
        assertThat(interceptor.check(bashCall("init 6"))).isNotNull();
    }
}
