package com.clawkit.reliability.gate;

import com.clawkit.tools.action.Digests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 确定性断言验证器（P1-G）。
 *
 * <p>断言总是重新采集新鲜证据（重新读取目标），不复用执行时的工具输出。
 * 支持的断言：
 * <ul>
 *   <li>{@code file-sha256:<path>:<hex>} — 文件当前内容 hash 等于期望值；</li>
 *   <li>{@code file-absent:<path>} — 文件不存在。</li>
 * </ul>
 * 未知断言前缀和空断言列表均按失败处理（fail closed）。
 */
public final class DeterministicVerifier {

    public record Verdict(boolean passed, String detail) {}

    private DeterministicVerifier() {}

    public static Verdict verify(List<String> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            return new Verdict(false, "no deterministic assertions declared");
        }
        StringBuilder detail = new StringBuilder();
        boolean allPassed = true;
        for (String assertion : assertions) {
            Verdict v = verifyOne(assertion);
            if (!v.passed()) allPassed = false;
            if (!detail.isEmpty()) detail.append("; ");
            detail.append(v.detail());
        }
        return new Verdict(allPassed, detail.toString());
    }

    /** 验证写锁内重新采集的前置条件。空列表表示无前置条件，允许继续。 */
    public static Verdict verifyPreconditions(List<String> preconditions) {
        if (preconditions == null || preconditions.isEmpty()) {
            return new Verdict(true, "no preconditions declared");
        }
        List<String> assertions = preconditions.stream().map(value -> {
            if (value.startsWith("pre-file-sha256:")) {
                return value.substring("pre-".length());
            }
            if (value.startsWith("pre-file-absent:")) {
                return value.substring("pre-".length());
            }
            return value;
        }).toList();
        return verify(assertions);
    }

    private static Verdict verifyOne(String assertion) {
        try {
            if (assertion.startsWith("file-sha256:")) {
                String rest = assertion.substring("file-sha256:".length());
                int lastColon = rest.lastIndexOf(':');
                if (lastColon <= 0) return new Verdict(false, "malformed assertion: " + assertion);
                Path path = Path.of(rest.substring(0, lastColon));
                String expected = rest.substring(lastColon + 1);
                if (!Files.exists(path)) {
                    return new Verdict(false, "file missing: " + path);
                }
                String actual = Digests.sha256Hex(Files.readAllBytes(path));
                return actual.equalsIgnoreCase(expected)
                    ? new Verdict(true, "sha256 ok: " + path)
                    : new Verdict(false, "sha256 mismatch on " + path
                        + " (expected " + shortHex(expected) + ", got " + shortHex(actual) + ")");
            }
            if (assertion.startsWith("file-absent:")) {
                Path path = Path.of(assertion.substring("file-absent:".length()));
                return !Files.exists(path)
                    ? new Verdict(true, "absent ok: " + path)
                    : new Verdict(false, "file still exists: " + path);
            }
            return new Verdict(false, "unsupported assertion (fail closed): " + assertion);
        } catch (Exception e) {
            return new Verdict(false, "assertion error on '" + assertion + "': " + e.getMessage());
        }
    }

    private static String shortHex(String hex) {
        return hex != null && hex.length() > 12 ? hex.substring(0, 12) + "…" : hex;
    }
}
