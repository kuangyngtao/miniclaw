package com.clawkit.ops.loop;

import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a single remote discovery via a {@link RemoteOpsSession}.
 *
 * <p>PR-M3 §5. Collects evidence in profile order, per-item timeout,
 * partial failure (each item produces Evidence regardless of success),
 * immutable bundle freeze, and completeness gate.
 *
 * <p>Thread-safe for sequential use within a single discovery run.
 */
public final class RemoteDiscoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RemoteDiscoveryCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RemoteOpsSession session;
    private final Clock clock;

    public RemoteDiscoveryCoordinator(RemoteOpsSession session) {
        this(session, Clock.systemUTC());
    }

    public RemoteDiscoveryCoordinator(RemoteOpsSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /**
     * Collect evidence for the given incident + profile.
     *
     * @return the discovery result containing all evidence and status
     */
    public DiscoveryResult collect(String incidentId, String runId,
                                    DiscoveryProfile profile) throws IOException {
        List<EvidenceSpec> specs = profile.specs();
        List<Evidence> evidence = new ArrayList<>(specs.size());
        int requiredSuccess = 0;
        int requiredTotal = 0;
        boolean transportLost = false;

        log.info("[discovery:{}] starting collection: {} specs ({} required)",
            runId, specs.size(), profile.requiredSpecs().size());

        for (EvidenceSpec spec : specs) {
            if (transportLost) {
                // Transport already gone — record NOT_COLLECTED for remaining
                evidence.add(transportLostEvidence(incidentId, runId, spec));
                continue;
            }

            Evidence e;
            try {
                e = collectOne(incidentId, runId, spec);
            } catch (IOException ex) {
                // Transport failure — mark remaining as not collected
                transportLost = true;
                e = transportLostEvidence(incidentId, runId, spec);
            }

            evidence.add(e);

            if (spec.required()) {
                requiredTotal++;
                if (e.collectionStatus() != Evidence.CollectionStatus.COLLECTION_FAILED) {
                    requiredSuccess++;
                }
            }
        }

        // Freeze the bundle
        EvidenceBundle bundle = new EvidenceBundle(incidentId, runId,
            clock.instant(), evidence);

        // Determine status
        DiscoveryStatus status;
        if (transportLost) {
            status = DiscoveryStatus.TRANSPORT_FAILED;
        } else if (requiredSuccess >= profile.minRequiredEvidence()) {
            status = DiscoveryStatus.COMPLETE;
        } else {
            status = DiscoveryStatus.INCOMPLETE;
        }

        log.info("[discovery:{}] complete: status={}, evidence={}/{}, required={}/{}",
            runId, status, evidence.size(), specs.size(),
            requiredSuccess, requiredTotal);

        return new DiscoveryResult(incidentId, runId, profile.name(),
            bundle, status, requiredSuccess, requiredTotal,
            clock.instant());
    }

    private Evidence collectOne(String incidentId, String runId,
                                 EvidenceSpec spec) throws IOException {
        String evidenceId = "e-" + spec.sequenceNumber();
        Instant observedAt = clock.instant();

        ObjectNode args = MAPPER.createObjectNode();
        spec.arguments().forEach((k, v) -> {
            if (v instanceof Integer i) args.put(k, i);
            else if (v instanceof Boolean b) args.put(k, b);
            else args.put(k, String.valueOf(v));
        });

        // session.callTool() throws IOException on transport failure —
        // let it propagate to the caller for transport-lost handling.
        McpCallResult result = session.callTool(spec.mcpTool(), args);

        try {
            JsonNode parsed = MAPPER.readTree(result.text());
            boolean success = parsed.path("success").asBoolean(false);
            String errorCode = parsed.path("errorCode").asText(null);
            String error = parsed.path("error").asText(null);

            return createEvidence(incidentId, runId, spec, evidenceId,
                observedAt, success, errorCode != null ? errorCode : error);
        } catch (Exception e) {
            return createEvidence(incidentId, runId, spec, evidenceId,
                observedAt, false, "parse error: " + e.getMessage());
        }
    }

    private Evidence createEvidence(String incidentId, String runId,
                                     EvidenceSpec spec, String evidenceId,
                                     Instant observedAt, boolean success,
                                     String errorDetail) {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", success);
        if (errorDetail != null && !success) {
            fact.put("error", errorDetail);
        }
        Evidence.CollectionStatus cs = success
            ? Evidence.CollectionStatus.OBSERVED
            : Evidence.CollectionStatus.COLLECTION_FAILED;

        Instant validUntil = success
            ? observedAt.plus(spec.freshnessTtl()) : null;

        return new Evidence(
            evidenceId, incidentId, spec.evidenceType(),
            "mcp:ops/" + spec.mcpTool(),
            observedAt, clock.instant(), spec.scope(),
            Evidence.Kind.FACT, fact,
            "run://" + runId + "/tool/" + evidenceId,
            success ? Evidence.Freshness.CURRENT : Evidence.Freshness.STALE,
            Evidence.Redaction.NONE,
            "2", cs, validUntil, null);
    }

    private Evidence transportLostEvidence(String incidentId, String runId,
                                            EvidenceSpec spec) {
        ObjectNode fact = MAPPER.createObjectNode();
        fact.put("success", false);
        fact.put("error", "NOT_COLLECTED_TRANSPORT_LOST");
        return new Evidence(
            "e-" + spec.sequenceNumber(), incidentId, spec.evidenceType(),
            "mcp:ops/" + spec.mcpTool(),
            clock.instant(), clock.instant(), spec.scope(),
            Evidence.Kind.FACT, fact,
            "run://" + runId + "/tool/e-" + spec.sequenceNumber(),
            Evidence.Freshness.STALE, Evidence.Redaction.NONE,
            "2", Evidence.CollectionStatus.COLLECTION_FAILED, null, null);
    }
}
