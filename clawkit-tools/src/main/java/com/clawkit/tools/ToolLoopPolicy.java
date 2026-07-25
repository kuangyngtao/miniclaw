package com.clawkit.tools;

/** Controls whether a successful tool result may terminate the agent loop. */
public enum ToolLoopPolicy {
    CONTINUE,
    COMPLETE_RUN_ON_SUCCESS
}
