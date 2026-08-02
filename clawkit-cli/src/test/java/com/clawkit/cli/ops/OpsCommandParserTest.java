package com.clawkit.cli.ops;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpsCommandParserTest {

    @Test
    void shouldParseInvestigateWithTargetServiceAndQuestion() {
        var cmd = OpsCommandParser.parse("investigate test-server order-api 为什么不可用");
        assertThat(cmd.subCommand()).isEqualTo(OpsCommandParser.SubCommand.INVESTIGATE);
        assertThat(cmd.targetId()).isEqualTo("test-server");
        assertThat(cmd.serviceId()).isEqualTo("order-api");
        assertThat(cmd.question()).isEqualTo("为什么不可用");
    }

    @Test
    void shouldParseInvestigateWithTargetOnly() {
        var cmd = OpsCommandParser.parse("investigate test-server");
        assertThat(cmd.subCommand()).isEqualTo(OpsCommandParser.SubCommand.INVESTIGATE);
        assertThat(cmd.targetId()).isEqualTo("test-server");
        assertThat(cmd.serviceId()).isEqualTo("order-api");
    }

    @Test
    void shouldRejectNonAllowedServiceInHandler() {
        // The parser accepts it but isAllowedService rejects non-order-api
        assertThat(OpsCommandParser.isAllowedService("order-api")).isTrue();
        assertThat(OpsCommandParser.isAllowedService("postgres")).isFalse();
        assertThat(OpsCommandParser.isAllowedService("gateway")).isFalse();
    }

    @Test
    void shouldParseRecent() {
        var cmd = OpsCommandParser.parse("recent");
        assertThat(cmd.subCommand()).isEqualTo(OpsCommandParser.SubCommand.RECENT);
        assertThat(cmd.recentLimit()).isEqualTo(10);
    }

    @Test
    void shouldParseRecentWithLimit() {
        var cmd = OpsCommandParser.parse("recent 5");
        assertThat(cmd.recentLimit()).isEqualTo(5);
    }

    @Test
    void shouldParseInspect() {
        var cmd = OpsCommandParser.parse("inspect inc-test-abc12345");
        assertThat(cmd.subCommand()).isEqualTo(OpsCommandParser.SubCommand.INSPECT);
        assertThat(cmd.incidentId()).isEqualTo("inc-test-abc12345");
    }

    @Test
    void shouldParseContinue() {
        var cmd = OpsCommandParser.parse("continue inc-test-abc12345");
        assertThat(cmd.subCommand()).isEqualTo(OpsCommandParser.SubCommand.CONTINUE);
        assertThat(cmd.incidentId()).isEqualTo("inc-test-abc12345");
    }

    @Test
    void shouldParseHelp() {
        assertThat(OpsCommandParser.parse("help").subCommand())
            .isEqualTo(OpsCommandParser.SubCommand.HELP);
        assertThat(OpsCommandParser.parse("").subCommand())
            .isEqualTo(OpsCommandParser.SubCommand.HELP);
    }

    // ── NL routing ──────────────────────────────────────────────────

    @Test
    void shouldResolveChineseInvestigationIntent() {
        Set<String> targets = Set.of("test-server", "staging");
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "调查 test-server 上的 order-api 为什么不可用", targets))
            .isEqualTo("test-server");
    }

    @Test
    void shouldResolveDiagnosisIntent() {
        Set<String> targets = Set.of("test-server");
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "诊断 test-server 上的 order-api", targets))
            .isEqualTo("test-server");
    }

    @Test
    void shouldRejectUnregisteredTargetInNL() {
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "调查 test-server 上的 order-api", Set.of("staging")))
            .isNull();
    }

    @Test
    void shouldNotConfuseNormalChat() {
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "今天天气怎么样", Set.of("test-server")))
            .isNull();
    }

    @Test
    void shouldNotParseArbitraryIp() {
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "调查 192.168.1.1 上的 order-api", Set.of("test-server")))
            .isNull();
    }

    @Test
    void shouldNotParseArbitraryHostname() {
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "调查 unknown-host 上的 order-api", Set.of("test-server")))
            .isNull();
    }

    @Test
    void shouldHandleEnglishInvestigateIntent() {
        assertThat(OpsCommandParser.resolveNaturalLanguage(
            "investigate test-server order-api", Set.of("test-server")))
            .isEqualTo("test-server");
    }
}
