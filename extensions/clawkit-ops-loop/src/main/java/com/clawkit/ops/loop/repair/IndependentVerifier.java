package com.clawkit.ops.loop.repair;

import com.clawkit.ops.loop.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent post-repair verification using a fresh opsro session.
 * All evidence field reads go through {@link EvidenceReader} for correct
 * nested fact.data access and fail-closed semantics.
 */
public final class IndependentVerifier {

    private static final Logger log = LoggerFactory.getLogger(IndependentVerifier.class);
    private final Clock clock;

    public IndependentVerifier(Clock clock) { this.clock = clock; }
    public IndependentVerifier() { this(Clock.systemUTC()); }

    public VerificationResult verify(
            RepairResult repairResult,
            String incidentId,
            SshConnectionConfig opsroConfig,
            RemoteTargetDescriptor targetDescriptor) {

        String verificationRunId = "verify-" + UUID.randomUUID().toString().substring(0, 8);
        List<VerificationResult.VerificationCheck> checks = new ArrayList<>();

        log.info("[verify:{}] starting independent verification for repair {}",
            verificationRunId, repairResult.attemptId());

        try (RemoteOpsSession opsroSession = new RemoteOpsSession(
                targetDescriptor, opsroConfig, clock)) {

            opsroSession.start();

            DiscoveryProfile profile = DiscoveryProfile.REMOTE_APP_DOWN_V1;
            RemoteDiscoveryCoordinator coordinator = new RemoteDiscoveryCoordinator(opsroSession);
            DiscoveryResult discovery = coordinator.collect(incidentId,
                "run-" + verificationRunId, profile);

            if (discovery.status() != DiscoveryStatus.COMPLETE) {
                checks.add(VerificationResult.VerificationCheck.fail("discovery",
                    "discovery not COMPLETE: " + discovery.status()));
                return new VerificationResult(verificationRunId, repairResult.attemptId(),
                    false, checks, discovery.bundle(), false, false);
            }

            EvidenceBundle freshEvidence = discovery.bundle();
            Instant now = clock.instant();

            // ── Run all verification checks ──
            checks.add(checkServiceStatus(freshEvidence, now));
            checks.add(checkContainerStatus(freshEvidence, now));
            checks.add(checkHttpReadiness(freshEvidence, now));
            checks.add(checkAppDownResolved(freshEvidence, now));
            checks.add(checkNoNewErrors(freshEvidence, now));
            checks.add(checkBusinessInvariants(freshEvidence, now));

            boolean allPassed = checks.stream().allMatch(VerificationResult.VerificationCheck::passed);
            boolean hasNewErrors = checks.stream()
                .anyMatch(c -> c.name().equals("no_new_errors") && !c.passed());
            boolean bizOk = checks.stream()
                .filter(c -> c.name().equals("business_invariants"))
                .findFirst().map(VerificationResult.VerificationCheck::passed).orElse(false);

            log.info("[verify:{}] checks: {} allPassed={} bizOk={} newErrors={}",
                verificationRunId, checks.size(), allPassed, bizOk, hasNewErrors);

            return new VerificationResult(verificationRunId, repairResult.attemptId(),
                allPassed, checks, freshEvidence, bizOk, hasNewErrors);

        } catch (IOException e) {
            log.error("[verify:{}] session failed: {}", verificationRunId, e.getMessage());
            checks.add(VerificationResult.VerificationCheck.fail("session",
                "verification session failed: " + e.getMessage()));
            return new VerificationResult(verificationRunId, repairResult.attemptId(),
                false, checks, null, false, false);
        }
    }

