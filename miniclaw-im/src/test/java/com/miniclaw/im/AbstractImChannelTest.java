package com.miniclaw.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.miniclaw.engine.AgentState;
import com.miniclaw.engine.AgentStateEvent;
import com.miniclaw.engine.impl.AgentEngine;
import java.util.List;
import java.util.Map;
import com.miniclaw.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbstractImChannelTest {

    private TestChannel channel;
    private AgentEngine engine;
    private String lastIncomingUserId;
    private String lastIncomingText;

    // Minimal test channel
    static class TestChannel extends AbstractImChannel {
        final List<String> busyReplies = new java.util.ArrayList<>();
        final List<String> startedReplies = new java.util.ArrayList<>();
        final List<String> userMessages = new java.util.ArrayList<>();

        @Override public String id() { return "test"; }
        @Override public String name() { return "Test"; }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public ImChannelStatus status() {
            return new ImChannelStatus("test", "Test", running, linkedUserId, "");
        }

        @Override
        protected ReplyStream startReply(String userId) throws ImException {
            startedReplies.add(userId);
            return new ReplyStream() {
                final StringBuilder tokens = new StringBuilder();
                String lastState = "";
                String lastError = "";
                String finalContent = "";
                @Override public void onToken(String token) { tokens.append(token); }
                @Override public void onStateChange(AgentStateEvent event) { lastState = event.state().name(); }
                @Override public void finalize(String fullContent) { finalContent = fullContent; }
                @Override public void onError(String errorMessage) { lastError = errorMessage; }
            };
        }

        @Override
        protected void sendBusyReply(String userId) { busyReplies.add(userId); }
        @Override
        protected void sendUserMessage(String userId, String text) { userMessages.add(userId + ":" + text); }

        // Expose handleIncomingMessage for testing
        void receiveMessage(String userId, String text) {
            handleIncomingMessage(userId, text);
        }
    }

    @BeforeEach
    void setUp() {
        // Create a minimal engine (no provider needed for tryAcquire/release pattern)
        engine = new AgentEngine(null, new ToolRegistry(), "/tmp",
            com.miniclaw.engine.ThinkingMode.OFF, null, null, null);
        channel = new TestChannel();
        channel.setEngine(engine);
        channel.setOnImInput((text) -> {
            lastIncomingUserId = channel.linkedUserId;
            lastIncomingText = text;
        });
    }

    @Test
    void handleIncomingMessageShouldSetLinkedUserAndNotifyCallback() {
        channel.receiveMessage("user1", "hello");

        assertThat(channel.linkedUserId).isEqualTo("user1");
        assertThat(lastIncomingText).isEqualTo("hello");
    }

    @Test
    void handleIncomingMessageShouldSendBusyWhenEngineLocked() {
        engine.tryAcquire(); // lock the engine
        channel.receiveMessage("user1", "hello");
        engine.release();

        assertThat(channel.busyReplies).contains("user1");
    }

    @Test
    void handleIncomingMessageShouldStartReply() {
        channel.receiveMessage("user1", "hello");

        assertThat(channel.startedReplies).contains("user1");
    }

    @Test
    void mirrorToImShouldBeNoopWhenNoLinkedUser() {
        channel.mirrorToIm("hello");
        assertThat(channel.userMessages).isEmpty();
    }

    @Test
    void mirrorToImShouldSendUserMessage() {
        channel.linkedUserId = "user1";
        channel.mirrorToIm("hello");

        assertThat(channel.userMessages).hasSize(1);
        assertThat(channel.userMessages.get(0)).contains("user1");
        assertThat(channel.userMessages.get(0)).contains("hello");
    }

    @Test
    void finalizeMirrorShouldBeNoopWhenNoMirrorSession() {
        assertDoesNotThrow(() -> channel.finalizeMirror("result"));
    }

    @Test
    void isRunningShouldReturnFalseBeforeStart() {
        assertThat(channel.isRunning()).isFalse();
    }

    @Test
    void isRunningShouldReturnTrueAfterStart() {
        channel.start();
        assertThat(channel.isRunning()).isTrue();
    }

    // ─── mapStateToStatus ────────────────────────────────────────────

    @Test
    void mapStateToStatusShouldCoverAllStates() {
        for (AgentState state : AgentState.values()) {
            AgentStateEvent event = new AgentStateEvent(state, 1, Map.of());
            String status = channel.mapStateToStatus(event);
            assertThat(status).isNotNull().isNotBlank();
        }
    }

    @Test
    void mapStateToStatusExecutingShouldShowToolName() {
        AgentStateEvent event = new AgentStateEvent(AgentState.EXECUTING, 1,
            Map.of("toolNames", List.of("bash")));
        String status = channel.mapStateToStatus(event);
        assertThat(status).contains("bash");
    }

    @Test
    void mapStateToStatusErrorShouldShowErrorMessage() {
        AgentStateEvent event = new AgentStateEvent(AgentState.ERROR, 1,
            Map.of("error", "connection refused"));
        String status = channel.mapStateToStatus(event);
        assertThat(status).contains("connection refused");
    }
}
