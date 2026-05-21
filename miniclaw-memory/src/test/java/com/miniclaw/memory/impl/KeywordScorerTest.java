package com.miniclaw.memory.impl;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordScorerTest {

    private static final List<String> CORPUS = List.of(
        "将 user 表从 MySQL 迁移到 PostgreSQL",
        "修复了登录页面的空指针异常",
        "重构数据库连接池，修复迁移脚本的时区问题",
        "用户偏好简短回复，不要冗长的总结",
        "Nothing to do with databases here"
    );

    @Test
    void shouldScoreExactMatchHigherThanPartial() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        double exactScore = scorer.score("数据库迁移",
            "重构数据库连接池，修复迁移脚本的时区问题");
        double partialScore = scorer.score("数据库迁移",
            "将 user 表从 MySQL 迁移到 PostgreSQL");

        assertThat(exactScore).isGreaterThan(0);
        // exactMatch has "数据库"+"迁移", partial has "迁移" only → exact higher
        assertThat(exactScore).isGreaterThan(partialScore);
    }

    @Test
    void shouldScorePartialMatchAboveZero() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        double score = scorer.score("数据库迁移",
            "将 user 表从 MySQL 迁移到 PostgreSQL");

        // "迁移" matches, so score > 0
        assertThat(score).isGreaterThan(0);
    }

    @Test
    void shouldReturnZeroForNoMatch() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        double score = scorer.score("前端框架",
            "重构数据库连接池，修复迁移脚本的时区问题");

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void shouldReturnZeroForEmptyQuery() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        assertThat(scorer.score("", CORPUS.get(0))).isEqualTo(0.0);
        assertThat(scorer.score("   ", CORPUS.get(0))).isEqualTo(0.0);
        assertThat(scorer.score(null, CORPUS.get(0))).isEqualTo(0.0);
    }

    @Test
    void shouldReturnZeroForEmptyDocument() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        assertThat(scorer.score("test", "")).isEqualTo(0.0);
        assertThat(scorer.score("test", (String) null)).isEqualTo(0.0);
    }

    @Test
    void shouldScoreCJKTokens() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        double score = scorer.score("用户偏好", "用户偏好简短回复，不要冗长的总结");

        assertThat(score).isGreaterThan(0.5);
    }

    @Test
    void shouldScoreEnglishTokens() {
        KeywordScorer scorer = new KeywordScorer(CORPUS);

        double score = scorer.score("MySQL PostgreSQL", CORPUS.get(0));
        // Both MySQL and PostgreSQL match in first doc

        assertThat(score).isGreaterThan(0);
    }

    @Test
    void shouldHandleEmptyCorpus() {
        KeywordScorer scorer = new KeywordScorer(List.of());

        double score = scorer.score("test", "some document");
        // avgDocLength defaults to 1.0, idf returns 0 since n=0
        assertThat(score).isEqualTo(0.0);
    }
}