    /**
     * Verify using an already-open {@link OpsReadSession}. The caller owns
     * the session lifecycle — this method does NOT close it.
     *
     * <p>OPS-PRODUCT-LOOP-1 §6 (revised).
     */
    public VerificationResult verify(
            RepairResult repairResult,
            String incidentId,
            OpsReadSession session) {

        String verificationRunId = "verify-" + UUID.randomUUID().toString().substring(0, 8);
        List<VerificationResult.VerificationCheck> checks = new ArrayList<>();

        log.info("[verify:{}] starting independent verification for repair {}",
            verificationRunId, repairResult.attemptId());

        try {
            DiscoveryProfile profile = DiscoveryProfile.REMOTE_APP_DOWN_V1;
            RemoteDiscoveryCoordinator coordinator = new RemoteDiscoveryCoordinator(session);
            DiscoveryResult discovery = coordinator.collect(incidentId,
                "run-" + verificationRunId, profile);

            if (discovery.status() != DiscoveryStatus.COMPLETE) {
                checks.add(VerificationResult.VerificationCheck.fail("discovery",
                    "discovery not COMPLETE: " + discovery.status()));
                return new VerificationResult(verificationRunId, repairResult.attemptId(),
                    false, checks, discovery.bundle(), false, false);
            }

            EvidenceBundle freshEvidence = discovery.bundle();
            Instant now = clock.instant();

            checks.add(checkServiceStatus(freshEvidence, now));
            checks.add(checkContainerStatus(freshEvidence, now));
            checks.add(checkHttpReadiness(freshEvidence, now));
            checks.add(checkAppDownResolved(freshEvidence, now));
            checks.add(checkNoNewErrors(freshEvidence, now));
            checks.add(checkBusinessInvariants(freshEvidence, now));

            boolean allPassed = checks.stream().allMatch(VerificationResult.VerificationCheck::passed);
            boolean hasNewErrors = checks.stream()
                .anyMatch(c -> c.name().equals("no_new_errors") && !c.passed());
            boolean bizOk = checks.stream()
                .filter(c -> c.name().equals("business_invariants"))
                .findFirst().map(VerificationResult.VerificationCheck::passed).orElse(false);

            log.info("[verify:{}] checks: {} allPassed={} bizOk={} newErrors={}",
                verificationRunId, checks.size(), allPassed, bizOk, hasNewErrors);

            return new VerificationResult(verificationRunId, repairResult.attemptId(),
                allPassed, checks, freshEvidence, bizOk, hasNewErrors);

        } catch (IOException e) {
            log.error("[verify:{}] session failed: {}", verificationRunId, e.getMessage());
            checks.add(VerificationResult.VerificationCheck.fail("session",
                "verification session failed: " + e.getMessage()));
            return new VerificationResult(verificationRunId, repairResult.attemptId(),
                false, checks, null, false, false);
        }
    }

    private VerificationResult.VerificationCheck checkServiceStatus(
            EvidenceBundle evidence, Instant now) {
        Optional<Evidence> svc = EvidenceReader.find(evidence,
            EvidenceType.SERVICE_STATUS, "order-api", now);
        if (svc.isEmpty()) {
            return VerificationResult.VerificationCheck.fail("service_status",
                "no CURRENT/OBSERVED service_status evidence for order-api");
        }
        if (!EvidenceReader.success(svc.get())) {
            return VerificationResult.VerificationCheck.fail("service_status",
                "service_status success=false");
        }
        if (!EvidenceReader.isRunning(svc.get())) {
            String state = EvidenceReader.dataString(svc.get(), "State").orElse("unknown");
            return VerificationResult.VerificationCheck.fail("service_status",
                "order-api not running: " + state);
        }
        return VerificationResult.VerificationCheck.pass("service_status");
    }

    private VerificationResult.VerificationCheck checkContainerStatus(
            EvidenceBundle evidence, Instant now) {
        Optional<Evidence> ctr = EvidenceReader.find(evidence,
            EvidenceType.CONTAINER_STATUS, "order-api", now);
        if (ctr.isEmpty()) {
            return VerificationResult.VerificationCheck.fail("container_status",
                "no CURRENT/OBSERVED container_status evidence for order-api");
        }
        if (!EvidenceReader.success(ctr.get())) {
            return VerificationResult.VerificationCheck.fail("container_status",
                "container_status success=false");
        }
        if (!EvidenceReader.isRunning(ctr.get())) {
            String state = EvidenceReader.dataString(ctr.get(), "State").orElse("unknown");
            return VerificationResult.VerificationCheck.fail("container_status",
                "order-api container not running: " + state);
        }
        return VerificationResult.VerificationCheck.pass("container_status");
    }

