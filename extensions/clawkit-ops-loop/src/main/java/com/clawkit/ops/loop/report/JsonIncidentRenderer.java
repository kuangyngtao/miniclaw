package com.clawkit.ops.loop.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Renders a {@link HumanIncidentReport} as deterministic JSON.
 *
 * <p>M2-5. Shares the same presentation model as Markdown and Feishu renderers.
 */
public final class JsonIncidentRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonIncidentRenderer() {}

    public static String render(HumanIncidentReport report) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed: " + e.getMessage() + "\"}";
        }
    }
}
