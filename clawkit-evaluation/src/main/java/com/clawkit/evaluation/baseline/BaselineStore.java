package com.clawkit.evaluation.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 基线 JSON 读写。
 * 禁止自动更新 baseline——必须人工 review candidate 后替换。
 */
public class BaselineStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public static void save(Path path, BaselineData baseline) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), baseline);
    }

    public static Optional<BaselineData> load(Path path) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(path.toFile(), BaselineData.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
