package com.miniclaw.context;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLoaderTest {

    // ─── parseFrontmatter ────────────────────────────────────────────

    @Test
    void shouldParseFrontmatter() {
        String content = "---\nname: my-skill\ndescription: does stuff\n---\n# Body";
        Map<String, String> fm = SkillLoader.parseFrontmatter(content);
        assertThat(fm).containsEntry("name", "my-skill")
            .containsEntry("description", "does stuff");
    }

    @Test
    void shouldParseQuotedValues() {
        String content = "---\nname: \"my-skill\"\ndescription: 'does stuff'\n---\nBody";
        Map<String, String> fm = SkillLoader.parseFrontmatter(content);
        assertThat(fm).containsEntry("name", "my-skill")
            .containsEntry("description", "does stuff");
    }

    @Test
    void shouldReturnEmptyForNoFrontmatter() {
        assertThat(SkillLoader.parseFrontmatter("# Just markdown")).isEmpty();
        assertThat(SkillLoader.parseFrontmatter(null)).isEmpty();
        assertThat(SkillLoader.parseFrontmatter("")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnclosedFrontmatter() {
        String content = "---\nname: x\ndescription: y\n(no closing)";
        assertThat(SkillLoader.parseFrontmatter(content)).isEmpty();
    }

    // ─── stripFrontmatter ────────────────────────────────────────────

    @Test
    void shouldStripFrontmatter() {
        String content = "---\nname: s\ndescription: d\n---\n\n# Real Body\ncontent";
        String body = SkillLoader.stripFrontmatter(content);
        assertThat(body).isEqualTo("\n\n# Real Body\ncontent");
    }

    @Test
    void shouldReturnOriginalIfNoFrontmatter() {
        String content = "# Just a doc";
        assertThat(SkillLoader.stripFrontmatter(content)).isEqualTo(content);
    }

    @Test
    void shouldReturnEmptyForNull() {
        assertThat(SkillLoader.stripFrontmatter(null)).isEmpty();
    }

    // ─── buildCatalog ────────────────────────────────────────────────

    @Test
    void shouldBuildCatalogFromUserDir(@TempDir Path userDir) throws Exception {
        Path skillDir = userDir.resolve("my-skill");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: my-skill\ndescription: does things\n---\n# Content");

        SkillLoader loader = new SkillLoader(userDir, null);
        SkillCatalog catalog = loader.buildCatalog();

        assertThat(catalog.entries()).containsEntry("my-skill", "does things");
    }

    @Test
    void shouldOverrideWithProjectLevel(@TempDir Path userDir, @TempDir Path projectDir) throws Exception {
        // User-level
        Path userSkill = userDir.resolve("shared-skill");
        Files.createDirectory(userSkill);
        Files.writeString(userSkill.resolve("SKILL.md"),
            "---\nname: shared-skill\ndescription: user version\n---\n# User");

        // Project-level (should win)
        Path projSkill = projectDir.resolve("shared-skill");
        Files.createDirectory(projSkill);
        Files.writeString(projSkill.resolve("SKILL.md"),
            "---\nname: shared-skill\ndescription: project version\n---\n# Project");

        SkillLoader loader = new SkillLoader(userDir, projectDir);
        SkillCatalog catalog = loader.buildCatalog();

        assertThat(catalog.entries()).containsEntry("shared-skill", "project version");
    }

    @Test
    void shouldSkipDirsWithoutSkillMd(@TempDir Path userDir) throws Exception {
        Path emptyDir = userDir.resolve("not-a-skill");
        Files.createDirectory(emptyDir);

        Path validDir = userDir.resolve("valid-skill");
        Files.createDirectory(validDir);
        Files.writeString(validDir.resolve("SKILL.md"),
            "---\nname: valid-skill\ndescription: ok\n---\n# Ok");

        SkillLoader loader = new SkillLoader(userDir, null);
        SkillCatalog catalog = loader.buildCatalog();

        assertThat(catalog.entries()).hasSize(1).containsKey("valid-skill");
    }

    @Test
    void shouldListAllSkills(@TempDir Path userDir) throws Exception {
        Path skillDir = userDir.resolve("demo-skill");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: demo-skill\ndescription: a demo\n---\n# Demo");

        SkillLoader loader = new SkillLoader(userDir, null);
        List<SkillLoader.SkillDef> skills = loader.listAll();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("demo-skill");
        assertThat(skills.get(0).dir()).isEqualTo(skillDir);
    }

    @Test
    void shouldLoadPrompt(@TempDir Path userDir) throws Exception {
        Path skillDir = userDir.resolve("load-test");
        Files.createDirectory(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: load-test\ndescription: test\n---\n# Skill Body\n\nSome content.");

        SkillLoader loader = new SkillLoader(userDir, null);
        String prompt = loader.loadPrompt("load-test");

        assertThat(prompt).isEqualTo("# Skill Body\n\nSome content.");
    }

    @Test
    void shouldReturnNullForUnknownSkill(@TempDir Path userDir) {
        SkillLoader loader = new SkillLoader(userDir, null);
        assertThat(loader.loadPrompt("no-such-skill")).isNull();
    }
}
