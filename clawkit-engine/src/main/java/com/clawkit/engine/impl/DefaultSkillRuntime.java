package com.clawkit.engine.impl;

import com.clawkit.context.SkillCatalog;
import com.clawkit.context.SkillLoader;
import com.clawkit.engine.SkillRuntime;
import com.clawkit.tools.schema.Message;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Production SkillRuntime backed by the filesystem SkillLoader. */
public final class DefaultSkillRuntime implements SkillRuntime {
    private final SkillLoader loader;
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private volatile SkillCatalog catalog = SkillCatalog.empty();

    public DefaultSkillRuntime(SkillLoader loader) {
        this.loader = java.util.Objects.requireNonNull(loader, "loader required");
        rebuildCatalog();
    }

    @Override public SkillCatalog catalog() { return catalog; }

    @Override public SkillLoadResult load(String name) {
        if (name == null || name.isBlank()) return SkillLoadResult.failed("", "skill name is required");
        String prompt = loader.loadPrompt(name);
        if (prompt == null || prompt.isBlank()) return SkillLoadResult.failed(name, "skill not found");
        active.put(name, prompt);
        return SkillLoadResult.success(name);
    }

    @Override public SkillUnloadResult unload(String name) {
        return new SkillUnloadResult(name, name != null && active.remove(name) != null);
    }

    @Override public List<Message> activeContext() {
        return active.values().stream().map(Message::system).toList();
    }

    @Override public boolean isLoaded(String name) { return active.containsKey(name); }

    @Override public void rebuildCatalog() { catalog = loader.buildCatalog(); }
}
