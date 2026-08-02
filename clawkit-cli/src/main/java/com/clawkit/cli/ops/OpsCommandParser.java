package com.clawkit.cli.ops;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parser for {@code /ops} commands.
 *
 * <p>Narrow-format only. Does NOT use LLM. Target must be a registered targetId.
 * Arbitrary IPs, hostnames, and usernames are rejected.
 *
 * <p>OPS-PRODUCT-LOOP-1 §12.
 */
public final class OpsCommandParser {

    private OpsCommandParser() {}

    public enum SubCommand {
        INVESTIGATE, RECENT, INSPECT, CONTINUE, HELP, UNKNOWN
    }

    public record ParsedCommand(
        SubCommand subCommand,
        String targetId,
        String serviceId,
        String question,
        String incidentId,
        int recentLimit
    ) {}

    // Group 1=targetId, Group 2=serviceId(optional), Group 3=question(optional)
    private static final Pattern INVESTIGATE_PATTERN = Pattern.compile(
        "investigate\\s+(\\S+)(?:\\s+(\\S+))?\\s*(.*)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> ALLOWED_SERVICES = Set.of("order-api");

    /**
     * Parse a /ops command string. Never returns null.
     */
    public static ParsedCommand parse(String args) {
        if (args == null) args = "";
        String trimmed = args.strip();

        if (trimmed.isEmpty() || trimmed.equals("help")) {
            return new ParsedCommand(SubCommand.HELP, null, null, null, null, 0);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.startsWith("recent")) {
            int limit = 10;
            String rest = trimmed.substring(6).strip();
            try {
                if (!rest.isEmpty()) limit = Integer.parseInt(rest);
            } catch (NumberFormatException ignored) { }
            return new ParsedCommand(SubCommand.RECENT, null, null, null, null,
                Math.min(Math.max(limit, 1), 100));
        }

        if (lower.startsWith("inspect ")) {
            String id = trimmed.substring(8).strip();
            return new ParsedCommand(SubCommand.INSPECT, null, null, null,
                id.isEmpty() ? null : id, 0);
        }

        if (lower.startsWith("continue ")) {
            String id = trimmed.substring(9).strip();
            return new ParsedCommand(SubCommand.CONTINUE, null, null, null,
                id.isEmpty() ? null : id, 0);
        }

        if (lower.startsWith("investigate")) {
            Matcher m = INVESTIGATE_PATTERN.matcher(trimmed);
            if (m.matches()) {
                String targetId = m.group(1);
                String svc = m.group(2);
                String q = m.group(3) != null ? m.group(3).strip() : "";

                // If group(2) isn't a valid serviceId, it's actually part of target or question
                String serviceId;
                if (svc != null && ALLOWED_SERVICES.contains(svc)) {
                    serviceId = svc;
                } else if (svc != null) {
                    // Not a service — prepend to question
                    serviceId = "order-api";
                    q = svc + (q.isEmpty() ? "" : " " + q);
                } else {
                    serviceId = "order-api";
                }
                return new ParsedCommand(SubCommand.INVESTIGATE, targetId,
                    serviceId, q.isEmpty() ? null : q, null, 0);
            }
            // "investigate <text>" without matching pattern — try to extract target
            String rest = trimmed.substring(11).strip();
            if (!rest.isEmpty()) {
                return new ParsedCommand(SubCommand.INVESTIGATE, null,
                    "order-api", rest, null, 0);
            }
        }

        return new ParsedCommand(SubCommand.HELP, null, null, null, null, 0);
    }

    /**
     * Validate serviceId — only allowlist is accepted.
     */
    public static boolean isAllowedService(String serviceId) {
        return serviceId != null && ALLOWED_SERVICES.contains(serviceId);
    }

    /**
     * Narrow-format NL routing for ops investigation intents.
     * Only matches registered targets. Never parses arbitrary IPs or hostnames.
     *
     * @return the resolved targetId or null if not a recognized intent
     */
    public static String resolveNaturalLanguage(String input, Set<String> registeredTargets) {
        if (input == null || input.isBlank()) return null;

        String s = input.strip();

        // Pattern: 调查 <target> ...
        // Pattern: 诊断 <target> ...
        for (String prefix : List.of("调查", "诊断")) {
            if (s.startsWith(prefix)) {
                String rest = s.substring(prefix.length()).strip();
                for (String tid : registeredTargets) {
                    if (rest.startsWith(tid)) {
                        String after = rest.substring(tid.length()).strip();
                        if (after.isEmpty() || after.startsWith("上")
                            || after.startsWith("的") || after.startsWith(" "))
                            return tid;
                    }
                }
            }
        }

        // Pattern: investigate <target> ...
        for (String prefix : List.of("investigate ")) {
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix)) {
                String rest = lower.substring(prefix.length());
                for (String tid : registeredTargets) {
                    if (rest.startsWith(tid)) {
                        String after = rest.substring(tid.length());
                        if (after.isEmpty() || Character.isWhitespace(after.charAt(0))
                            || after.startsWith("上") || after.startsWith("的"))
                            return tid;
                    }
                }
            }
        }

        return null;
    }
}
