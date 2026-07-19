package com.clawkit.tools.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** P1-G2：截断保真采集器的 head/tail/错误片段/hash/脱敏行为。 */
class BoundedOutputCollectorTest {

    private static void feed(BoundedOutputCollector c, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // 模拟流式分块
        int chunk = 7;
        for (int i = 0; i < bytes.length; i += chunk) {
            c.accept(bytes, i, Math.min(chunk, bytes.length - i));
        }
    }

    @Test
    void completeOutputHasNoTruncation() {
        var c = new BoundedOutputCollector(1000, 200);
        feed(c, "hello world\n");
        var env = c.toEnvelope("max-bytes");
        assertFalse(env.truncated());
        assertEquals("hello world\n", env.head());
        assertEquals("", env.tail());
        assertNull(env.truncationReason());
        assertEquals(12, env.totalBytes());
        assertEquals(env.totalBytes(), env.returnedBytes() + env.omittedBytes());
        assertEquals(64, env.sha256().length());
    }

    @Test
    void headAndTailTogetherCanRepresentCompleteOutput() {
        var c = new BoundedOutputCollector(5, 5);
        feed(c, "abcdefghij");
        var env = c.toEnvelope("max-bytes");

        assertFalse(env.truncated());
        assertEquals("abcdefghij", env.head() + env.tail());
        assertEquals(10, env.returnedBytes());
        assertEquals(0, env.omittedBytes());
    }

    @Test
    void truncationKeepsHeadTailAndMidStreamErrors() {
        var c = new BoundedOutputCollector(100, 100);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("line-").append(i).append(" padding padding\n");
        String middle = "ERROR: disk quota exceeded at segment 42\n";
        sb.insert(sb.length() / 2, middle);
        String endMarker = "final-line-marker\n";
        sb.append(endMarker);
        feed(c, sb.toString());

        var env = c.toEnvelope("max-bytes");
        assertTrue(env.truncated());
        assertEquals("max-bytes", env.truncationReason());
        // 尾部保留：错误在末尾不会丢
        assertTrue(env.tail().contains("final-line-marker"),
            "tail must keep end of stream, got: " + env.tail());
        // 中段错误：即使 head/tail 都覆盖不到也通过错误片段保留
        assertTrue(env.errorExcerpts().stream().anyMatch(e -> e.contains("disk quota exceeded")),
            "mid-stream error must be captured: " + env.errorExcerpts());
        // 完整性统计不受截断影响
        assertEquals(sb.toString().getBytes(StandardCharsets.UTF_8).length, env.totalBytes());
        assertEquals(env.totalBytes(), env.returnedBytes() + env.omittedBytes());
    }

    @Test
    void utf8BoundaryNeverProducesMojibake() {
        // head 上限刚好落在多字节字符中间
        var c = new BoundedOutputCollector(10, 10);
        feed(c, "abcdefgh中文字符尾巴X");
        var env = c.toEnvelope("max-bytes");
        // head 不得以损坏的半个字符结束
        assertFalse(env.head().contains("�"), "head has replacement char: " + env.head());
        // tail 跳过前导续字节后不得出现替换符
        assertFalse(env.tail().contains("�"), "tail has replacement char: " + env.tail());
        assertTrue(env.tail().endsWith("X"));
    }

    @Test
    void secretsAreRedactedInEnvelope() {
        var c = new BoundedOutputCollector(1000, 200);
        feed(c, "Authorization: Bearer sk-live-abcdef1234567890\nERROR: auth failed with key sk-abcdef1234567890\n");
        var env = c.toEnvelope("max-bytes");
        assertTrue(env.redactionApplied());
        assertFalse(env.head().contains("abcdef1234567890"), env.head());
        for (String excerpt : env.errorExcerpts()) {
            assertFalse(excerpt.contains("abcdef1234567890"), excerpt);
        }
    }

    @Test
    void errorExcerptCountIsBounded() {
        var c = new BoundedOutputCollector(50, 50);
        for (int i = 0; i < 30; i++) {
            feed(c, "ERROR number " + i + "\n");
        }
        var env = c.toEnvelope("max-bytes");
        assertTrue(env.errorExcerpts().size() <= 8,
            "excerpts must be bounded: " + env.errorExcerpts().size());
    }
}
