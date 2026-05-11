package com.miniclaw.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class YamlFrontmatterParserTest {

    @Test
    void shouldParseFullFrontmatter() {
        String content = "---\nname: test\n---\nHello world";
        Map<String, String> fm = YamlFrontmatterParser.parseFrontmatter(content);
        assertThat(fm).containsEntry("name", "test");
    }

    @Test
    void shouldReturnEmptyMapWithoutFrontmatter() {
        Map<String, String> fm = YamlFrontmatterParser.parseFrontmatter("plain text");
        assertThat(fm).isEmpty();
    }

    @Test
    void shouldReturnEmptyMapForNull() {
        Map<String, String> fm = YamlFrontmatterParser.parseFrontmatter(null);
        assertThat(fm).isEmpty();
    }

    @Test
    void shouldStripFrontmatter() {
        String content = "---\nname: test\ndescription: desc\n---\n\nBody content here";
        String body = YamlFrontmatterParser.stripFrontmatter(content);
        assertThat(body).isEqualTo("Body content here");
    }

    @Test
    void shouldReturnOriginalIfNoFrontmatter() {
        String body = YamlFrontmatterParser.stripFrontmatter("just body");
        assertThat(body).isEqualTo("just body");
    }

    @Test
    void shouldBuildMarkdownWithFrontmatter() {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("name", "test");
        String result = YamlFrontmatterParser.build(fm, "body text");
        assertThat(result).startsWith("---\n");
        assertThat(result).contains("name:");
        assertThat(result).contains("test");
        assertThat(result).endsWith("body text");
    }

    @Test
    void shouldRoundtrip() {
        Map<String, String> fm = new LinkedHashMap<>();
        fm.put("name", "my-memory");
        fm.put("type", "user");
        String original = "Memory body content.";
        String built = YamlFrontmatterParser.build(fm, original);
        Map<String, String> parsed = YamlFrontmatterParser.parseFrontmatter(built);
        String body = YamlFrontmatterParser.stripFrontmatter(built);
        assertThat(parsed).containsEntry("name", "my-memory");
        assertThat(parsed).containsEntry("type", "user");
        assertThat(body).isEqualTo(original);
    }

    @Test
    void shouldHandleMalformedYamlGracefully() {
        String content = "---\n: bad yaml : :\n---\nbody";
        Map<String, String> fm = YamlFrontmatterParser.parseFrontmatter(content);
        // Should not throw; returns whatever YAML parser produces or empty
        assertThat(fm).isNotNull();
    }
}
