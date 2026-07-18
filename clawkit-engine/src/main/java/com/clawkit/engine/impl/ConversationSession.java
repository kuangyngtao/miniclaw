package com.clawkit.engine.impl;

import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionService;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.Role;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns factual conversation history and its persistence boundary. */
final class ConversationSession {
    private static final Logger log = LoggerFactory.getLogger(ConversationSession.class);
    private final InMemorySessionHistory history = new InMemorySessionHistory();
    private volatile SessionService service;
    private boolean autoSaved;

    List<Message> messages() { return history.messages(); }
    void append(Message message) { history.append(message); }
    void replace(List<Message> messages) { history.replace(messages); }
    boolean isEmpty() { return history.isEmpty(); }
    int size() { return history.size(); }
    void setService(SessionService service) { this.service = service; }
    boolean available() { return service != null; }

    void clear() {
        history.clear();
        autoSaved = false;
    }

    String save(String name, Predicate<Message> persistable) {
        if (service == null) return "[H-003] Session service not available.";
        List<Message> clean = filtered(persistable);
        if (clean.isEmpty()) return "[H-001] No conversation to save — start a conversation first.";
        SessionMeta meta = service.save(name, clean);
        return String.format("Session saved: %s (%s, %d messages)",
            meta.id(), meta.name(), meta.messageCount());
    }

    void autoSave(Predicate<Message> persistable) {
        if (service == null || autoSaved || history.isEmpty()) return;
        try {
            List<Message> clean = filtered(persistable);
            if (clean.isEmpty()) return;
            service.save(autoName(), clean);
            autoSaved = true;
            log.info("[Session] auto-saved {} factual messages", clean.size());
        } catch (Exception e) {
            log.warn("[Session] auto-save failed: {}", e.getMessage());
        }
    }

    List<Message> load(String id, Predicate<Message> persistable) {
        requireService();
        return service.load(id).stream().filter(persistable).toList();
    }
    List<SessionMeta> list() { return service == null ? List.of() : service.list(); }
    void delete(String id) { requireService(); service.delete(id); }

    List<Message> relatedContext(String query) {
        if (service == null || query == null || query.isBlank()) return List.of();
        try {
            List<SessionMeta> related = service.search(query);
            if (related.isEmpty()) return List.of();
            StringBuilder text = new StringBuilder("[Related Past Sessions]\n");
            related.stream().limit(3).forEach(meta -> {
                text.append("- (").append(meta.updatedAt().toString(), 0, 10).append(") ")
                    .append(meta.name());
                if (meta.summary() != null && !meta.summary().isBlank()) {
                    text.append(": ").append(meta.summary());
                }
                text.append('\n');
            });
            return List.of(Message.system(text.toString().stripTrailing()));
        } catch (Exception e) {
            log.warn("[Session] related recall failed: {}", e.getMessage());
            return List.of();
        }
    }

    String searchContext(String query) {
        if (service == null) return "[H-003] Session service not available.";
        if (query == null || query.isBlank()) {
            return "No search query provided. Please specify what to search for in past sessions.";
        }
        List<SessionMeta> matches = service.search(query);
        if (matches.isEmpty()) return "No past sessions matching \"" + query + "\".";
        StringBuilder out = new StringBuilder("Found ").append(matches.size())
            .append(" matching session(s):\n\n");
        for (SessionMeta meta : matches) {
            String updated = meta.updatedAt().toString().replace("T", " ").substring(0, 16);
            out.append("- **").append(meta.id()).append("**: ").append(meta.name())
                .append(" (").append(meta.messageCount()).append(" msgs, ").append(updated).append(")\n");
            if (meta.summary() != null && !meta.summary().isBlank()) {
                out.append("  ").append(meta.summary()).append('\n');
            }
            out.append('\n');
        }
        return out.append("To load a session, tell the user to use `/session load <id>`.").toString();
    }

    private List<Message> filtered(Predicate<Message> persistable) {
        return history.messages().stream().filter(persistable).toList();
    }
    private String autoName() {
        return history.messages().stream()
            .filter(m -> m.role() == Role.USER && m.content() != null && !m.content().isBlank())
            .map(m -> m.content().strip())
            .map(c -> "[auto] " + (c.length() > 50 ? c.substring(0, 50) + "..." : c))
            .findFirst().orElse("[auto] " + Instant.now().toString().substring(0, 16));
    }
    private void requireService() {
        if (service == null) throw new UnsupportedOperationException("Session service not available");
    }
}