    private VerificationResult.VerificationCheck checkHttpReadiness(
            EvidenceBundle evidence, Instant now) {
        Optional<Evidence> http = EvidenceReader.find(evidence,
            EvidenceType.HTTP_PROBE, null, now);
        if (http.isEmpty()) {
            return VerificationResult.VerificationCheck.fail("http_probe",
                "no CURRENT/OBSERVED http_probe evidence");
        }
        if (!EvidenceReader.success(http.get())) {
            return VerificationResult.VerificationCheck.fail("http_probe",
                "http_probe success=false");
        }
        Optional<Integer> status = EvidenceReader.dataHttpStatus(http.get());
        if (status.isEmpty()) {
            return VerificationResult.VerificationCheck.fail("http_probe",
                "http_probe data missing status/statusCode field");
        }
        if (status.get() != 200) {
            return VerificationResult.VerificationCheck.fail("http_probe",
                "HTTP " + status.get() + " (expected 200)");
        }
        // Check body for real errors (not metrics field names like "errorCount")
        String body = EvidenceReader.dataBody(http.get()).orElse("");
        if (body.contains("\"error\":") || body.contains("\"errors\":")) {
            return VerificationResult.VerificationCheck.fail("http_probe",
                "HTTP 200 but response body contains error fields");
        }
        return VerificationResult.VerificationCheck.pass("http_probe");
    }

    private VerificationResult.VerificationCheck checkAppDownResolved(
            EvidenceBundle evidence, Instant now) {
        long failures = evidence.evidence().stream()
            .filter(e -> e.scope().contains("order-api") || e.scope().contains("gateway"))
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.isCurrentAt(now))
            .filter(e -> !EvidenceReader.success(e))
            .count();
        if (failures > 0) {
            return VerificationResult.VerificationCheck.fail("app_down_resolved",
                failures + " evidence items still show failure");
        }
        return VerificationResult.VerificationCheck.pass("app_down_resolved");
    }

    private VerificationResult.VerificationCheck checkNoNewErrors(
            EvidenceBundle evidence, Instant now) {
        var logs = evidence.evidence().stream()
            .filter(e -> e.type() == EvidenceType.LOGS)
            .filter(e -> e.collectionStatus() == Evidence.CollectionStatus.OBSERVED)
            .filter(e -> e.isCurrentAt(now))
            .toList();
        for (var log : logs) {
            String content = log.fact().toString();
            if (content.contains("\"level\":\"ERROR\"") || content.contains("ERROR")) {
                return VerificationResult.VerificationCheck.fail("no_new_errors",
                    "ERROR found in post-repair logs for " + log.scope());
            }
        }
        return VerificationResult.VerificationCheck.pass("no_new_errors");
    }

    private VerificationResult.VerificationCheck checkBusinessInvariants(
            EvidenceBundle evidence, Instant now) {
        // 1. Try business metrics (POSTGRES_DIAGNOSIS_V1 profile)
        Optional<Evidence> metrics = EvidenceReader.find(evidence,
            EvidenceType.BUSINESS_METRIC, null, now);
        if (metrics.isPresent() && EvidenceReader.success(metrics.get())) {
            long errors = EvidenceReader.dataLong(metrics.get(), "errorCount").orElse(0L);
            if (errors > 0) {
                return VerificationResult.VerificationCheck.fail("business_invariants",
                    "business metrics show " + errors + " errors");
            }
            double p95 = EvidenceReader.dataDouble(metrics.get(), "p95LatencyMs").orElse(0.0);
            if (p95 > 1000) {
                return VerificationResult.VerificationCheck.fail("business_invariants",
                    "P95 latency " + p95 + "ms exceeds threshold");
            }
            return VerificationResult.VerificationCheck.pass("business_invariants");
        }

        // 2. For APP_DOWN_V1: service running + HTTP 200 is sufficient
        //    when business metrics are not available in the discovery profile
        Optional<Evidence> svc = EvidenceReader.find(evidence,
            EvidenceType.SERVICE_STATUS, "order-api", now);
        Optional<Evidence> http = EvidenceReader.find(evidence,
            EvidenceType.HTTP_PROBE, null, now);

        if (svc.isPresent() && EvidenceReader.isRunning(svc.get())
            && EvidenceReader.success(svc.get())
            && http.isPresent() && EvidenceReader.success(http.get())
            && EvidenceReader.dataHttpStatus(http.get()).orElse(-1) == 200) {
            return VerificationResult.VerificationCheck.pass("business_invariants");
        }

        return VerificationResult.VerificationCheck.fail("business_invariants",
            "no business metrics available and service/HTTP check failed");
    }
}
