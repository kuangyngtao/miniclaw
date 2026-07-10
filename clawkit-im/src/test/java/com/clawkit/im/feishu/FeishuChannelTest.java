package com.clawkit.im.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeishuChannelTest {

    @Test
    void parseMessageTextShouldExtractJsonText() {
        String content = "{\"text\":\"hello world\"}";
        assertThat(FeishuChannel.parseMessageText(content)).isEqualTo("hello world");
    }

    @Test
    void parseMessageTextShouldReturnRawOnNonJson() {
        assertThat(FeishuChannel.parseMessageText("plain text")).isEqualTo("plain text");
    }

    @Test
    void parseMessageTextShouldReturnNullForBlank() {
        assertThat(FeishuChannel.parseMessageText(null)).isNull();
        assertThat(FeishuChannel.parseMessageText("")).isNull();
        assertThat(FeishuChannel.parseMessageText("   ")).isNull();
    }

    @Test
    void channelIdShouldBeFeishu() {
        FeishuConfig config = new FeishuConfig("app", "secret", 8080, "/tmp", null);
        FeishuChannel channel = new FeishuChannel(config);
        assertThat(channel.id()).isEqualTo("feishu");
        assertThat(channel.name()).isEqualTo("飞书");
    }

    @Test
    void statusShouldReflectRunningState() {
        FeishuConfig config = new FeishuConfig("app", "secret", 8080, "/tmp", null);
        FeishuChannel channel = new FeishuChannel(config);
        var status = channel.status();
        assertThat(status.id()).isEqualTo("feishu");
        assertThat(status.running()).isFalse();
    }

    @Test
    void handleEventShouldReturnUrlVerificationChallenge() {
        FeishuConfig config = new FeishuConfig("app", "secret", 8080, "/tmp", null);
        FeishuChannel channel = new FeishuChannel(config);
        String body = "{\"type\":\"url_verification\",\"challenge\":\"test123\"}";
        String response = channel.handleEvent(body);
        assertThat(response).contains("test123");
        assertThat(response).contains("challenge");
    }

    @Test
    void handleEventShouldIgnoreNonTextMessages() {
        FeishuConfig config = new FeishuConfig("app", "secret", 8080, "/tmp", null);
        FeishuChannel channel = new FeishuChannel(config);
        String body = "{\"header\":{\"event_type\":\"im.message.receive_v1\",\"event_id\":\"evt1\"},"
            + "\"event\":{\"message\":{\"message_type\":\"image\",\"content\":\"{}\"},"
            + "\"sender\":{\"sender_id\":{\"open_id\":\"user1\"}}}}";
        String response = channel.handleEvent(body);
        assertThat(response).isEqualTo("{\"code\":0}");
    }

    @Test
    void handleEventShouldDedupDuplicateEventIds() {
        FeishuConfig config = new FeishuConfig("app", "secret", 8080, "/tmp", null);
        FeishuChannel channel = new FeishuChannel(config);
        String body = "{\"header\":{\"event_type\":\"im.message.receive_v1\",\"event_id\":\"evt_dup\"},"
            + "\"event\":{\"message\":{\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"hi\\\"}\"},"
            + "\"sender\":{\"sender_id\":{\"open_id\":\"user1\"}}}}";

        String first = channel.handleEvent(body);
        String second = channel.handleEvent(body);
        assertThat(first).isEqualTo("{\"code\":0}");
        assertThat(second).isEqualTo("{\"code\":0}");
    }
}
