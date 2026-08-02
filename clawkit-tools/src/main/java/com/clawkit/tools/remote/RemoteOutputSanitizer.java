package com.clawkit.tools.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Client-side output sanitization for remote tool results.
 *
 * <p>Second layer of defense (after server-side LogSanitizer):
 * <ol>
 *   <li>Recursive JSON key sanitization for sensitive field names</li>
 *   <li>Pattern-based text sanitization</li>
 *   <li>UTF-8 byte count enforcement</li>
 *   <li>PEM / oversized / non-JSON detection → reject</li>
 * </ol>
 *
 * <p>Design: REMOTE-0 §12.2.
 */
public final class RemoteOutputSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 32768;

    // Sensitive JSON keys to sanitize recursively
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "passwd", "secret", "token", "apiKey", "api_key",
        "apikey", "authorization", "auth", "cookie", "session",
        "privateKey", "private_key", "accessKey", "access_key",
        "secretKey", "secret_key", "credential", "credentials"
    );

    // Patterns for text-field sanitization
    private static final Pattern[] TEXT_PATTERNS = {
        Pattern.compile("(?i)(?:bearer|basic)\\s+[\\w\\-+.=/]{8,}"),
        Pattern.compile("(?i)eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*"),
        Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
        Pattern.compile("(?i)jdbc:[a-z]+://[^\\s]*:[^@\\s]+@"),
    };

    private RemoteOutputSanitizer() {}

    /**
     * Sanitize a remote tool output string.
     *
     * @param output the raw output from the remote tool
     * @return sanitized output
     * @throws OutputRejectedException if output is unsafe or unprocessable
     */
    public static String sanitize(String output) throws OutputRejectedException {
        if (output == null || output.isEmpty()) return output;

        // Check UTF-8 byte count
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            // Truncate to max bytes on a UTF-8 boundary
            int cutPoint = MAX_OUTPUT_BYTES;
            while (cutPoint > 0 && (bytes[cutPoint] & 0xC0) == 0x80) {
                cutPoint--; // don't cut in the middle of a multi-byte char
            }
            output = new String(bytes, 0, cutPoint, StandardCharsets.UTF_8) + "\n[TRUNCATED]";
        }

        // Reject PEM private key headers
        if (output.contains("-----BEGIN") && output.contains("PRIVATE KEY-----")) {
            throw new OutputRejectedException("RMT-014",
                "output contains private key material");
        }

        // Try JSON sanitization first
        String sanitized = sanitizeJson(output);
        if (sanitized != null) return sanitized;

        // Plain text sanitization
        return sanitizeText(output);
    }

    private static String sanitizeJson(String output) {
        try {
            JsonNode root = MAPPER.readTree(output);
            JsonNode cleaned = sanitizeNode(root);
            return MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            return null; // Not valid JSON — fall through to text sanitization
        }
    }

    private static JsonNode sanitizeNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = MAPPER.createObjectNode();
            var iter = node.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (SENSITIVE_KEYS.contains(key.toLowerCase())) {
                    obj.put(key, "[REDACTED]");
                } else {
                    obj.set(key, sanitizeNode(value));
                }
            }
            return obj;
        } else if (node.isArray()) {
            var arr = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                arr.add(sanitizeNode(item));
            }
            return arr;
        } else if (node.isTextual()) {
            String text = sanitizeText(node.asText());
            return MAPPER.getNodeFactory().textNode(text);
        }
        return node;
    }

    private static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (Pattern p : TEXT_PATTERNS) {
            result = p.matcher(result).replaceAll("[REDACTED]");
        }
        return result;
    }

    /** Thrown when output is rejected as unsafe. */
    public static class OutputRejectedException extends RuntimeException {
        private final String code;
        public OutputRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }
}
