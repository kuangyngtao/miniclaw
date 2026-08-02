package com.clawkit.cli.remote;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in catalog of supported remote capability profiles.
 *
 * <p>Only contains READ_ONLY profiles. FIX/write profiles must not
 * appear here. Unknown manifest IDs fail closed.
 *
 * <p>Contract hashes are version-controlled constants that must be
 * kept in sync with {@code OpsMcpServer} via cross-contract tests.
 *
 * <p>Design: PRODUCT-1 §5.3.
 */
public final class RemoteProfileCatalog {

    // Pinned hashes from OpsMcpServer computeExpectedToolContractHash (version-controlled)
    // Updated: 2026-07-30 — PR-2

    private static final RemoteProfileManifest APP_DOWN = new RemoteProfileManifest(
        1,
        "app-down-readonly-v1",
        "Linux 服务基础检查",
        "clawkit-ops-mcp",
        "2024-11-05",
        "1",
        "APP_DOWN_V1",
        "d822b006a5dcb84c",
        "666e4646d56653639adf0719e614258eef8fc4ce521f3ec57e7d8e0680569abf",
        RemoteAccessMode.READ_ONLY,
        "opsro"
    );

    private static final RemoteProfileManifest POSTGRES = new RemoteProfileManifest(
        1,
        "postgres-diagnosis-readonly-v1",
        "PostgreSQL 数据库诊断",
        "clawkit-ops-mcp",
        "2024-11-05",
        "1",
        "POSTGRES_DIAGNOSIS_V1",
        "7e33276f3c0ef4b9",
        "141d42ba560716f5698d7ebd7e0a2b7fa13e60d10a9df23e6967c04186b65540",
        RemoteAccessMode.READ_ONLY,
        "opsro"
    );

    private static final Map<String, RemoteProfileManifest> CATALOG = Map.of(
        APP_DOWN.manifestId(), APP_DOWN,
        POSTGRES.manifestId(), POSTGRES
    );

    private RemoteProfileCatalog() {}

    /** Look up a manifest by ID. Returns empty if unknown. */
    public static Optional<RemoteProfileManifest> lookup(String manifestId) {
        return Optional.ofNullable(CATALOG.get(manifestId));
    }

    /** All built-in manifests. */
    public static List<RemoteProfileManifest> all() {
        return List.copyOf(CATALOG.values());
    }

    /** Verify that a manifest ID is known and safe. */
    public static boolean isKnown(String manifestId) {
        return CATALOG.containsKey(manifestId);
    }

    // ── Exposed for cross-contract tests ─────────────────────────────

    static RemoteProfileManifest appDownManifest() { return APP_DOWN; }
    static RemoteProfileManifest postgresManifest() { return POSTGRES; }
}
