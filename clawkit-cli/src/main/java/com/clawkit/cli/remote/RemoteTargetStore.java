package com.clawkit.cli.remote;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Persistent store for registered remote targets.
 *
 * <p>Narrow interface — no tenantId, owner, role, or organization fields.
 * Designed to be backed by a local YAML file, but injectable for testing.
 *
 * <p>Design: REMOTE-0 §10.1.
 */
public interface RemoteTargetStore {

    /** List all registered target IDs. */
    List<String> list();

    /** Get a target config by ID. */
    Optional<RemoteTargetConfig> get(String targetId);

    /**
     * Add a target from a config file (legacy v1 path).
     *
     * @param targetId the target ID to register
     * @param configFile path to the YAML config file
     * @param replace if true, overwrite an existing target with the same ID
     * @throws TargetAlreadyExistsException if the target exists and replace is false
     * @throws RemoteTargetConfig.ConfigValidationException if the config is invalid
     */
    void add(String targetId, Path configFile, boolean replace);

    /**
     * Add a target from a v2 registration domain object.
     *
     * @param registration the v2 registration
     * @param replace if true, overwrite an existing target with the same ID
     * @throws TargetAlreadyExistsException if the target exists and replace is false
     */
    void add(RemoteTargetRegistration registration, boolean replace);

    /**
     * Get a v2 registration by target ID.
     *
     * @return the registration, or empty if not found or is a v1-only target
     */
    Optional<RemoteTargetRegistration> getRegistration(String targetId);

    /**
     * Remove a target.
     *
     * @throws TargetInUseException if the target is currently active/connected
     */
    void remove(String targetId);

    /** Check if a target exists (v1 or v2). */
    boolean exists(String targetId);

    /** Check if a target is currently marked as active (connected). */
    boolean isActive(String targetId);

    /** Mark a target as active (connected). */
    void markActive(String targetId);

    /** Mark a target as inactive (disconnected). */
    void markInactive(String targetId);

    // ── Exceptions ────────────────────────────────────────────────────

    class TargetAlreadyExistsException extends RuntimeException {
        public TargetAlreadyExistsException(String targetId) {
            super("target already exists: " + targetId + " (use --replace to overwrite)");
        }
    }

    class TargetInUseException extends RuntimeException {
        public TargetInUseException(String targetId) {
            super("target is currently active — disconnect first: " + targetId);
        }
    }

    class TargetNotFoundException extends RuntimeException {
        public TargetNotFoundException(String targetId) {
            super("target not found: " + targetId);
        }
    }
}
