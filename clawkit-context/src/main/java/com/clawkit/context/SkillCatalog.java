package com.clawkit.context;

import java.util.Map;

/**
 * Lightweight catalog of available skills (name → one-line description).
 * Always visible in the system prompt so the LLM knows what it can load.
 */
public record SkillCatalog(Map<String, String> entries) {

    public static SkillCatalog empty() {
        return new SkillCatalog(Map.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Format entries for system prompt injection. */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder();
        for (var e : entries.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}
