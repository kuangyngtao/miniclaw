package com.clawkit.ops.delivery;

/**
 * Progress callback implemented by the CLI layer.
 * Delivery module does not depend on JLine.
 *
 * <p>OPS-PRODUCT-LOOP-1 §5.3.
 */
public interface InvestigationInteraction {

    /** Called at each progress step (discovery, diagnosis, etc.). */
    void onProgress(InvestigationProgress progress);

    /**
     * Request user approval for a repair action.
     *
     * @return the user's decision — never null
     */
    ApprovalDecision requestApproval(ApprovalPrompt prompt);

    /** Called with the final result (success, failure, or aborted). */
    void onFinalResult(InvestigationView result);
}
