package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateIncidentDirectory() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String id = store.createIncident("test-server", "order-api");

        assertThat(id).startsWith("inc-test-server-");
        assertThat(Files.exists(tempDir.resolve("incidents").resolve(id))).isTrue();
        assertThat(Files.exists(tempDir.resolve("incidents").resolve(id).resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("incidents").resolve(id).resolve("timeline.ndjson"))).isTrue();

        store.close();
    }

    @Test
    void shouldWriteManifestWithCorrectFields() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String id = store.createIncident("test-server", "order-api");

        Path manifest = tempDir.resolve("incidents").resolve(id).resolve("manifest.json");
        String content = Files.readString(manifest);
        assertThat(content).contains("test-server");
        assertThat(content).contains("order-api");
        assertThat(content).contains("CREATED");
        assertThat(content).contains("schemaVersion");

        store.close();
    }

    @Test
    void shouldAppendTimeline() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String id = store.createIncident("test-server", "order-api");

        store.appendTimeline(id, "test_event", "ok", "test detail");

        Path timeline = tempDir.resolve("incidents").resolve(id).resolve("timeline.ndjson");
        List<String> lines = Files.readAllLines(timeline);
        assertThat(lines).hasSize(2); // incident_created + test_event
        assertThat(lines.get(1)).contains("test_event");

        store.close();
    }

    @Test
    void shouldListIncidentsByMostRecentFirst() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);

        String id1 = store.createIncident("test-server", "order-api");
        Thread.sleep(50); // ensure different timestamps on some filesystems
        String id2 = store.createIncident("staging", "order-api");
        Thread.sleep(50);
        store.markTerminal(id1, UserIncidentStatus.INCONCLUSIVE);

        List<IncidentSummary> recent = store.recent(10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).incidentId()).isEqualTo(id1); // most recently updated
        assertThat(recent.get(0).status()).isEqualTo(UserIncidentStatus.INCONCLUSIVE);

        store.close();
    }

    @Test
    void shouldInspectIncident() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String id = store.createIncident("test-server", "order-api");

        InvestigationView view = store.inspect(id);
        assertThat(view.incidentId()).isEqualTo(id);
        assertThat(view.targetId()).isEqualTo("test-server");
        assertThat(view.serviceId()).isEqualTo("order-api");
        assertThat(view.status()).isEqualTo(UserIncidentStatus.CREATED);

        store.close();
    }

    @Test
    void shouldHandleMissingIncidentGracefully() {
        IncidentStore store = new IncidentStore(tempDir);
        try {
            InvestigationView view = store.inspect("nonexistent");
            // Should throw
            assertThat(view).isNull();
        } catch (Exception e) {
            assertThat(e).isInstanceOf(Exception.class);
        }
        store.close();
    }

    @Test
    void shouldNotPersistApiKeys() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String id = store.createIncident("test-server", "order-api");

        Path manifest = tempDir.resolve("incidents").resolve(id).resolve("manifest.json");
        String content = Files.readString(manifest);
        // These should never appear in any persisted file
        assertThat(content).doesNotContain("sk-");
        assertThat(content).doesNotContain("api_key");
        assertThat(content).doesNotContain("Authorization");
        assertThat(content).doesNotContain("Bearer");

        store.close();
    }

    @Test
    void shouldNotFailOnCorruptedIncident() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String goodId = store.createIncident("good", "order-api");

        // Create a corrupted incident directory with no manifest
        Path badDir = tempDir.resolve("incidents").resolve("inc-corrupted-00000000");
        Files.createDirectories(badDir);

        // recent() should skip the corrupted one
        List<IncidentSummary> recent = store.recent(10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).incidentId()).isEqualTo(goodId);

        store.close();
    }

    @Test
    void strictJsonShouldParse() throws Exception {
        IncidentStore store = new IncidentStore(tempDir);
        String id = store.createIncident("test-server", "order-api");

        Path manifest = tempDir.resolve("incidents").resolve(id).resolve("manifest.json");
        String content = Files.readString(manifest);
        // Verify valid JSON
        var node = MAPPER.readTree(content);
        assertThat(node.has("incidentId")).isTrue();
        assertThat(node.has("schemaVersion")).isTrue();

        Path timeline = tempDir.resolve("incidents").resolve(id).resolve("timeline.ndjson");
        String tlContent = Files.readString(timeline);
        // Each line must be valid JSON
        for (String line : tlContent.split("\n")) {
            if (!line.isBlank()) {
                MAPPER.readTree(line); // should not throw
            }
        }

        store.close();
    }
}
