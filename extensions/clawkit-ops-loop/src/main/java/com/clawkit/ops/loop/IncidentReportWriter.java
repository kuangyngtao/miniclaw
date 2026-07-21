package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class IncidentReportWriter {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void write(Path outputDirectory, IncidentReport report) throws IOException {
        Files.createDirectories(outputDirectory);
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputDirectory.resolve("incident.json").toFile(), report);
        Files.writeString(outputDirectory.resolve("incident.md"), markdown(report),
            StandardCharsets.UTF_8);
    }

    private String markdown(IncidentReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# OPS Incident ").append(report.incidentId()).append("\n\n");
        md.append("- State: `").append(report.state()).append("`\n");
        md.append("- Run: `").append(report.runId()).append("`\n");
        md.append("- Root cause: `").append(report.diagnosis().rootCauseCode()).append("`\n");
        md.append("- Confidence: ").append(report.diagnosis().confidence()).append("\n");
        md.append("- Claimed resolved: ").append(report.diagnosis().claimedResolved()).append("\n\n");
        md.append("## Evidence\n\n");
        for (Evidence evidence : report.evidenceBundle().evidence()) {
            md.append("- `").append(evidence.evidenceId()).append("` ")
                .append(evidence.type()).append(" / ").append(evidence.scope())
                .append(" / ").append(evidence.freshness())
                .append(" ([runtime event](").append(evidence.rawReference()).append("))\n");
        }
        md.append("\n## Diagnosis\n\n");
        md.append("- Supporting evidence: ")
            .append(String.join(", ", report.diagnosis().supportingEvidence())).append("\n");
        md.append("- Contradicting evidence: ")
            .append(String.join(", ", report.diagnosis().contradictingEvidence())).append("\n");
        md.append("- Missing evidence: ")
            .append(String.join(", ", report.diagnosis().missingEvidence())).append("\n");
        md.append("- Recommended action: `")
            .append(report.diagnosis().recommendedActionCode()).append("`\n");
        return md.toString();
    }
}
