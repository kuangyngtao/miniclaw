package com.miniclaw.context;

import java.util.Map;

/**
 * Pure static assembler: 5 layers → 1 system prompt string.
 * No state, no caching — the LLM provider's prompt cache handles deduplication.
 */
public final class PromptAssembly {

    private PromptAssembly() {}

    public static String assemble(
            String l1Kernel,
            String l2WorkspaceRules,
            SkillCatalog skillCatalog,
            Map<String, String> activeSkills,
            String l4ModePrompt,
            String l5MemoryIndex) {

        StringBuilder sb = new StringBuilder(l1Kernel);

        if (l2WorkspaceRules != null && !l2WorkspaceRules.isBlank()) {
            sb.append("\n\n").append(l2WorkspaceRules);
        }

        if (skillCatalog != null && !skillCatalog.isEmpty()) {
            sb.append("\n\n## Available Skills\n\n").append(skillCatalog.toPrompt());
        }

        if (activeSkills != null && !activeSkills.isEmpty()) {
            sb.append("\n\n## Loaded Skills\n");
            for (String prompt : activeSkills.values()) {
                sb.append("\n").append(prompt);
            }
        }

        sb.append("\n\n").append(l4ModePrompt);

        if (l5MemoryIndex != null && !l5MemoryIndex.isBlank()) {
            sb.append("\n\n## Available Memory\n\n").append(l5MemoryIndex);
        }

        return sb.toString();
    }
}
