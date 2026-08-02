package com.clawkit.tools.remote;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reference to an SSH private key, resolved at connection time.
 *
 * <p>Supports two forms:
 * <ul>
 *   <li>{@code env:VAR_NAME} — reads the path from an environment variable.
 *       The value must be an absolute path to a key file, NOT the key text.</li>
 *   <li>{@code file:/absolute/path} — direct path reference.</li>
 * </ul>
 *
 * <p>Rejects inline PEM, relative paths, and directory paths.
 * Safe to pass as a value object — contains only the reference string,
 * never the key material.
 */
public sealed interface CredentialRef
    permits CredentialRef.EnvRef, CredentialRef.FileRef {

    /** Resolve to an absolute, verified {@link Path}. */
    Path resolve();

    /** Original reference string for display (never contains key material). */
    String ref();

    // ── Implementations ─────────────────────────────────────────────

    /** {@code env:VAR_NAME} — path stored in an environment variable. */
    record EnvRef(String varName) implements CredentialRef {
        public EnvRef {
            Objects.requireNonNull(varName, "varName");
            if (varName.isBlank()) throw new IllegalArgumentException("varName must not be blank");
        }

        @Override
        public Path resolve() {
            String value = System.getenv(varName);
            if (value == null || value.isBlank()) {
                throw new CredentialRefException(
                    "RMT-003", "environment variable " + varName + " is not set or empty");
            }
            return validatePath(value);
        }

        @Override
        public String ref() {
            return "env:" + varName;
        }
    }

    /** {@code file:/absolute/path} — direct path reference. */
    record FileRef(Path path) implements CredentialRef {
        public FileRef {
            Objects.requireNonNull(path, "path");
        }

        @Override
        public Path resolve() {
            return validatePath(path.toString());
        }

        @Override
        public String ref() {
            return "file:" + path.toAbsolutePath().normalize();
        }
    }

    // ── Factory ──────────────────────────────────────────────────────

    static CredentialRef parse(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (ref.startsWith("env:")) {
            String varName = ref.substring(4).strip();
            if (varName.isEmpty()) throw new IllegalArgumentException("env: requires a variable name");
            return new EnvRef(varName);
        }
        if (ref.startsWith("file:")) {
            String pathStr = ref.substring(5).strip();
            if (pathStr.isEmpty()) throw new IllegalArgumentException("file: requires a path");
            return new FileRef(Path.of(pathStr));
        }
        throw new IllegalArgumentException(
            "credential ref must start with 'env:' or 'file:': " + ref);
    }

    // ── Validation ───────────────────────────────────────────────────

    private static Path validatePath(String raw) {
        String trimmed = raw.strip();
        // Reject inline PEM content
        if (trimmed.contains("-----BEGIN") || trimmed.contains("PRIVATE KEY")) {
            throw new CredentialRefException("RMT-003",
                "credential value appears to be key material, not a file path");
        }
        if (trimmed.contains("\n") || trimmed.contains("\r")) {
            throw new CredentialRefException("RMT-003",
                "credential value contains newlines — inline key material is not allowed");
        }
        Path path;
        try {
            path = Path.of(trimmed);
        } catch (InvalidPathException e) {
            throw new CredentialRefException("RMT-003", "invalid path: " + trimmed);
        }
        if (!path.isAbsolute()) {
            throw new CredentialRefException("RMT-003",
                "credential path must be absolute: " + trimmed);
        }
        if (Files.isDirectory(path)) {
            throw new CredentialRefException("RMT-003",
                "credential path is a directory: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new CredentialRefException("RMT-003",
                "credential file not found: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    /** Exception thrown when a credential reference cannot be resolved. */
    final class CredentialRefException extends RuntimeException {
        private final String code;
        CredentialRefException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
}
