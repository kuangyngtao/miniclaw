package com.clawkit.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans {name}/SKILL.md directories and loads skill prompts.
 *
 * Project-level (.clawkit/skills/) overrides user-level (~/.clawkit/skills/)
 * for skills with the same name.
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_MD = "SKILL.md";

    private final Path userSkillsDir;
    private final Path projectSkillsDir;

    public SkillLoader(Path userSkillsDir, Path projectSkillsDir) {
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
    }

    public record SkillDef(String name, String description, Path dir) {}

    // ─── Catalog ──────────────────────────────────────────────────────

    /** Scan both directories and build a name→description catalog. */
    public SkillCatalog buildCatalog() {
        Map<String, String> entries = new LinkedHashMap<>();

        // Load user-level first, then project-level to override
        scanDir(userSkillsDir, entries);
        scanDir(projectSkillsDir, entries);

        return new SkillCatalog(Map.copyOf(entries));
    }

    private void scanDir(Path dir, Map<String, String> entries) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillMd = skillDir.resolve(SKILL_MD);
                if (!Files.isRegularFile(skillMd)) return;
                try {
                    String content = Files.readString(skillMd);
                    Map<String, String> fm = parseFrontmatter(content);
                    String name = fm.getOrDefault("name", skillDir.getFileName().toString());
                    String desc = fm.getOrDefault("description", "");
                    entries.put(name, desc); // project-level overwrites if same key
                } catch (IOException e) {
                    log.warn("Failed to read SKILL.md in {}: {}", skillDir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list skill dir {}: {}", dir, e.getMessage());
        }
    }

    // ─── Load / List ──────────────────────────────────────────────────

    /** Load full prompt body (SKILL.md content without frontmatter) for a skill. */
    public String loadPrompt(String name) {
        Path dir = resolveDir(name);
        if (dir == null) return null;
        Path skillMd = dir.resolve(SKILL_MD);
        try {
            String raw = Files.readString(skillMd);
            return stripFrontmatter(raw).strip();
        } catch (IOException e) {
            log.error("Failed to load skill prompt for {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** List all available skills (project overrides user). */
    public List<SkillDef> listAll() {
        Map<String, SkillDef> map = new LinkedHashMap<>();
        collectSkills(userSkillsDir, map);
        collectSkills(projectSkillsDir, map); // override
        return new ArrayList<>(map.values());
    }

    private void collectSkills(Path dir, Map<String, SkillDef> map) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillMd = skillDir.resolve(SKILL_MD);
                if (!Files.isRegularFile(skillMd)) return;
                try {
                    String content = Files.readString(skillMd);
                    Map<String, String> fm = parseFrontmatter(content);
                    String name = fm.getOrDefault("name", skillDir.getFileName().toString());
                    String desc = fm.getOrDefault("description", "");
                    map.put(name, new SkillDef(name, desc, skillDir));
                } catch (IOException e) {
                    log.warn("Failed to read SKILL.md in {}: {}", skillDir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list skill dir {}: {}", dir, e.getMessage());
        }
    }

    /** Get the directory for a skill (for LLM to read references/). */
    public Path skillDir(String name) {
        return resolveDir(name);
    }

    // ─── Resolution ───────────────────────────────────────────────────

    private Path resolveDir(String name) {
        // Project-level first
        Path project = projectSkillsDir != null ? projectSkillsDir.resolve(name) : null;
        if (project != null && Files.isDirectory(project)
                && Files.isRegularFile(project.resolve(SKILL_MD))) {
            return project;
        }
        Path user = userSkillsDir != null ? userSkillsDir.resolve(name) : null;
        if (user != null && Files.isDirectory(user)
                && Files.isRegularFile(user.resolve(SKILL_MD))) {
            return user;
        }
        return null;
    }

    // ─── YAML Frontmatter ─────────────────────────────────────────────

    /** Parse simple YAML frontmatter: key: value lines between --- delimiters. */
    static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> fm = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) return fm;

        int end = content.indexOf("---", 3);
        if (end < 0) return fm;

        String block = content.substring(3, end);
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                // Strip surrounding quotes
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                fm.put(key, value);
            }
        }
        return fm;
    }

    /** Return content with the YAML frontmatter block removed. */
    static String stripFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) return content != null ? content : "";
        int end = content.indexOf("---", 3);
        if (end < 0) return content;
        return content.substring(end + 3);
    }
}
