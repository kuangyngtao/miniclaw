package com.clawkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

class ClawkitCompleterTest {

    private final ClawkitCompleter completer = new ClawkitCompleter();

    @Test
    void shouldCompleteExactMatch() {
        List<Candidate> candidates = complete("/help");
        assertThat(candidates).extracting(Candidate::value).contains("/help");
    }

    @Test
    void shouldCompletePartialPrefix() {
        List<Candidate> candidates = complete("/sess");
        assertThat(candidates).extracting(Candidate::value)
            .contains("/session", "/session save", "/session load", "/session list",
                "/session delete", "/session search", "/session stats",
                "/session prune", "/session new");
    }

    @Test
    void shouldCompleteSingleChar() {
        List<Candidate> candidates = complete("/a");
        assertThat(candidates).extracting(Candidate::value)
            .contains("/ask", "/auto");
    }

    @Test
    void shouldReturnEmptyForNonMatchingSlash() {
        List<Candidate> candidates = complete("/nope");
        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNonSlashInput() {
        List<Candidate> candidates = complete("hello");
        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldReturnEmptyForEmptyInput() {
        List<Candidate> candidates = complete("");
        assertThat(candidates).isEmpty();
    }

    @Test
    void shouldBeCaseInsensitive() {
        List<Candidate> candidates = complete("/HELP");
        assertThat(candidates).extracting(Candidate::value).contains("/help");
    }

    @Test
    void shouldCompleteAllMemoryCommands() {
        List<Candidate> candidates = complete("/mem");
        assertThat(candidates).extracting(Candidate::value).contains("/memory");
    }

    @Test
    void shouldCompleteAllSkillCommands() {
        List<Candidate> candidates = complete("/skill");
        assertThat(candidates).extracting(Candidate::value)
            .contains("/skill", "/skill list", "/skill load", "/skill unload");
    }

    @Test
    void shouldCompleteFeishuCommands() {
        List<Candidate> candidates = complete("/feishu");
        assertThat(candidates).extracting(Candidate::value)
            .contains("/feishu-on", "/feishu-off");
    }

    @Test
    void shouldCompleteAllCommandsWhenSlashOnly() {
        List<Candidate> candidates = complete("/");
        assertThat(candidates).extracting(Candidate::value)
            .contains("/help", "/clear", "/compact", "/context",
                "/thinking", "/plan", "/ask", "/auto",
                "/exit", "/session", "/remember", "/memory",
                "/skill", "/feishu-on", "/feishu-off");
    }

    private List<Candidate> complete(String word) {
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, new StubParsedLine(word), candidates);
        return candidates;
    }

    private record StubParsedLine(String word) implements ParsedLine {
        @Override public String word() { return word; }
        @Override public int wordCursor() { return 0; }
        @Override public int wordIndex() { return 0; }
        @Override public List<String> words() { return List.of(word); }
        @Override public String line() { return word; }
        @Override public int cursor() { return 0; }
    }
}
