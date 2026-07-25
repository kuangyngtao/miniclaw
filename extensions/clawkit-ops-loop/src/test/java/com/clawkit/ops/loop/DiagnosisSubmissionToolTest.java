package com.clawkit.ops.loop;

import com.clawkit.tools.Result;
import com.clawkit.tools.ToolLoopPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisSubmissionToolTest {
    @Test
    void storesAValidatedStructuredDiagnosis() {
        var tool = new DiagnosisSubmissionTool();
        Result<String> result = tool.execute("""
            {"rootCauseCode":"CPU_PRESSURE","confidence":0.9,
             "supportingEvidence":["e-1"],"contradictingEvidence":[],
             "alternatives":["INCONCLUSIVE"],"missingEvidence":[],
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "schemaVersion":"2","diagnosisStatus":"CONFIRMED",
             "currentCondition":"ACTIVE","evaluatedAt":"2026-07-22T00:00:00Z",
             "resolutionAttribution":"NONE"}
            """);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(tool.latest().rootCauseCode()).isEqualTo("CPU_PRESSURE");
        assertThat(((Result.Ok<String>) result).data()).contains("\"status\":\"accepted\"");
        assertThat(tool.metadata().controlPolicy().loopPolicy())
            .isEqualTo(ToolLoopPolicy.COMPLETE_RUN_ON_SUCCESS);
    }

    @Test
    void rejectsInvalidDiagnosisWithoutReplacingTheLatestValue() {
        var tool = new DiagnosisSubmissionTool();

        Result<String> result = tool.execute("{\"confidence\":101}");

        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(tool.latest()).isNull();
    }

    @Test
    void rejectsASecondSubmission() {
        var tool = new DiagnosisSubmissionTool();
        String diagnosis = """
            {"rootCauseCode":"INCONCLUSIVE","confidence":0.3,
             "supportingEvidence":["e-1"],"contradictingEvidence":[],
             "alternatives":[],"missingEvidence":[],
             "recommendedActionCode":"ESCALATE","claimedResolved":false,
             "schemaVersion":"2","diagnosisStatus":"INCONCLUSIVE",
             "currentCondition":"ACTIVE","evaluatedAt":"2026-07-22T00:00:00Z",
             "resolutionAttribution":"NONE"}
            """;

        assertThat(tool.execute(diagnosis)).isInstanceOf(Result.Ok.class);
        assertThat(tool.execute(diagnosis)).isInstanceOf(Result.Err.class);
    }
}
