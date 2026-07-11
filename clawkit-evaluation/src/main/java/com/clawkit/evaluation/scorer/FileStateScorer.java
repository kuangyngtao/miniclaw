package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 验证工作区文件状态——存在性、内容匹配。
 */
public class FileStateScorer implements BenchmarkScorer {

    private final Map<String, String> expectedFiles;

    public FileStateScorer(Map<String, String> expectedFiles) {
        this.expectedFiles = Map.copyOf(expectedFiles);
    }

    public static FileStateScorer exact(Map<String, String> expectedFiles) {
        return new FileStateScorer(expectedFiles);
    }

    @Override
    public Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir) {
        if (runArtifactDir == null) {
            return Score.notApplicable("FileStateScorer");
        }

        for (var entry : expectedFiles.entrySet()) {
            Path file = runArtifactDir.resolve(entry.getKey());
            if (!Files.exists(file)) {
                return Score.fail("FileStateScorer", "exists", "missing",
                    "Expected file not found: " + entry.getKey());
            }
            try {
                String content = Files.readString(file);
                if (!content.equals(entry.getValue())) {
                    return Score.fail("FileStateScorer", entry.getValue(), content,
                        "File content mismatch: " + entry.getKey());
                }
            } catch (Exception e) {
                return Score.fail("FileStateScorer", "readable", "error",
                    "Cannot read " + entry.getKey() + ": " + e.getMessage());
            }
        }
        return Score.pass("FileStateScorer", 1.0,
            "All " + expectedFiles.size() + " files verified");
    }
}
