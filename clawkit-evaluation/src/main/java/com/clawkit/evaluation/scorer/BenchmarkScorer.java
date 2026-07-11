package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;

import java.nio.file.Path;

@FunctionalInterface
public interface BenchmarkScorer {
    Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir);
}
