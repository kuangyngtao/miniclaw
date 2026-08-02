package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.RemoteIncidentResult;
import com.clawkit.ops.loop.repair.RepairResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-based incident persistence under {@code ~/.clawkit/incidents/<id>/}.
 *
 * <p>Each incident directory contains:
 * <ul>
 *   <li>{@code manifest.json} — atomic write, schema-versioned</li>
 *   <li>{@code timeline.ndjson} — append-only</li>
 *   <li>{@code investigation-result.json} — discovery + diagnosis</li>
 *   <li>{@code report.md} — Chinese human-readable report</li>
 *   <li>{@code repair-result.json} — only if repair occurred</li>
 *   <li>{@code .attempts/} — {@code FileActionAttemptStore} journal</li>
 * </ul>
 *
 * <p>Never persists: private keys, API keys, tokens, Authorization headers,
 * SSH argv, raw unsanitized logs, or Java stack traces.
 *
 * <p>OPS-PRODUCT-LOOP-1 §10.
 */
public final class IncidentStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IncidentStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    static final String MANIFEST_SCHEMA_VERSION = "1";

    private final Path incidentsDir;
    private final Clock clock;

    public IncidentStore(Path baseDir) {
        this(baseDir, Clock.systemUTC());
    }

    public IncidentStore(Path baseDir, Clock clock) {
        this.incidentsDir = baseDir.resolve("incidents");
        this.clock = clock;
        try {
            Files.createDirectories(incidentsDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create incidents dir: " + incidentsDir, e);
        }
    }

    // ── Create ──────────────────────────────────────────────────────────

    /** Create a new incident directory and return its ID. */
    public String createIncident(String targetId, String serviceId) throws IOException {
        String incidentId = "inc-" + targetId + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = incidentDir(incidentId);
        Files.createDirectories(dir);

        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("schemaVersion", MANIFEST_SCHEMA_VERSION);
        manifest.put("incidentId", incidentId);
        manifest.put("targetId", targetId);
        if (serviceId != null) manifest.put("serviceId", serviceId);
        manifest.put("userStatus", UserIncidentStatus.CREATED.name());
        manifest.put("createdAt", clock.instant().toString());
        manifest.put("updatedAt", clock.instant().toString());
        manifest.put("terminal", false);

        atomicWriteJson(dir.resolve("manifest.json"), manifest);

        // Initialize timeline
        appendTimeline(incidentId, "incident_created", "ok",
            "target=" + targetId + (serviceId != null ? " service=" + serviceId : ""));

        log.info("[incident-store] created {}", incidentId);
        return incidentId;
    }

    // ── Update ──────────────────────────────────────────────────────────

    /** Update the manifest with investigation results (discovery + diagnosis). */
    public void updateInvestigationResult(String incidentId, RemoteIncidentResult result,
                                          UserIncidentStatus status) throws IOException {
        Path dir = incidentDir(incidentId);
        if (!Files.exists(dir)) throw new IOException("incident not found: " + incidentId);

        // Write investigation result
        Path resultPath = dir.resolve("investigation-result.json");
        atomicWriteJson(resultPath, result);

        // Update manifest
        updateManifest(incidentId, manifest -> {
            manifest.put("discoveryRunId", result.discovery().runId());
            manifest.put("diagnosisRunId", "diag-" + result.discovery().runId());
            manifest.put("userStatus", status.name());
        });

        appendTimeline(incidentId, "investigation_complete", "ok",
            "status=" + status + " rootCause=" + result.diagnosis().rootCauseCode()
            + " providerCalled=" + result.providerCalled());
    }

    /** Write the Chinese markdown report. */
    public void writeReport(String incidentId, String markdown) throws IOException {
        Path dir = incidentDir(incidentId);
        if (!Files.exists(dir)) throw new IOException("incident not found: " + incidentId);
        Path reportPath = dir.resolve("report.md");
        atomicWriteText(reportPath, markdown);
    }

    /** Update manifest when repair completes. */
    public void updateRepairResult(String incidentId, RepairResult result,
                                    UserIncidentStatus status) throws IOException {
        Path dir = incidentDir(incidentId);
        if (!Files.exists(dir)) throw new IOException("incident not found: " + incidentId);

        // Write repair result
        Path repairPath = dir.resolve("repair-result.json");
        atomicWriteJson(repairPath, result);

        // Update manifest
        updateManifest(incidentId, manifest -> {
            manifest.put("repairRunId", result.repairRunId());
            manifest.put("attemptId", result.attemptId());
            manifest.put("attemptState", result.attemptState().name());
            if (result.verification() != null) {
                manifest.put("verificationRunId", result.verification().verificationRunId());
            }
            manifest.put("userStatus", status.name());
        });

        appendTimeline(incidentId, "repair_complete", "ok",
            "attemptState=" + result.attemptState() + " status=" + status);
    }

    /** Mark the incident as terminal (no further transitions allowed). */
    public void markTerminal(String incidentId, UserIncidentStatus status) throws IOException {
        updateManifest(incidentId, manifest -> {
            manifest.put("userStatus", status.name());
            manifest.put("terminal", true);
        });
        appendTimeline(incidentId, "terminal", "ok", "status=" + status);
    }

    // ── Read ────────────────────────────────────────────────────────────

    /** List recent incidents (most recent first). Corrupted entries are skipped. */
    public List<IncidentSummary> recent(int limit) throws IOException {
        List<IncidentSummary> result = new ArrayList<>();
        try (var dirs = Files.list(incidentsDir)) {
            var entries = dirs
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> {
                    try {
                        return -Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return 0L;
                    }
                }))
                .toList();

            for (Path dir : entries) {
                if (result.size() >= limit) break;
                try {
                    IncidentSummary summary = readSummary(dir);
                    if (summary != null) result.add(summary);
                } catch (Exception e) {
                    log.warn("[incident-store] skipping corrupted incident {}: {}",
                        dir.getFileName(), e.getMessage());
                }
            }
        }
        return result;
    }

    /** Get full view of an incident. Read-only. */
    public InvestigationView inspect(String incidentId) throws IOException {
        Path dir = incidentDir(incidentId);
        if (!Files.exists(dir)) throw new IOException("incident not found: " + incidentId);

        ObjectNode manifest = readManifest(dir);
        if (manifest == null) throw new IOException("manifest not found for: " + incidentId);

        return buildView(manifest, dir);
    }

    /** Read the investigation result for an incident. */
    public RemoteIncidentResult readInvestigationResult(String incidentId) throws IOException {
        Path dir = incidentDir(incidentId);
        Path resultPath = dir.resolve("investigation-result.json");
        if (!Files.exists(resultPath)) return null;
        return MAPPER.readValue(resultPath.toFile(), RemoteIncidentResult.class);
    }

    /** Get the attempt store directory for an incident. */
    public Path attemptStoreDir(String incidentId) {
        return incidentDir(incidentId).resolve(".attempts");
    }

    /** Read the repair result from disk. */
    public com.clawkit.ops.loop.repair.RepairResult readRepairResult(String incidentId)
            throws IOException {
        Path dir = incidentDir(incidentId);
        Path repairPath = dir.resolve("repair-result.json");
        if (!Files.exists(repairPath)) return null;
        return MAPPER.readValue(repairPath.toFile(),
            com.clawkit.ops.loop.repair.RepairResult.class);
    }

    /** Read the manifest as a JsonNode. */
    public com.fasterxml.jackson.databind.JsonNode readManifest(String incidentId)
            throws IOException {
        Path dir = incidentDir(incidentId);
        return readManifest(dir);
    }

    /** Update specific fields in the manifest without rewriting everything. */
    public void updateManifestFields(String incidentId, String... keyValues) throws IOException {
        updateManifest(incidentId, manifest -> {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                manifest.put(keyValues[i], keyValues[i + 1]);
            }
        });
    }

    /** Read the report markdown. */
    public String readReport(String incidentId) throws IOException {
        Path dir = incidentDir(incidentId);
        Path reportPath = dir.resolve("report.md");
        if (!Files.exists(reportPath)) return null;
        return Files.readString(reportPath);
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private Path incidentDir(String incidentId) {
        return incidentsDir.resolve(incidentId);
    }

    private void updateManifest(String incidentId, ManifestUpdater updater) throws IOException {
        Path dir = incidentDir(incidentId);
        Path manifestPath = dir.resolve("manifest.json");
        ObjectNode manifest;
        if (Files.exists(manifestPath)) {
            JsonNode existing = MAPPER.readTree(manifestPath.toFile());
            manifest = existing.deepCopy();
        } else {
            manifest = MAPPER.createObjectNode();
            manifest.put("schemaVersion", MANIFEST_SCHEMA_VERSION);
            manifest.put("incidentId", incidentId);
            manifest.put("createdAt", Instant.now().toString());
        }
        manifest.put("updatedAt", Instant.now().toString());
        updater.apply(manifest);
        atomicWriteJson(manifestPath, manifest);
    }

    private ObjectNode readManifest(Path dir) throws IOException {
        Path manifestPath = dir.resolve("manifest.json");
        if (!Files.exists(manifestPath)) return null;
        JsonNode node = MAPPER.readTree(manifestPath.toFile());
        return node.deepCopy();
    }

    private IncidentSummary readSummary(Path dir) throws IOException {
        ObjectNode m = readManifest(dir);
        if (m == null) return null;
        String incidentId = m.path("incidentId").asText(dir.getFileName().toString());
        String targetId = m.path("targetId").asText("");
        String serviceId = m.has("serviceId") ? m.path("serviceId").asText() : "";
        UserIncidentStatus status = parseStatus(m.path("userStatus").asText("CREATED"));
        String briefSummary = m.path("summary").asText("");
        Instant createdAt = parseInstant(m.path("createdAt").asText(""));
        Instant updatedAt = parseInstant(m.path("updatedAt").asText(""));
        return new IncidentSummary(incidentId, targetId, serviceId, status, briefSummary,
            createdAt, updatedAt);
    }

    private InvestigationView buildView(ObjectNode manifest, Path dir) {
        String incidentId = manifest.path("incidentId").asText("");
        String targetId = manifest.path("targetId").asText("");
        String serviceId = manifest.has("serviceId") ? manifest.path("serviceId").asText() : "";
        UserIncidentStatus status = parseStatus(manifest.path("userStatus").asText("CREATED"));
        String summary = manifest.path("summary").asText("");
        boolean approvalRequired = "AWAITING_APPROVAL".equals(manifest.path("userStatus").asText(""));
        String actionExecuted = manifest.path("actionExecuted").asText("");
        String verificationSummary = manifest.path("verificationSummary").asText("");
        String nextAction = manifest.path("nextAction").asText("");
        Instant createdAt = parseInstant(manifest.path("createdAt").asText(""));
        Instant updatedAt = parseInstant(manifest.path("updatedAt").asText(""));
        String evidenceDir = "incidents/" + incidentId + "/";

        // Read report for diagnosis/recommendation
        String diagnosis = "未完成诊断";
        String recommendation = "";
        List<String> facts = List.of();
        try {
            String report = readReport(incidentId);
            if (report != null) {
                diagnosis = extractSection(report, "诊断结论");
                recommendation = extractSection(report, "建议");
            }
        } catch (IOException ignored) { }

        return new InvestigationView(incidentId, targetId, serviceId, status,
            summary, facts, diagnosis, recommendation, approvalRequired,
            actionExecuted, verificationSummary, nextAction,
            createdAt, updatedAt, evidenceDir);
    }

    /** Append a line to the timeline NDJSON. */
    void appendTimeline(String incidentId, String stage, String status, String detail)
            throws IOException {
        Path timelinePath = incidentDir(incidentId).resolve("timeline.ndjson");
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("schemaVersion", "1");
        entry.put("incidentId", incidentId);
        entry.put("timestamp", Instant.now().toString());
        entry.put("stage", stage);
        entry.put("status", status);
        entry.put("detail", detail != null ? detail : "");
        String line = MAPPER.writeValueAsString(entry) + "\n";
        Files.writeString(timelinePath, line,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ── Atomic I/O ──────────────────────────────────────────────────────

    static void atomicWriteJson(Path target, Object content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void atomicWriteText(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static UserIncidentStatus parseStatus(String s) {
        try { return UserIncidentStatus.valueOf(s); }
        catch (IllegalArgumentException e) { return UserIncidentStatus.CREATED; }
    }

    private static Instant parseInstant(String s) {
        try {
            return s != null && !s.isEmpty() ? Instant.parse(s) : Instant.EPOCH;
        } catch (Exception e) { return Instant.EPOCH; }
    }

    private static String extractSection(String markdown, String heading) {
        if (markdown == null) return "";
        String marker = "## " + heading;
        int start = markdown.indexOf(marker);
        if (start < 0) return "";
        int contentStart = markdown.indexOf('\n', start);
        if (contentStart < 0) return "";
        int nextHeading = markdown.indexOf("\n## ", contentStart + 1);
        String section = nextHeading > 0
            ? markdown.substring(contentStart, nextHeading)
            : markdown.substring(contentStart);
        return section.strip();
    }

    @Override
    public void close() {
        // no-op; individual incidents manage their own resources
    }

    @FunctionalInterface
    private interface ManifestUpdater {
        void apply(ObjectNode manifest);
    }
}
