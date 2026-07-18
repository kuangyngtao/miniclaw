package com.clawkit.engine.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.clawkit.engine.SessionMeta;
import com.clawkit.engine.SessionError;
import com.clawkit.engine.SessionStoreException;
import com.clawkit.tools.schema.Message;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-based session persistence using JSON under ~/.clawkit/sessions/.
 * Sessions are stored as <id>.json, with an index at index.json.
 */
public class FileSessionStore implements com.clawkit.engine.SessionStore {

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
            throw new SessionStoreException(SessionError.IO_ERROR, null,
                "Cannot create sessions directory: " + this.basePath, e);
        }
    }

    // ── public API ──

    public synchronized SessionMeta save(String id, String name, List<Message> messages, String summary) {
        Instant now = Instant.now();
        String firstMsg = extractFirstUserMessage(messages);
        SessionMeta meta = new SessionMeta(id, name, now, now, messages.size(), firstMsg, summary);

        // write session file
        SessionFile sf = new SessionFile(meta, messages, java.util.Map.of("summary", summary != null ? summary : ""));
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

    @Deprecated
    public List<Message> loadMessages(String id) {
        return load(id).map(com.clawkit.engine.SessionDocument::messages)
            .orElseThrow(() -> new SessionStoreException(SessionError.NOT_FOUND, id,
                "[H-003] Session not found: " + id));
    }

    /** Legacy v0: no schemaVersion field, read directly */
    private List<Message> loadLegacy(String raw, String id) throws IOException {
        try {
            // v0 format: { "meta": {...}, "messages": [...] }
            var node = mapper.readTree(raw);
            var messagesNode = node.get("messages");
            if (messagesNode == null || !messagesNode.isArray()) {
                throw new IOException("Invalid legacy session format");
            }
            @SuppressWarnings("unchecked")
            List<Message> messages = mapper.convertValue(
                messagesNode,
                mapper.getTypeFactory().constructCollectionType(List.class, Message.class));
            log.info("[Session] loaded legacy v0: {} ({} messages)", id, messages.size());
            return messages;
        } catch (JsonProcessingException e) {
            throw new IOException("[H-003] Corrupted session file: " + id, e);
        }
    }

    public synchronized void delete(String id) {
        Path path = sessionPath(id);
        try {
            boolean removed = Files.deleteIfExists(path);
            if (!removed) {
                throw new SessionStoreException(SessionError.NOT_FOUND, id,
                    "[H-003] Session not found: " + id);
            }
        } catch (IOException e) {
            throw new SessionStoreException(SessionError.IO_ERROR, id,
                "[H-003] Cannot delete session: " + id, e);
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
            writeAtomic(basePath.resolve(INDEX_FILE), new IndexFile(sessions));
        } catch (IOException e) {
            throw new SessionStoreException(SessionError.IO_ERROR, null,
                "[H-003] Cannot write session index", e);
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
        writeAtomic(path, obj);
    }

    /** 原子写入：先写临时文件，再 atomic move */
    private void writeAtomic(Path target, Object obj) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            log.warn("Atomic move not supported, using replace: {}", target);
            try {
                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
                Files.writeString(target, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e2) {
                throw new SessionStoreException(SessionError.IO_ERROR, sessionIdFrom(target),
                    "[H-003] Cannot write: " + target, e2);
            }
        } catch (IOException e) {
            throw new SessionStoreException(SessionError.IO_ERROR, sessionIdFrom(target),
                "[H-003] Cannot write session file: " + target, e);
        }
    }

    private void updateIndex(SessionMeta meta) {
        List<SessionMeta> index = loadIndex();
        index.removeIf(m -> m.id().equals(meta.id()));
        index.add(meta);
        index.sort(Comparator.comparing(SessionMeta::updatedAt).reversed());
        writeIndex(index);
    }

    private static String extractFirstUserMessage(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.role() == com.clawkit.tools.schema.Role.USER
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

    // ── SessionStore interface ──────────────────────────────────────

    @Override
    public Optional<com.clawkit.engine.SessionDocument> load(String id) {
        Path path = sessionPath(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path);
            if (!raw.contains("\"schemaVersion\"")) {
                List<Message> msgs;
                try {
                    msgs = loadLegacy(raw, id);
                } catch (IOException e) {
                    throw new SessionStoreException(SessionError.CORRUPTED_JSON, id,
                        "[H-003] Corrupted legacy session: " + id, e);
                }
                Instant now = Instant.now();
                return Optional.of(new com.clawkit.engine.SessionDocument(
                    SESSION_SCHEMA_VERSION, id, "", now, now, msgs, java.util.Map.of()));
            }
            SessionFile sf = mapper.readValue(raw, SessionFile.class);
            if (sf.schemaVersion > SESSION_SCHEMA_VERSION) {
                throw new SessionStoreException(SessionError.UNSUPPORTED_VERSION, id,
                    "[H-003] Unsupported session version: " + sf.schemaVersion + " for " + id);
            }
            SessionMeta meta = sf.meta != null
                ? sf.meta
                : new SessionMeta(id, "", Instant.now(), Instant.now(), sf.messages.size(), "", "");
            return Optional.of(new com.clawkit.engine.SessionDocument(
                sf.schemaVersion, meta.id(), meta.name(), meta.createdAt(), meta.updatedAt(),
                sf.messages != null ? sf.messages : List.of(),
                sf.metadata != null ? sf.metadata : java.util.Map.of("summary", meta.summary())));
        } catch (SessionStoreException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new SessionStoreException(SessionError.CORRUPTED_JSON, id,
                "[H-003] Corrupted session: " + id, e);
        } catch (IOException e) {
            throw new SessionStoreException(SessionError.IO_ERROR, id,
                "[H-003] Cannot read session: " + id, e);
        }
    }

    @Override
    public synchronized void save(com.clawkit.engine.SessionDocument doc) {
        // 保留传入的 createdAt/updatedAt/metadata
        String firstMsg = extractFirstUserMessage(doc.messages());
        SessionMeta meta = new SessionMeta(doc.sessionId(), doc.name(),
            doc.createdAt() != null ? doc.createdAt() : Instant.now(),
            doc.updatedAt() != null ? doc.updatedAt() : Instant.now(),
            doc.messages().size(), firstMsg,
            doc.metadata() != null ? doc.metadata().getOrDefault("summary", "") : "");
        SessionFile sf = new SessionFile(meta, doc.messages(), doc.metadata());
        writeAtomic(sessionPath(doc.sessionId()), sf);
        updateIndex(meta);
        log.info("[Session] saved via interface: {} ({} messages)", doc.sessionId(), doc.messages().size());
    }

    @Override
    public List<com.clawkit.engine.SessionMeta> list() {
        return listSessions();
    }

    // ── internal records for JSON structure ──

    /** Current session schema version persisted to disk */
    static final int SESSION_SCHEMA_VERSION = 1;

    record SessionFile(int schemaVersion, SessionMeta meta, List<Message> messages,
                       java.util.Map<String, String> metadata) {
        SessionFile(SessionMeta meta, List<Message> messages) {
            this(SESSION_SCHEMA_VERSION, meta, messages, java.util.Map.of("summary", meta.summary()));
        }
        SessionFile(SessionMeta meta, List<Message> messages, java.util.Map<String, String> metadata) {
            this(SESSION_SCHEMA_VERSION, meta, messages, metadata == null ? java.util.Map.of() : metadata);
        }
    }

    record IndexFile(List<SessionMeta> sessions) {}

    private static String sessionIdFrom(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : null;
    }
}
