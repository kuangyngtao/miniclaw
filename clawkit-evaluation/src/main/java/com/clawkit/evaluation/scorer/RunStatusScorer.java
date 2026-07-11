package com.clawkit.evaluation.scorer;

import com.clawkit.evaluation.BenchmarkResult;
import com.clawkit.evaluation.BenchmarkSpec;
import com.clawkit.observability.RunStatus;

import java.nio.file.Path;

/**
 * 验证 run 终止状态是否为 COMPLETED（或预期状态）。
 */
public class RunStatusScorer implements BenchmarkScorer {

    private final RunStatus expectedStatus;

    public RunStatusScorer() {
        this(RunStatus.COMPLETED);
    }

    public RunStatusScorer(RunStatus expectedStatus) {
        this.expectedStatus = expectedStatus;
    }

    @Override
    public Score score(BenchmarkSpec spec, BenchmarkResult result, Path runArtifactDir) {
        if (result.metrics() == null) {
            return Score.fail("RunStatusScorer", expectedStatus.name(), "null",
                "No metrics collected — engine may have crashed");
        }
        var actualStatus = result.summary().status();
        if (actualStatus == expectedStatus) {
            return Score.pass("RunStatusScorer", 1.0,
                "status=" + actualStatus.name() + " as expected");
        }
        return Score.fail("RunStatusScorer",
            expectedStatus.name(), actualStatus.name(),
            "Expected " + expectedStatus + " but got " + actualStatus);
    }
}
