package com.clawkit.cli.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YAML-file-backed {@link RemoteTargetStore}.
 *
 * <p>Supports mixed v1 ({@link RemoteTargetConfig}) and v2
 * ({@link RemoteTargetRegistration}) targets in the same store.
 * Uses atomic write (temp file + rename) to prevent corruption.
 * Thread-safe via read-write lock.
 *
 * <p>Design: REMOTE-0 §6.3, §10.1; PRODUCT-1 §12.
 */
public class FileRemoteTargetStore implements RemoteTargetStore {

    private static final Logger log = LoggerFactory.getLogger(FileRemoteTargetStore.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .registerModule(new JavaTimeModule());

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, RemoteTargetConfig> v1Targets = new LinkedHashMap<>();
    private final Map<String, RemoteTargetRegistration> v2Targets = new LinkedHashMap<>();
    private final Set<String> activeTargets = ConcurrentHashMap.newKeySet();

    public FileRemoteTargetStore(Path storeFile) {
        this.storeFile = storeFile;
        loadFromDisk();
    }

    // ── Read operations ────────────────────────────────────────────────

    @Override
    public List<String> list() {
        lock.readLock().lock();
        try {
            List<String> ids = new ArrayList<>();
            ids.addAll(v1Targets.keySet());
            ids.addAll(v2Targets.keySet());
            return List.copyOf(ids);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<RemoteTargetConfig> get(String targetId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(v1Targets.get(targetId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<RemoteTargetRegistration> getRegistration(String targetId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(v2Targets.get(targetId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Check if a target exists (v1 or v2). */
    public boolean exists(String targetId) {
        lock.readLock().lock();
        try {
            return v1Targets.containsKey(targetId) || v2Targets.containsKey(targetId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isActive(String targetId) {
        return activeTargets.contains(targetId);
    }

    // ── Write operations ──────────────────────────────────────────────

    @Override
    public void add(String targetId, Path configFile, boolean replace) {
        RemoteTargetConfig config;
        try {
            config = RemoteTargetConfig.parse(configFile);
        } catch (RemoteTargetConfig.ConfigValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new RemoteTargetConfig.ConfigValidationException(targetId,
                "failed to read config file: " + e.getMessage());
        }

        if (!targetId.equals(config.targetId())) {
            throw new RemoteTargetConfig.ConfigValidationException(targetId,
                "targetId mismatch: command says '" + targetId
                + "' but config says '" + config.targetId() + "'");
        }

        lock.writeLock().lock();
        try {
            if ((v1Targets.containsKey(targetId) || v2Targets.containsKey(targetId))
                && !replace) {
                throw new TargetAlreadyExistsException(targetId);
            }
            v1Targets.put(targetId, config);
            v2Targets.remove(targetId); // replace v2 if exists
            persist();
            log.info("[remote-store] added v1 target: {}", targetId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void add(RemoteTargetRegistration registration, boolean replace) {
        String targetId = registration.targetId();
        lock.writeLock().lock();
        try {
            if ((v1Targets.containsKey(targetId) || v2Targets.containsKey(targetId))
                && !replace) {
                throw new TargetAlreadyExistsException(targetId);
            }
            v2Targets.put(targetId, registration);
            v1Targets.remove(targetId); // replace v1 if exists
            persist();
            log.info("[remote-store] added v2 target: {}", targetId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String targetId) {
        lock.writeLock().lock();
        try {
            boolean inV1 = v1Targets.containsKey(targetId);
            boolean inV2 = v2Targets.containsKey(targetId);
            if (!inV1 && !inV2) {
                throw new TargetNotFoundException(targetId);
            }
            if (isActive(targetId)) {
                throw new TargetInUseException(targetId);
            }
            v1Targets.remove(targetId);
            v2Targets.remove(targetId);
            persist();
            log.info("[remote-store] removed target: {}", targetId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void markActive(String targetId) {
        activeTargets.add(targetId);
    }

    @Override
    public void markInactive(String targetId) {
        activeTargets.remove(targetId);
    }

    // ── Persistence ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!Files.isRegularFile(storeFile)) {
            log.debug("[remote-store] no store file at {}, starting empty", storeFile);
            return;
        }
        try {
            String content = Files.readString(storeFile);
            if (content.isBlank()) return;

            Map<String, Object> raw = YAML.readValue(storeFile.toFile(), Map.class);
            if (raw == null) return;

            List<Map<String, Object>> targetList = (List<Map<String, Object>>) raw.get("targets");
            if (targetList == null) return;

            for (Map<String, Object> entry : targetList) {
                try {
                    Object versionObj = entry.get("schemaVersion");
                    int version = versionObj instanceof Number n
                        ? n.intValue() : 1;

                    if (version == 2) {
                        loadV2Entry(entry);
                    } else {
                        loadV1Entry(entry);
                    }
                } catch (Exception e) {
                    Object id = entry.get("targetId");
                    log.warn("[remote-store] skipping invalid target entry '{}': {}",
                        id != null ? id : "<unknown>", e.getMessage());
                }
            }
            log.info("[remote-store] loaded {} target(s) (v1={}, v2={}) from {}",
                v1Targets.size() + v2Targets.size(),
                v1Targets.size(), v2Targets.size(), storeFile);
        } catch (IOException e) {
            log.warn("[remote-store] failed to load store file: {}", e.getMessage());
        }
    }

    private void loadV1Entry(Map<String, Object> entry) throws Exception {
        String entryYaml = YAML.writeValueAsString(entry);
        RemoteTargetConfig config = YAML.readValue(entryYaml, RemoteTargetConfig.class);
        config.validate();
        v1Targets.put(config.targetId(), config);
    }

    @SuppressWarnings("unchecked")
    private void loadV2Entry(Map<String, Object> entry) {
        String targetId = (String) entry.get("targetId");
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }

        Map<String, Object> connMap = (Map<String, Object>) entry.get("connection");
        if (connMap == null) {
            throw new IllegalArgumentException("connection is required");
        }

        String type = (String) connMap.get("type");
        RemoteConnectionReference connRef;
        if ("openssh-alias".equals(type)) {
            String alias = (String) connMap.get("alias");
            String remoteUser = (String) connMap.get("remoteUser");
            if (alias == null || remoteUser == null) {
                throw new IllegalArgumentException("alias and remoteUser are required");
            }
            String sshConfigFile = (String) connMap.get("sshConfigFile");
            Path configPath = sshConfigFile != null ? Path.of(sshConfigFile) : null;
            connRef = new OpenSshAliasReference(alias, remoteUser, configPath);
        } else {
            throw new IllegalArgumentException("unsupported connection type: " + type);
        }

        String profileManifestId = (String) entry.get("profileManifestId");
        if (profileManifestId == null || profileManifestId.isBlank()) {
            throw new IllegalArgumentException("profileManifestId is required");
        }

        // Verify manifest is known
        if (!RemoteProfileCatalog.isKnown(profileManifestId)) {
            throw new IllegalArgumentException("unknown profile manifest: " + profileManifestId);
        }

        var reg = new RemoteTargetRegistration(2, targetId, connRef, profileManifestId);
        v2Targets.put(targetId, reg);
    }

    private void persist() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> targetList = new ArrayList<>();

            // Serialize v1 entries
            for (RemoteTargetConfig config : v1Targets.values()) {
                String yaml = YAML.writeValueAsString(config);
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = YAML.readValue(yaml, Map.class);
                targetList.add(entry);
            }

            // Serialize v2 entries
            for (RemoteTargetRegistration reg : v2Targets.values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("schemaVersion", reg.schemaVersion());
                entry.put("targetId", reg.targetId());

                Map<String, Object> connMap = new LinkedHashMap<>();
                connMap.put("type", reg.connection().type());
                switch (reg.connection()) {
                    case OpenSshAliasReference alias -> {
                        connMap.put("alias", alias.alias());
                        connMap.put("remoteUser", alias.remoteUser());
                        if (alias.sshConfigFile() != null) {
                            connMap.put("sshConfigFile",
                                alias.sshConfigFile().toAbsolutePath().toString());
                        }
                    }
                    case LegacyExplicitEndpointReference ep -> {
                        connMap.put("host", ep.host());
                        connMap.put("port", ep.port());
                        connMap.put("user", ep.user());
                        connMap.put("identityFileRef", ep.identityFileRef());
                        connMap.put("knownHostsFile", ep.knownHostsFile());
                    }
                }
                entry.put("connection", connMap);
                entry.put("profileManifestId", reg.profileManifestId());

                targetList.add(entry);
            }

            root.put("targets", targetList);

            // Atomic write: temp file → rename
            Files.createDirectories(storeFile.getParent());
            Path tempFile = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            YAML.writeValue(tempFile.toFile(), root);
            Files.move(tempFile, storeFile, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[remote-store] failed to persist targets: {}", e.getMessage());
            throw new RuntimeException("failed to persist remote target store", e);
        }
    }
}
