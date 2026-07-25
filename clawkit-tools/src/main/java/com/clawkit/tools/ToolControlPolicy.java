package com.clawkit.tools;

/** Engine control-plane policy declared by trusted local tool metadata. */
public record ToolControlPolicy(ToolLoopPolicy loopPolicy) {
    public static final ToolControlPolicy DEFAULT = new ToolControlPolicy(ToolLoopPolicy.CONTINUE);
    public static final ToolControlPolicy COMPLETE_ON_SUCCESS =
        new ToolControlPolicy(ToolLoopPolicy.COMPLETE_RUN_ON_SUCCESS);

    public ToolControlPolicy {
        if (loopPolicy == null) loopPolicy = ToolLoopPolicy.CONTINUE;
    }
}
