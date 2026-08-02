package com.clawkit.ops.mcp;

import java.util.regex.Pattern;

/**
 * First-layer server-side log sanitization.
 *
 * <p>Applied before log content leaves the remote server.
 * Redacts common secret patterns from log text. Never logs
 * the original values that were replaced.
 *
 * <p>This is a BEST-EFFORT layer — it cannot guarantee that
 * arbitrary production logs are safe. The client-side second
 * layer and the operational rule (REMOTE-0 only runs against
 * Fixture servers) provide defense-in-depth.
 *
 * <p>Design: REMOTE-0 §12.1.
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    // Patterns are applied in order; earlier matches take precedence
    private static final Pattern[] PATTERNS = {
        // Bearer token / Authorization header
        Pattern.compile("(?i)(Authorization|Auth)[：:\\s]*[Bb]earer\\s+[\\w\\-\\.+/=]+"),
        Pattern.compile("(?i)(Authorization|Auth)[：:\\s]*[Bb]asic\\s+[\\w\\-\\.+/=]+"),
        Pattern.compile("(?i)(Authorization|Auth)[：:\\s]*[Dd]igest\\s+[\\w\\-\\.+/=,]+"),
        // API keys
        Pattern.compile("(?i)(api[_-]?key|apikey|api_secret|secret_key|access_key)[：:\\s=]+[\\w\\-\\.+/=]{8,}"),
        Pattern.compile("(?i)(token|secret|password|passwd)[：:\\s=]+[\\S]{4,}"),
        // JWT
        Pattern.compile("(?i)eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*"),
        // JDBC / database connection strings
        Pattern.compile("(?i)jdbc:[a-z]+://[^/\\s]*:[^@\\s]+@[\\S]+"),
        Pattern.compile("(?i)jdbc:[a-z]+://[^/\\s]+\\?[\\S]*password=[^&\\s]+"),
        // URL userinfo
        Pattern.compile("(?i)[a-z][a-z0-9+\\-.]*://[^/\\s:@]+:[^/\\s:@]+@"),
        // PEM private key blocks
        Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
        // Cookie / session values
        Pattern.compile("(?i)(cookie|session|sessid|jsessionid|phpsessid)[：:\\s=]+[\\S]{8,}"),
    };

    private static final String REDACTION_MARKER = "[REDACTED]";

    /**
     * Sanitize a single line of log text.
     *
     * @return sanitized text
     */
    public static String sanitize(String line) {
        if (line == null || line.isEmpty()) return line;
        String result = line;
        for (Pattern p : PATTERNS) {
            result = p.matcher(result).replaceAll(REDACTION_MARKER);
        }
        return result;
    }

    /**
     * Sanitize multi-line text and count redactions.
     */
    public static SanitizeResult sanitizeAll(String text) {
        if (text == null || text.isEmpty()) {
            return new SanitizeResult(text, 0);
        }
        String result = text;
        int count = 0;
        for (Pattern p : PATTERNS) {
            var matcher = p.matcher(result);
            while (matcher.find()) count++;
            result = matcher.replaceAll(REDACTION_MARKER);
        }
        // Truncate excessively long single lines
        String[] lines = result.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() > 8192) {
                line = line.substring(0, 8192) + "...[TRUNCATED]";
            }
            sb.append(line);
            if (i < lines.length - 1) sb.append("\n");
        }
        return new SanitizeResult(sb.toString(), count);
    }

    public record SanitizeResult(String text, int redactedMatches) {
        public boolean redactionApplied() { return redactedMatches > 0; }
    }
}
