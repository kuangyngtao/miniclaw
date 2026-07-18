package com.clawkit.engine;

import com.clawkit.context.ContextPipeline;
import com.clawkit.observability.RunRecorder;
import com.clawkit.tools.Registry;
import java.util.Objects;

public record AgentRuntimeDependencies(
    ProviderGateway providerGateway,
    ContextPipeline contextPipeline,
    Registry registry,
    int contextWindow,
    String encoding,
    RunRecorder runRecorder,
    MemoryHooks memoryHooks,
    SkillRuntime skillRuntime
) {
    public static SkillRuntime emptySkillRuntime() { return EmptySkillRuntime.INSTANCE; }
    public static MemoryHooks noopMemoryHooks() { return NoopMemoryHooks.INSTANCE; }
    public AgentRuntimeDependencies {
        Objects.requireNonNull(providerGateway, "providerGateway required");
        Objects.requireNonNull(registry, "registry required");
        if (contextWindow <= 0) throw new IllegalArgumentException("contextWindow must be > 0");
        if (encoding == null || encoding.isBlank()) encoding = "cl100k_base";
        if (runRecorder == null) runRecorder = new com.clawkit.observability.CompositeRunRecorder();
        if (memoryHooks == null) memoryHooks = NoopMemoryHooks.INSTANCE;
        if (skillRuntime == null) skillRuntime = EmptySkillRuntime.INSTANCE;
    }

    /** 兼容构造器 */
    public AgentRuntimeDependencies(
        ProviderGateway providerGateway, ContextPipeline contextPipeline,
        Registry registry, int contextWindow, String encoding
    ) {
        this(providerGateway, contextPipeline, registry, contextWindow, encoding,
            new com.clawkit.observability.CompositeRunRecorder(),
            NoopMemoryHooks.INSTANCE, EmptySkillRuntime.INSTANCE);
    }
}

final class NoopMemoryHooks implements MemoryHooks {
    static final NoopMemoryHooks INSTANCE = new NoopMemoryHooks();
    @Override public java.util.List<com.clawkit.tools.schema.Message> beforeRun(MemoryRecallRequest r) { return java.util.List.of(); }
    @Override public MemorySaveResult afterRun(MemoryExtractionRequest r) { return MemorySaveResult.EMPTY; }
}

final class EmptySkillRuntime implements SkillRuntime {
    static final EmptySkillRuntime INSTANCE = new EmptySkillRuntime();
    @Override public com.clawkit.context.SkillCatalog catalog() { return com.clawkit.context.SkillCatalog.empty(); }
    @Override public SkillLoadResult load(String n) { return SkillLoadResult.failed(n, "not implemented"); }
    @Override public SkillUnloadResult unload(String n) { return new SkillUnloadResult(n, false); }
    @Override public java.util.List<com.clawkit.tools.schema.Message> activeContext() { return java.util.List.of(); }
    @Override public boolean isLoaded(String n) { return false; }
    @Override public void rebuildCatalog() {}
}
