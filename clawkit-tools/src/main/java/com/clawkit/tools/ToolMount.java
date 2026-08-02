package com.clawkit.tools;

import java.util.List;

/**
 * A namespace-scoped mount of tools in a {@link ToolRegistry}.
 *
 * <p>Created by {@link ToolRegistry#mount(String, java.util.Collection)}.
 * The mount owns its tools — closing the mount removes them from the
 * registry. Idempotent close, concurrent-safe.
 *
 * <p>Design: REMOTE-0 §9.1.
 */
public interface ToolMount extends AutoCloseable {

    /** The owner ID that was passed to {@code ToolRegistry.mount()}. */
    String ownerId();

    /** The names of the tools in this mount (at creation time). */
    List<String> toolNames();

    /**
     * Remove all tools belonging to this mount from the registry.
     * Idempotent — subsequent calls are no-ops.
     * Only removes tools that still belong to this mount instance;
     * does not remove later-registered tools with the same names.
     */
    @Override
    void close();
}
