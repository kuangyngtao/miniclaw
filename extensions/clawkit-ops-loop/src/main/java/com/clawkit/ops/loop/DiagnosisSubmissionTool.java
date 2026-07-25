package com.clawkit.ops.loop;

import com.clawkit.tools.Result;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolControlPolicy;
import com.clawkit.tools.ToolMetadata;
import com.clawkit.tools.ToolMetadataProvenance;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory finalizer that avoids depending on free-form assistant text for the diagnosis contract. */
final class DiagnosisSubmissionTool implements Tool {
    private final AtomicReference<Diagnosis> latest = new AtomicReference<>();

    @Override public String name() { return "submit_diagnosis"; }

    @Override public String description() {
        return "Submit the final Diagnosis v2 object exactly once after reviewing evidence.";
    }

    @Override public String inputSchema() {
        return """
            {"type":"object","additionalProperties":false,
             "required":["rootCauseCode","confidence","supportingEvidence","contradictingEvidence",
               "alternatives","missingEvidence","recommendedActionCode","claimedResolved","schemaVersion",
               "diagnosisStatus","currentCondition","evaluatedAt","resolutionAttribution"],
             "properties":{
               "rootCauseCode":{"enum":["DB_LOCK_WAIT","CPU_PRESSURE","CONNECTION_EXHAUSTION","INCONCLUSIVE"]},
               "confidence":{"type":"number","minimum":0,"maximum":1},
               "supportingEvidence":{"type":"array","items":{"type":"string"}},
               "contradictingEvidence":{"type":"array","items":{"type":"string"}},
               "alternatives":{"type":"array","items":{"type":"string"}},
               "missingEvidence":{"type":"array","items":{"type":"string"}},
               "recommendedActionCode":{"const":"ESCALATE"},
               "claimedResolved":{"const":false},
               "schemaVersion":{"type":"string"},
               "diagnosisStatus":{"enum":["CONFIRMED","PROBABLE","INCONCLUSIVE"]},
               "currentCondition":{"enum":["ACTIVE","RECOVERED","UNKNOWN"]},
               "evaluatedAt":{"type":"string","format":"date-time"},
               "resolutionAttribution":{"enum":["NONE","SELF_RECOVERED"]}
             }}
            """;
    }

    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolMetadata metadata() {
        ToolMetadata base = ToolMetadata.from(this);
        return new ToolMetadata(base.name(), base.description(), base.inputSchema(),
            base.outputSchema(), base.behavior(), base.executionPolicy(),
            ToolMetadataProvenance.builtin(name()), ToolControlPolicy.COMPLETE_ON_SUCCESS);
    }

    @Override @Deprecated
    public Result<String> execute(String arguments) {
        try {
            Diagnosis parsed = OpsBlindAgentMain.parseDiagnosis(arguments);
            if (!latest.compareAndSet(null, parsed)) {
                return new Result.Err<>(new Result.ErrorInfo(
                    "DIAGNOSIS_ALREADY_SUBMITTED", "Diagnosis v2 was already submitted"));
            }
            var confirmation = new LinkedHashMap<String, Object>();
            confirmation.put("status", "accepted");
            confirmation.put("schemaVersion", parsed.schemaVersion());
            confirmation.put("rootCauseCode", parsed.rootCauseCode());
            confirmation.put("diagnosisStatus", parsed.diagnosisStatus().name());
            confirmation.put("currentCondition", parsed.currentCondition().name());
            confirmation.put("confidence", parsed.confidence());
            return new Result.Ok<>(Tool.MAPPER.writeValueAsString(confirmation));
        } catch (Exception error) {
            return new Result.Err<>(new Result.ErrorInfo(
                "INVALID_DIAGNOSIS", "Diagnosis v2 validation failed: " + error.getMessage()));
        }
    }

    Diagnosis latest() { return latest.get(); }
}
