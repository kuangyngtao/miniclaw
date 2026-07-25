package com.clawkit.context;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicPromptAssemblyTest {

    @Test
    void sortsCatalogAndActiveSkillsByName() {
        Map<String, String> catalogEntries = new HashMap<>();
        catalogEntries.put("zeta", "last");
        catalogEntries.put("alpha", "first");
        Map<String, String> active = new HashMap<>();
        active.put("zeta", "ZETA PROMPT");
        active.put("alpha", "ALPHA PROMPT");

        String prompt = PromptAssembly.assemble("kernel", null,
            new SkillCatalog(catalogEntries), active, "mode", null);

        assertThat(prompt.indexOf("- alpha: first")).isLessThan(prompt.indexOf("- zeta: last"));
        assertThat(prompt.indexOf("ALPHA PROMPT")).isLessThan(prompt.indexOf("ZETA PROMPT"));
    }
}
