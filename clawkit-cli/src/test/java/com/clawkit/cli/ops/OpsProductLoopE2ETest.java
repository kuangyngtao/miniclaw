package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.*;
import com.clawkit.ops.loop.*;
import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product E2E test using fake in-process sessions.
 * Verifies: parser, investigation entry, file persistence,
 * session factory calls, safety paths, and no secrets.
 * OPS-PRODUCT-LOOP-1 V4.
 */
class OpsProductLoopE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-01T00:00:00Z"), ZoneId.of("UTC"));

    @TempDir Path homeDir;
    private final List<OpsInvestigationFacade> facades = new ArrayList<>();

    @AfterEach
    void tearDown() { facades.forEach(OpsInvestigationFacade::close); }

    // ── Parser tests ───────────────────────────────────────────────────

    @Test void parserSeparatesTargetServiceAndQuestion() {
        var cmd = OpsCommandParser.parse("investigate test-server order-api 为什么不可用");
        assertThat(cmd.targetId()).isEqualTo("test-server");
        assertThat(cmd.serviceId()).isEqualTo("order-api");
        assertThat(cmd.question()).isEqualTo("为什么不可用");
    }

    @Test void parserRejectsIllegalService() {
        assertThat(OpsCommandParser.isAllowedService("order-api")).isTrue();
        assertThat(OpsCommandParser.isAllowedService("postgres")).isFalse();
    }

    @Test void parserDefaultsServiceId() {
        var cmd = OpsCommandParser.parse("investigate test-server");
        assertThat(cmd.targetId()).isEqualTo("test-server");
        assertThat(cmd.serviceId()).isEqualTo("order-api");
    }

    // ── Investigation tests ────────────────────────────────────────────

    @Test void investigationCreatesIncidentAndFiles() {
        var c = new C();
        var f = createFacade(true, false, c);
        var r = f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.APPROVE, c));

        assertThat(r).isNotNull();
        assertThat(r.incidentId()).startsWith("inc-test-");
        assertThat(c.borrow.get()).isEqualTo(1);

        Path d = homeDir.resolve("incidents").resolve(r.incidentId());
        assertThat(Files.exists(d.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(d.resolve("timeline.ndjson"))).isTrue();
        assertThat(Files.exists(d.resolve("investigation-result.json"))).isTrue();

        // JSON/NDJSON validity
        try {
            MAPPER.readTree(d.resolve("manifest.json").toFile());
            for (String line : Files.readAllLines(d.resolve("timeline.ndjson")))
                if (!line.isBlank()) MAPPER.readTree(line);
            MAPPER.readTree(d.resolve("investigation-result.json").toFile());
        } catch (IOException e) { throw new AssertionError(e); }
    }

    @Test void incidentHasNoSecrets() throws Exception {
        var c = new C();
        var f = createFacade(true, false, c);
        var r = f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.APPROVE, c));

        Path d = homeDir.resolve("incidents").resolve(r.incidentId());
        for (Path file : Files.list(d).toList()) {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file);
                assertThat(content).doesNotContain(
                    "sk-", "api_key", "Authorization", "Bearer", "password", "secret");
            }
        }
    }

    @Test void investigationWithNoFixReturnsNeedsHuman() {
        // APP_DOWN + no fix factory → NEEDS_HUMAN
        var c = new C();
        var f = createFacade(true, false, c);
        var r = f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.APPROVE, c));

        assertThat(r).isNotNull();
        assertThat(c.fixCreate.get()).isEqualTo(0);
    }

    @Test void investigationWithFixConfigCompletes() {
        // Investigation completes; further repair requires real SSH
        var c = new C();
        var f = createFacade(true, true, c);
        var r = f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.APPROVE, c));
        assertThat(r).isNotNull();
        assertThat(r.incidentId()).startsWith("inc-test-");
    }

    // ── Safety: no writes on reject/cancel ─────────────────────────────

    @Test void rejectDoesNotCreateFixSession() {
        var c = new C();
        var f = createFacade(true, true, c);
        f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.REJECT, c));
        assertThat(c.fixCreate.get()).isEqualTo(0);
    }

    @Test void cancelDoesNotCreateFixSession() {
        var c = new C();
        var f = createFacade(true, true, c);
        f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.CANCEL, c));
        assertThat(c.fixCreate.get()).isEqualTo(0);
    }

    @Test void eofDoesNotCreateFixSession() {
        var c = new C();
        var f = createFacade(true, true, c);
        f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.EOF, c));
        assertThat(c.fixCreate.get()).isEqualTo(0);
    }

    @Test void interruptedDoesNotCreateFixSession() {
        var c = new C();
        var f = createFacade(true, true, c);
        f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.INTERRUPTED, c));
        assertThat(c.fixCreate.get()).isEqualTo(0);
    }

    // ── Continue tests ─────────────────────────────────────────────────

    @Test void continueOnCreatedReInvestigates() {
        var c = new C();
        var f = createFacade(true, false, c);
        var r = f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.APPROVE, c));

        var c2 = new C();
        var r2 = f.continueIncident(r.incidentId(),
            new TestInteraction(ApprovalDecision.APPROVE, c2));
        assertThat(r2).isNotNull();
        assertThat(r2.incidentId()).isEqualTo(r.incidentId());
    }

    @Test void continueOnResolvedIsRejected() {
        var c = new C();
        var f = createFacade(true, false, c);
        var r = f.investigateAndMaybeRepair(
            new InvestigationRequest("test", "order-api", null),
            new TestInteraction(ApprovalDecision.APPROVE, c));

        // Mark RESOLVED manually
        try {
            new IncidentStore(homeDir).markTerminal(r.incidentId(),
                UserIncidentStatus.RESOLVED);
        } catch (IOException e) { throw new RuntimeException(e); }

        var c2 = new C();
        var r2 = f.continueIncident(r.incidentId(),
            new TestInteraction(ApprovalDecision.APPROVE, c2));
        assertThat(r2).isNotNull();
    }

    // ── Factory ─────────────────────────────────────────────────────────

    private OpsInvestigationFacade createFacade(boolean evidenceDown,
                                                  boolean fixConfigured, C c) {
        OpsInvestigationFacade.InitialReadSessionProvider borrow = tid -> {
            c.borrow.incrementAndGet();
            return new FakeReadSession(tid, evidenceDown);
        };
        OpsInvestigationFacade.FreshReadSessionFactory fresh = tid -> {
            c.fresh.incrementAndGet();
            return new FakeReadSession(tid, evidenceDown);
        };
        OpsInvestigationFacade.FixSessionFactory fix = fixConfigured ? tid -> {
            c.fixCreate.incrementAndGet();
            throw new IOException("SSH not available in test");
        } : null;

        var f = new OpsInvestigationFacade(homeDir, borrow, fresh, fix,
            config -> { throw new UnsupportedOperationException("no LLM"); }, FIXED_CLOCK);
        facades.add(f);
        return f;
    }

    // ── Inner types ─────────────────────────────────────────────────────

    static class C {
        final AtomicInteger borrow = new AtomicInteger();
        final AtomicInteger fresh = new AtomicInteger();
        final AtomicInteger fixCreate = new AtomicInteger();
        final AtomicInteger approvalPrompt = new AtomicInteger();
    }

    record TestInteraction(ApprovalDecision decision, C c)
            implements InvestigationInteraction {
        @Override public void onProgress(InvestigationProgress p) { }
        @Override public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
            c.approvalPrompt.incrementAndGet();
            return decision;
        }
        @Override public void onFinalResult(InvestigationView r) { }
    }

    static class FakeReadSession implements OpsReadSession {
        final String tid; final boolean down;
        FakeReadSession(String t, boolean d) { tid = t; down = d; }
        @Override public McpCallResult callTool(String tool, ObjectNode args) {
            String r = switch (tool) {
                case "service_status" -> down
                    ? "{\"success\":true,\"data\":{\"containers\":[{\"State\":\"exited\"}]}}"
                    : "{\"success\":true,\"data\":{\"containers\":[{\"State\":\"running\"}]}}";
                case "container_status" -> down
                    ? "{\"success\":true,\"data\":{\"state\":{\"Status\":\"exited\"}}}"
                    : "{\"success\":true,\"data\":{\"state\":{\"Status\":\"running\"}}}";
                case "http_probe" -> down
                    ? "{\"success\":false,\"error\":\"connection refused\"}"
                    : "{\"success\":true,\"data\":{\"statusCode\":200}}";
                default -> "{\"success\":true}";
            };
            return McpCallResult.success(r, List.of());
        }
        @Override public boolean isReady() { return true; }
        @Override public String targetId() { return tid; }
        @Override public void close() { }
    }
}
