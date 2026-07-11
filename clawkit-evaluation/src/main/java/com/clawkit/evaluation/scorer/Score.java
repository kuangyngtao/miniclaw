package com.clawkit.evaluation.scorer;

/**
 * 单个 Scorer 的评分结果。
 */
public record Score(
    String name,
    ScoreStatus status,
    double value,
    String expected,
    String actual,
    String evidence
) {
    public static Score pass(String name) {
        return new Score(name, ScoreStatus.PASS, 0, "", "", "");
    }

    public static Score pass(String name, double value, String evidence) {
        return new Score(name, ScoreStatus.PASS, value, "", "", evidence);
    }

    public static Score fail(String name, double value, String expected, String actual, String evidence) {
        return new Score(name, ScoreStatus.FAIL, value, expected, actual, evidence);
    }

    public static Score fail(String name, String expected, String actual, String evidence) {
        return new Score(name, ScoreStatus.FAIL, 0, expected, actual, evidence);
    }

    public static Score warn(String name, String message) {
        return new Score(name, ScoreStatus.WARN, 0, "", "", message);
    }

    public static Score notApplicable(String name) {
        return new Score(name, ScoreStatus.NOT_APPLICABLE, 0, "", "", "");
    }
}
