package com.miniclaw.im.weixin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeixinChannelTest {

    @Test
    void channelIdShouldBeWeixin() {
        WeixinConfig config = new WeixinConfig("app", "secret", "/tmp");
        WeixinChannel channel = new WeixinChannel(config);
        assertThat(channel.id()).isEqualTo("weixin");
        assertThat(channel.name()).isEqualTo("微信");
    }

    @Test
    void statusShouldReflectRunningState() {
        WeixinConfig config = new WeixinConfig("app", "secret", "/tmp");
        WeixinChannel channel = new WeixinChannel(config);
        var status = channel.status();
        assertThat(status.id()).isEqualTo("weixin");
        assertThat(status.running()).isFalse();
    }

    @Test
    void isRunningShouldBeFalseInitially() {
        WeixinConfig config = new WeixinConfig("app", "secret", "/tmp");
        WeixinChannel channel = new WeixinChannel(config);
        assertThat(channel.isRunning()).isFalse();
    }

    @Test
    void stopShouldBeNoopWhenNotRunning() {
        WeixinConfig config = new WeixinConfig("app", "secret", "/tmp");
        WeixinChannel channel = new WeixinChannel(config);
        channel.stop(); // should not throw
        assertThat(channel.isRunning()).isFalse();
    }
}
