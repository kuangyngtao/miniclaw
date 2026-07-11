package com.clawkit.evaluation;

import java.nio.file.Path;
import java.util.Map;

/**
 * Benchmark case 工作区 fixture。
 * 定义在临时 workspace 中应创建的文件。
 */
public record Fixture(
    String name,
    Map<String, String> files
) {
    public static Fixture of(String name, Map<String, String> files) {
        return new Fixture(name, files);
    }

    public static Fixture empty() {
        return new Fixture("empty", Map.of());
    }

    /** 设置 fixture 文件到目标目录 */
    public void apply(Path targetDir) throws Exception {
        for (var entry : files.entrySet()) {
            Path file = targetDir.resolve(entry.getKey());
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, entry.getValue());
        }
    }
}
