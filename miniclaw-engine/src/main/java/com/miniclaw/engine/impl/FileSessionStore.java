package com.miniclaw.engine.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miniclaw.engine.SessionMeta;
import com.miniclaw.tools.schema.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-based session persistence using JSON under ~/.miniclaw/sessions/.
 * Sessions are stored as <id>.json, with an index at index.json.
 */
public class FileSessionStore {

    private static final Logger log = LoggerFactory.getLogger(FileSessionStore.class);
    private static final String INDEX_FILE = "index.json";

    private final Path basePath;
    private final ObjectMapper mapper;

    public FileSessionStore(Path basePath) {
        this.basePath = basePath.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create sessions directory: " + this.basePath, e);
        }
    }

    // ── public API ──

    public SessionMeta save(String id, String name, List<Message> messages, String summary) {
        Instant now = Instant.now();
        String firstMsg = extractFirstUserMessage(messages);
        SessionMeta meta = new SessionMeta(id, name, now, now, messages.size(), firstMsg, summary);

        // write session file
        SessionFile sf = new SessionFile(meta, messages);
        writeJson(sessionPath(id), sf);

        // update index
        List<SessionMeta> index = loadIndex();
        index.removeIf(m -> m.id().equals(id));
        index.add(meta);
        index.sort(Comparator.comparing(SessionMeta::updatedAt).reversed());
        writeIndex(index);

        log.info("[Session] saved: {} ({} messages)", id, messages.size());
        return meta;
    }

    public List<Message> load(String id) {
        Path path = sessionPath(id);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("[H-003] Session not found: " + id);
        }
        try {
            SessionFile sf = mapper.readValue(path.toFile(), SessionFile.class);
            log.info("[Session] loaded: {} ({} messages)", id, sf.messages.size());
            return sf.messages;
        } catch (IOException e) {
            throw new UncheckedIOException("[H-003] Cannot read session: " + id, e);
        }
    }

    public void delete(String id) {
        Path path = sessionPath(id);
        try {
            boolean removed = Files.deleteIfExists(path);
            if (!removed) {
                throw new IllegalArgumentException("[H-003] Session not found: " + id);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("[H-003] Cannot delete session: " + id, e);
        }

        List<SessionMeta> index = loadIndex();
        index.removeIf(m -> m.id().equals(id));
        writeIndex(index);
        log.info("[Session] deleted: {}", id);
    }

    public List<SessionMeta> listSessions() {
        return loadIndex();
    }

    public long fileSize(String id) {
        Path path = sessionPath(id);
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            log.warn("Cannot get file size for session: {}", id, e);
            return 0;
        }
    }

    public SessionMeta getMeta(String id) {
        Path path = sessionPath(id);
        if (!Files.exists(path)) return null;
        try {
            SessionFile sf = mapper.readValue(path.toFile(), SessionFile.class);
            return sf.meta;
        } catch (IOException e) {
            log.warn("Cannot read session meta: {}", id, e);
            return null;
        }
    }

    // ── index I/O ──

    private List<SessionMeta> loadIndex() {
        Path p = basePath.resolve(INDEX_FILE);
        try {
            String raw = Files.readString(p);
            if (raw.isBlank()) return new ArrayList<>();
            IndexFile idx = mapper.readValue(raw, IndexFile.class);
            return idx.sessions != null ? new ArrayList<>(idx.sessions) : new ArrayList<>();
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (IOException e) {
            log.warn("[H-002] Session index corrupted, resetting: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeIndex(List<SessionMeta> sessions) {
        try {
            IndexFile idx = new IndexFile(sessions);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(idx);
            Files.writeString(basePath.resolve(INDEX_FILE), json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("[H-003] Cannot write session index", e);
        }
    }

    // ── helpers ──

    private Path sessionPath(String id) {
        Path p = basePath.resolve(id + ".json").normalize();
        if (!p.startsWith(basePath)) {
            throw new SecurityException("Path traversal detected: " + id);
        }
        return p;
    }

    private void writeJson(Path path, Object obj) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            Files.writeString(path, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("[H-003] Cannot write session file: " + path, e);
        }
    }

    private static String extractFirstUserMessage(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.role() == com.miniclaw.tools.schema.Role.USER
                && msg.content() != null && !msg.content().isBlank()) {
                String c = msg.content().strip();
                return c.length() > 120 ? c.substring(0, 120) + "..." : c;
            }
        }
        return "";
    }

    public static String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ── internal records for JSON structure ──

    record SessionFile(SessionMeta meta, List<Message> messages) {}

    record IndexFile(List<SessionMeta> sessions) {}
}
