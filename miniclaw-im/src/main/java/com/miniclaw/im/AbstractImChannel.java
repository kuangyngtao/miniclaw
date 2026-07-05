package com.miniclaw.im;

import com.miniclaw.engine.AgentStateEvent;
import com.miniclaw.engine.PermissionMode;
import com.miniclaw.engine.impl.AgentEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractImChannel implements ImChannel {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected static final ObjectMapper JSON = new ObjectMapper();

    protected AgentEngine engine;
    protected volatile Consumer<String> onImInput;
    protected volatile String linkedUserId;
    protected volatile boolean running;

    // ─── CLI mirror state ─────────────────────────────────────────────

    private volatile MirrorSession mirrorSession;

    private static class MirrorSession {
        ReplyStream stream;
        Consumer<String> tokenListener;
        Consumer<AgentStateEvent> stateListener;
    }

    // ─── ReplyStream: platform-specific streaming strategy ────────────

    protected interface ReplyStream {
        void onToken(String token);
        void onStateChange(AgentStateEvent event);
        void finalize(String fullContent);
        void onError(String errorMessage);
    }

    // ─── Subclass hooks ───────────────────────────────────────────────

    protected abstract ReplyStream startReply(String userId) throws ImException;

    protected abstract void sendBusyReply(String userId) throws ImException;

    protected abstract void sendUserMessage(String userId, String text) throws ImException;

    protected String busyText() { return "正在处理中，请稍候..."; }

    protected String thinkingText() { return "○ thinking..."; }

    // ─── Engine bridge template ───────────────────────────────────────

    protected final void handleIncomingMessage(String userId, String text) {
        linkedUserId = userId;
        if (onImInput != null) onImInput.accept(text);

        if (engine == null) {
            log.warn("Ignoring IM message because AgentEngine is not attached");
            return;
        }

        if (!engine.tryAcquire()) {
            try {
                sendBusyReply(userId);
            } catch (ImException e) {
                log.warn("Failed to send busy reply: {}", e.getMessage());
            }
            return;
        }

        ReplyStream stream = null;
        PermissionMode savedMode = engine.permissionMode();
        try {
            stream = startReply(userId);

            Consumer<String> tokenListener = stream::onToken;
            Consumer<AgentStateEvent> stateListener = stream::onStateChange;
            engine.addOnTokenListener(tokenListener);
            engine.onStateChange(stateListener);

            engine.setPermissionMode(PermissionMode.AUTO);

            String result;
            try {
                result = engine.run(text);
            } catch (Exception e) {
                log.error("Engine error: {}", e.getMessage(), e);
                stream.onError("[A-002] " + e.getMessage());
                return;
            } finally {
                engine.setPermissionMode(savedMode);
                engine.removeOnTokenListener(tokenListener);
                engine.removeOnStateChangeListener(stateListener);
            }

            String finalContent = (!isNullOrBlank(result))
                ? result : "";
            stream.finalize(finalContent.isBlank() ? "No output." : finalContent);

        } catch (ImException e) {
            log.error("IM error handling message: {}", e.getMessage(), e);
        } finally {
            engine.release();
        }
    }

    // ─── CLI mirror ───────────────────────────────────────────────────

    @Override
    public void mirrorToIm(String userInput) {
        if (linkedUserId == null) return;
        try {
            sendUserMessage(linkedUserId, userInput);
            MirrorSession ms = new MirrorSession();
            mirrorSession = ms;

            ms.stream = startReply(linkedUserId);
            ms.tokenListener = ms.stream::onToken;
            ms.stateListener = ms.stream::onStateChange;
            engine.addOnTokenListener(ms.tokenListener);
            engine.onStateChange(ms.stateListener);
        } catch (ImException e) {
            log.warn("Failed to start IM mirror: {}", e.getMessage());
            mirrorSession = null;
        }
    }

    @Override
    public void finalizeMirror(String result) {
        MirrorSession ms = mirrorSession;
        if (ms == null) return;
        try {
            engine.removeOnTokenListener(ms.tokenListener);
            engine.removeOnStateChangeListener(ms.stateListener);
            String content = (!isNullOrBlank(result)) ? result : "";
            ms.stream.finalize(content.isBlank() ? "No output." : content);
        } catch (Exception e) {
            log.warn("Failed to finalize IM mirror: {}", e.getMessage());
        } finally {
            mirrorSession = null;
        }
    }

    // ─── State → status mapping ───────────────────────────────────────

    protected String mapStateToStatus(AgentStateEvent event) {
        return switch (event.state()) {
            case IDLE -> "○ 准备中…";
            case PLANNING -> "○ 规划中…";
            case REASONING -> "○ 思考中…";
            case EXECUTING -> {
                @SuppressWarnings("unchecked")
                var toolNames = (java.util.List<String>) event.metadata()
                    .getOrDefault("toolNames", java.util.List.of());
                String firstTool = toolNames.isEmpty() ? "" : toolNames.get(0);
                yield firstTool.isEmpty() ? "○ 执行中…" : "○ 执行 " + firstTool + "…";
            }
            case REPLYING -> "○ 生成回复…";
            case INTERRUPTED -> "已中断";
            case ERROR -> {
                String err = (String) event.metadata().getOrDefault("error", "");
                yield err.isEmpty() ? "执行出错" : "出错: " + err;
            }
        };
    }

    // ─── Shared helpers ───────────────────────────────────────────────

    @Override
    public boolean isRunning() { return running; }

    @Override
    public void setEngine(AgentEngine engine) { this.engine = engine; }

    @Override
    public void setOnImInput(Consumer<String> callback) { this.onImInput = callback; }

    protected static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

    // ─── Shared output utilities ───────────────────────────────────────

    protected static String simplifyMarkdown(String text) {
        if (text == null || text.isBlank()) return text;

        text = text.replaceAll("(?m)^```\\w*\\s*$", "");
        text = text.replaceAll("```", "");
        text = text.replaceAll("(?m)^#{1,6}\\s+", "");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("(?<!\\w)\\*(.+?)\\*(?!\\w)", "$1");
        text = text.replaceAll("`(.+?)`", "$1");
        text = text.replaceAll("\n{3,}", "\n\n");

        return text.strip();
    }

    protected static java.util.List<String> chunkText(String text, int maxLen) {
        var chunks = new java.util.ArrayList<String>();
        if (text.length() <= maxLen) {
            chunks.add(text);
            return chunks;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + maxLen / 2) end = nl;
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && text.charAt(start) == '\n') start++;
        }
        return chunks;
    }
}
