package com.clawkit.cli.remote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** P1-2: Parser schema validation tests. */
class RemoteCommandParserTest {

    // ── Tokenization ──────────────────────────────────────────────────

    @Test void shouldTokenizeQuotedArgs() {
        var t = RemoteCommandParser.tokenize("add --from-ssh \"my server\" --as target");
        assertThat(t).containsExactly("add", "--from-ssh", "my server", "--as", "target");
    }
    @Test void shouldDetectUnclosedSingleQuote() {
        var t = RemoteCommandParser.tokenize("add --from-ssh 'unclosed");
        assertThat(t).contains("UNCLOSED_SINGLE_QUOTE");
    }
    @Test void shouldDetectUnclosedDoubleQuote() {
        var t = RemoteCommandParser.tokenize("add --from-ssh \"unclosed");
        assertThat(t).contains("UNCLOSED_DOUBLE_QUOTE");
    }

    // ── Unknown option rejection ──────────────────────────────────────

    @Test void shouldRejectUnknownOption() {
        var c = RemoteCommandParser.parse("add --from-ssh test --unknown-opt");
        assertThat(c.hasErrors()).isTrue();
        assertThat(c.errors()).anyMatch(e -> e.contains("unknown option"));
    }
    @Test void doctorShouldRejectUnknownOption() {
        var c = RemoteCommandParser.parse("doctor test --foobar");
        assertThat(c.hasErrors()).isTrue();
    }

    // ── Duplicate option rejection ────────────────────────────────────

    @Test void shouldRejectDuplicateOption() {
        var c = RemoteCommandParser.parse("add --from-ssh a --from-ssh b");
        assertThat(c.hasErrors()).isTrue();
        assertThat(c.errors()).anyMatch(e -> e.contains("duplicate"));
    }

    // ── Missing targetId ──────────────────────────────────────────────

    @Test void connectRequiresTargetId() {
        var c = RemoteCommandParser.parse("connect");
        assertThat(c.hasErrors()).isTrue();
        assertThat(c.errors()).anyMatch(e -> e.contains("targetId"));
    }
    @Test void doctorRequiresTargetId() {
        var c = RemoteCommandParser.parse("doctor");
        assertThat(c.hasErrors()).isTrue();
    }

    // ── Illegal profile ───────────────────────────────────────────────

    @Test void shouldRejectFixProfileViaParser() {
        var c = RemoteCommandParser.parse("add --from-ssh srv --profile fix-order-api-v1");
        assertThat(c.hasErrors()).isTrue();
        assertThat(c.errors()).anyMatch(e -> e.contains("unknown profile"));
    }
    @Test void shouldAcceptValidProfile() {
        var c = RemoteCommandParser.parse(
            "add --from-ssh srv --profile postgres-diagnosis-readonly-v1");
        assertThat(c.option("profile")).isEqualTo("postgres-diagnosis-readonly-v1");
    }

    // ── --verbose/--json mutual exclusion ─────────────────────────────

    @Test void doctorRejectsVerboseAndJsonTogether() {
        var c = RemoteCommandParser.parse("doctor test --verbose --json");
        assertThat(c.hasErrors()).isTrue();
        assertThat(c.errors()).anyMatch(e -> e.contains("mutually exclusive"));
    }
    @Test void doctorAcceptsVerboseAlone() {
        var c = RemoteCommandParser.parse("doctor test --verbose");
        assertThat(c.hasErrors()).isFalse();
        assertThat(c.hasOption("verbose")).isTrue();
    }
    @Test void doctorAcceptsJsonAlone() {
        var c = RemoteCommandParser.parse("doctor test --json");
        assertThat(c.hasErrors()).isFalse();
        assertThat(c.hasOption("json")).isTrue();
    }

    // ── --yes requires --from-ssh ─────────────────────────────────────

    @Test void yesWithoutFromSshIsError() {
        var c = RemoteCommandParser.parse("add target --yes");
        assertThat(c.hasErrors()).isTrue();
        assertThat(c.errors()).anyMatch(e -> e.contains("--yes requires --from-ssh"));
    }
    @Test void yesWithFromSshIsValid() {
        var c = RemoteCommandParser.parse("add --from-ssh srv --yes");
        assertThat(c.hasErrors()).isFalse();
    }

    // ── Empty/status default ──────────────────────────────────────────

    @Test void emptyArgsDefaultsToStatus() {
        var c = RemoteCommandParser.parse("");
        assertThat(c.subCommand()).isEqualTo(RemoteCommandParser.SubCommand.STATUS);
    }
    @Test void whiteSpaceDefaultsToStatus() {
        var c = RemoteCommandParser.parse("   ");
        assertThat(c.subCommand()).isEqualTo(RemoteCommandParser.SubCommand.STATUS);
    }
}
