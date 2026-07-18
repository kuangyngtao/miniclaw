package com.clawkit.cli;

import static com.clawkit.cli.ClawkitApp.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.clawkit.memory.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClawkitAppTest {

    // ─── resolveCommand ──────────────────────────────────────────────

    @Test
    void shouldResolveHelpAliases() {
        assertThat(resolveCommand("/h")).isEqualTo("help");
        assertThat(resolveCommand("/help")).isEqualTo("help");
    }

    @Test
    void shouldResolveExitAliases() {
        assertThat(resolveCommand("/q")).isEqualTo("exit");
        assertThat(resolveCommand("/exit")).isEqualTo("exit");
    }

    @Test
    void shouldResolveThinkingMode() {
        assertThat(resolveCommand("/t")).isEqualTo("thinking");
        assertThat(resolveCommand("/thinking")).isEqualTo("thinking");
    }

    @Test
    void shouldResolvePermissionModes() {
        assertThat(resolveCommand("/p")).isEqualTo("plan");
        assertThat(resolveCommand("/plan")).isEqualTo("plan");
        assertThat(resolveCommand("/a")).isEqualTo("ask");
        assertThat(resolveCommand("/ask")).isEqualTo("ask");
        assertThat(resolveCommand("/auto")).isEqualTo("auto");
    }

    @Test
    void shouldResolveClearCompactContext() {
        assertThat(resolveCommand("/c")).isEqualTo("clear");
        assertThat(resolveCommand("/clear")).isEqualTo("clear");
        assertThat(resolveCommand("/compact")).isEqualTo("compact");
        assertThat(resolveCommand("/context")).isEqualTo("context");
        assertThat(resolveCommand("/config")).isEqualTo("config");
    }

    @Test
    void shouldResolveImCommands() {
        assertThat(resolveCommand("/im-on")).isEqualTo("im-on");
        assertThat(resolveCommand("/im-off")).isEqualTo("im-off");
        assertThat(resolveCommand("/im-status")).isEqualTo("im-status");
    }

    @Test
    void shouldResolveFeishuCommandAliases() {
        assertThat(resolveCommand("/feishu-on")).isEqualTo("feishu-on");
        assertThat(resolveCommand("/feishu-off")).isEqualTo("feishu-off");
    }

    @Test
    void shouldResolveSkill() {
        assertThat(resolveCommand("/skill")).isEqualTo("skill");
    }

    @Test
    void shouldResolveMenu() {
        assertThat(resolveCommand("/")).isEqualTo("menu");
    }

    @Test
    void shouldReturnNullForUnknownSlashCommand() {
        assertThat(resolveCommand("/unknown")).isNull();
        assertThat(resolveCommand("/foobar")).isNull();
    }

    @Test
    void shouldReturnNullForNonSlashInput() {
        assertThat(resolveCommand("hello")).isNull();
        assertThat(resolveCommand("what's up?")).isNull();
    }

    @Test
    void shouldReturnNullForBlankInput() {
        assertThat(resolveCommand("")).isNull();
        assertThat(resolveCommand("   ")).isNull();
        assertThat(resolveCommand(null)).isNull();
    }

    // ─── buildFullContext ────────────────────────────────────────────

    @Test
    void shouldBuildFromBothProjectAndMemory() {
        String result = buildFullContext("project context", "memory index");
        assertThat(result).contains("## Project Context")
            .contains("project context")
            .contains("memory index");
    }

    @Test
    void shouldBuildFromProjectOnly() {
        String result = buildFullContext("project context", "");
        assertThat(result).contains("## Project Context")
            .contains("project context")
            .doesNotContain("memory");
    }

    @Test
    void shouldBuildFromMemoryOnly() {
        String result = buildFullContext("", "memory index");
        assertThat(result).contains("memory index")
            .doesNotContain("## Project Context");
    }

    @Test
    void shouldReturnEmptyForNulls() {
        assertThat(buildFullContext(null, null)).isEmpty();
        assertThat(buildFullContext("", "")).isEmpty();
    }

    // ─── parseMemoryType ─────────────────────────────────────────────

    @Test
    void shouldParseValidTypes() {
        assertThat(parseMemoryType("USER")).isEqualTo(MemoryType.USER);
        assertThat(parseMemoryType("feedback")).isEqualTo(MemoryType.FEEDBACK);
        assertThat(parseMemoryType("ProJeCt")).isEqualTo(MemoryType.PROJECT);
        assertThat(parseMemoryType("REFERENCE")).isEqualTo(MemoryType.REFERENCE);
    }

    @Test
    void shouldParseShorthand() {
        assertThat(parseMemoryType("u")).isEqualTo(MemoryType.USER);
        assertThat(parseMemoryType("f")).isEqualTo(MemoryType.FEEDBACK);
        assertThat(parseMemoryType("p")).isEqualTo(MemoryType.PROJECT);
        assertThat(parseMemoryType("r")).isEqualTo(MemoryType.REFERENCE);
    }

    @Test
    void shouldReturnNullForInvalidType() {
        assertThat(parseMemoryType("INVALID")).isNull();
        assertThat(parseMemoryType("")).isNull();
        assertThat(parseMemoryType(null)).isNull();
    }

    // ─── formatTokens ────────────────────────────────────────────────

    @Test
    void shouldFormatSmallTokenCount() {
        assertThat(formatTokens(0)).isEqualTo("0t");
        assertThat(formatTokens(500)).isEqualTo("500t");
        assertThat(formatTokens(999)).isEqualTo("999t");
    }

    @Test
    void shouldFormatKiloTokens() {
        assertThat(formatTokens(1000)).isEqualTo("1.0kt");
        assertThat(formatTokens(1500)).isEqualTo("1.5kt");
        assertThat(formatTokens(8500)).isEqualTo("8.5kt");
    }

    // ─── formatSize ──────────────────────────────────────────────────

    @Test
    void shouldFormatBytes() {
        assertThat(formatSize(0)).isEqualTo("0 B");
        assertThat(formatSize(512)).isEqualTo("512 B");
        assertThat(formatSize(1023)).isEqualTo("1023 B");
    }

    @Test
    void shouldFormatKilobytes() {
        assertThat(formatSize(1024)).isEqualTo("1.0 KB");
        assertThat(formatSize(1536)).isEqualTo("1.5 KB");
    }

    @Test
    void shouldFormatMegabytes() {
        assertThat(formatSize(1048576)).isEqualTo("1.0 MB");
        assertThat(formatSize(2621440)).isEqualTo("2.5 MB");
    }

    // ─── padRight ────────────────────────────────────────────────────

    @Test
    void shouldPadShortString() {
        assertThat(padRight("hi", 5)).isEqualTo("hi   ");
    }

    @Test
    void shouldNotPadExactLength() {
        assertThat(padRight("hello", 5)).isEqualTo("hello");
    }

    @Test
    void shouldNotPadLongerString() {
        assertThat(padRight("hello world", 5)).isEqualTo("hello world");
    }

    // ─── center ──────────────────────────────────────────────────────

    @Test
    void shouldLeftPadShortText() {
        String result = center("hi", 6);
        assertThat(result).isEqualTo("  hi");
    }

    @Test
    void shouldReturnExactIfTooLong() {
        assertThat(center("hello world", 5)).isEqualTo("hello world");
    }

    // ─── truncatePath ────────────────────────────────────────────────

    @Test
    void shouldNotTruncateShortPath() {
        assertThat(truncatePath("/a/b", 10)).isEqualTo("/a/b");
    }

    @Test
    void shouldTruncateLongPath() {
        String result = truncatePath("/very/long/path/that/exceeds/limit", 20);
        assertThat(result).startsWith("...");
        assertThat(result).hasSize(20);
    }

    // ─── readProjectContext ──────────────────────────────────────────

    @Test
    void shouldReadClaudeMd(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "project rules here");
        assertThat(readProjectContext(tempDir)).isEqualTo("project rules here");
    }

    @Test
    void shouldFallbackToAgentsMd(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "agent rules here");
        assertThat(readProjectContext(tempDir)).isEqualTo("agent rules here");
    }

    @Test
    void shouldPreferClaudeMdOverAgentsMd(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "claude");
        Files.writeString(tempDir.resolve("AGENTS.md"), "agents");
        assertThat(readProjectContext(tempDir)).isEqualTo("claude");
    }

    @Test
    void shouldReturnEmptyWhenNoMdFile(@TempDir Path tempDir) {
        assertThat(readProjectContext(tempDir)).isEmpty();
    }
}
