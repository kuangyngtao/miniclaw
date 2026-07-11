package com.clawkit.observability;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写入 JSON 文件。
 *
 * <p>流程：写 .tmp → flush → ATOMIC_MOVE → 不支持时退化为 REPLACE_EXISTING。
 * 保证 CLI 不会读到半个 JSON。
 */
public final class AtomicJsonWriter {

    private final ObjectMapper mapper;

    public AtomicJsonWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AtomicJsonWriter() {
        this(new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    /**
     * 原子写入 JSON 到目标路径。
     *
     * @param target 目标文件路径
     * @param value  要序列化的对象
     */
    public void write(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
