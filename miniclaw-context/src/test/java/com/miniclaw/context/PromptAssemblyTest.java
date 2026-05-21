package com.miniclaw.context;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptAssemblyTest {

    private static final String L1 = "You are miniclaw.";
    private static final String L4 = "AUTO mode.";

    // ─── basic assembly ──────────────────────────────────────────────

    @Test
    void shouldAssembleAllLayers() {
        SkillCatalog catalog = new SkillCatalog(Map.of("feishu", "feishu docs"));
        Map<String, String> active = Map.of("feishu", "# Feishu Skill\nfull prompt");

        String result = PromptAssembly.assemble(
            L1, "## Workspace Rules\nrule1", catalog, active, L4, "- [mem](f.md)");

        assertThat(result).startsWith(L1)
            .contains("## Workspace Rules")
            .contains("## Available Skills")
            .contains("- feishu: feishu docs")
            .contains("## Loaded Skills")
            .contains("# Feishu Skill")
            .contains(L4)
            .contains("## Available Memory")
            .contains("- [mem](f.md)");
    }

    @Test
    void shouldAssembleMinimal() {
        String result = PromptAssembly.assemble(L1, null, null, null, L4, null);
        assertThat(result).isEqualTo(L1 + "\n\n" + L4);
    }

    @Test
    void shouldSkipEmptyOptionalLayers() {
        String result = PromptAssembly.assemble(L1, "", SkillCatalog.empty(), Map.of(), L4, "");
        assertThat(result).isEqualTo(L1 + "\n\n" + L4);
    }

    @Test
    void shouldIncludeWorkspaceRules() {
        String rules = "## Rules\n- no emoji\n- kebab-case";
        String result = PromptAssembly.assemble(L1, rules, null, null, L4, null);
        assertThat(result).contains("## Rules\n- no emoji\n- kebab-case");
    }

    @Test
    void shouldIncludeSkillCatalogWithoutActiveSkills() {
        SkillCatalog catalog = new SkillCatalog(Map.of("a", "desc A", "b", "desc B"));
        String result = PromptAssembly.assemble(L1, null, catalog, Map.of(), L4, null);
        assertThat(result).contains("## Available Skills")
            .contains("- a: desc A")
            .contains("- b: desc B")
            .doesNotContain("## Loaded Skills");
    }

    @Test
    void shouldIncludeActiveSkills() {
        SkillCatalog catalog = new SkillCatalog(Map.of("x", "desc"));
        Map<String, String> active = Map.of("x", "### Skill X\nInstructions here");
        String result = PromptAssembly.assemble(L1, null, catalog, active, L4, null);
        assertThat(result).contains("## Loaded Skills")
            .contains("### Skill X");
    }

    @Test
    void shouldIncludeMemoryIndex() {
        String mem = "- [coding-style](coding-style.md) — use tabs";
        String result = PromptAssembly.assemble(L1, null, null, null, L4, mem);
        assertThat(result).contains("## Available Memory")
            .contains(mem);
    }

    @Test
    void shouldNotIncludeMemoryWhenBlank() {
        String result = PromptAssembly.assemble(L1, null, null, null, L4, "  ");
        assertThat(result).doesNotContain("Available Memory");
    }
}
