package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Map;

/** Incident-level timeline. Payloads contain references, never duplicated tool/provider bodies. */
public final class IncidentFlightRecorder {
    private final Path path;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public IncidentFlightRecorder(Path path, Clock clock) {
        this.path = path.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public synchronized void record(String type, String runEventReference, Map<String, ?> fields)
        throws IOException {
        var event = mapper.createObjectNode();
        event.put("schemaVersion", "1");
        event.put("at", clock.instant().toString());
        event.put("type", type);
        if (runEventReference != null) event.put("runEventReference", runEventReference);
        event.set("fields", mapper.valueToTree(fields));
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writeValueAsString(event) + System.lineSeparator(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
