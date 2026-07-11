package com.clawkit.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 管理 benchmark fixture 的复制和清理。
 *
 * <p>每个 case 使用独立的临时 workspace：
 * <ol>
 *   <li>从 fixture 复制初始文件到临时目录</li>
 *   <li>执行 Agent</li>
 *   <li>成功时清理，失败时保留诊断产物</li>
 * </ol>
 */
public class FixtureManager {

    private static final Logger log = LoggerFactory.getLogger(FixtureManager.class);

    private final Path baseWorkDir;

    public FixtureManager(Path baseWorkDir) {
        this.baseWorkDir = baseWorkDir;
    }

    /** 创建带有 fixture 文件的临时 workspace */
    public Path setup(Fixture fixture, String caseId) throws Exception {
        Path workDir = baseWorkDir.resolve(caseId);
        Files.createDirectories(workDir);
        if (fixture != null) {
            fixture.apply(workDir);
        }
        log.debug("Fixture setup: {} → {}", caseId, workDir);
        return workDir;
    }

    /** 成功后清理 */
    public void cleanup(Path workDir) {
        try {
            if (Files.exists(workDir)) {
                try (var stream = Files.walk(workDir)) {
                    stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Fixture cleanup failed: {}", workDir, e);
        }
    }

    /** 保留失败产物的路径 */
    public Path failureArtifactDir(Path workDir) {
        return workDir; // keep as-is for diagnostics
    }
}
