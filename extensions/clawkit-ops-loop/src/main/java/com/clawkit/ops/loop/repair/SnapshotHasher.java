package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceBundle;
import com.clawkit.ops.loop.EvidenceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic snapshot hash for TOCTOU detection.
 * Only hashes essential state fields (State, statusCode, healthy) from order-api evidence.
 * Excludes container IDs, timestamps, and other per-session metadata.
 */
public final class SnapshotHasher {

    private SnapshotHasher() {}

    public static String compute(EvidenceBundle bundle, String targetServiceId) {
        // Find relevant evidence
        List<Evidence> svcList = bundle.evidence().stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.type() == EvidenceType.SERVICE_STATUS)
            .filter(e -> e.scope().contains(targetServiceId))
            .toList();

        List<Evidence> ctrList = bundle.evidence().stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.type() == EvidenceType.CONTAINER_STATUS)
            .filter(e -> e.scope().contains(targetServiceId))
            .toList();

        List<Evidence> httpList = bundle.evidence().stream()
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.freshness() == Evidence.Freshness.CURRENT)
            .filter(e -> e.type() == EvidenceType.HTTP_PROBE)
            .toList();

        if (svcList.isEmpty()) throw new IllegalArgumentException(
            "no CURRENT/OBSERVED service_status evidence for " + targetServiceId);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // Hash only essential state from service_status
            for (Evidence e : svcList) {
                md.update(("SVC:" + extractState(e) + "\0").getBytes(StandardCharsets.UTF_8));
            }
            // Hash only essential state from container_status
            for (Evidence e : ctrList) {
                md.update(("CTR:" + extractState(e) + "\0").getBytes(StandardCharsets.UTF_8));
            }
            // Hash HTTP status
            for (Evidence e : httpList) {
                int status = EvidenceReader.dataHttpStatus(e).orElse(-1);
                boolean healthy = EvidenceReader.isHealthy(e);
                md.update(("HTTP:" + status + ":" + healthy + "\0").getBytes(StandardCharsets.UTF_8));
            }

            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /** Extract container State from evidence data (handles DockerOpsBackend nesting). */
    private static String extractState(Evidence e) {
        return EvidenceReader.state(e).orElse("UNKNOWN");
    }
}
