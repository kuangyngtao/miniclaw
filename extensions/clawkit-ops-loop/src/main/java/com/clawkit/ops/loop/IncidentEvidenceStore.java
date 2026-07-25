package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class IncidentEvidenceStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final List<Evidence> evidence = new ArrayList<>();

    public IncidentEvidenceStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public synchronized void append(Evidence item) throws IOException {
        if (evidence.stream().anyMatch(existing -> existing.evidenceId().equals(item.evidenceId()))) {
            throw new IllegalArgumentException("duplicate evidenceId: " + item.evidenceId());
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writeValueAsString(item) + System.lineSeparator(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        evidence.add(item);
    }

    public synchronized List<Evidence> snapshot() {
        return List.copyOf(evidence);
    }
}
