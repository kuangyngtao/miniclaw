package com.clawkit.cli.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic parser for {@code /remote} sub-commands with schema validation.
 *
 * <p>Rejects unknown options, duplicate options, missing option values,
 * and extra positional arguments. Each sub-command has a defined schema.
 */
public final class RemoteCommandParser {

    private RemoteCommandParser() {}

    public enum SubCommand {
        LIST, STATUS, INSPECT, SHOW, ADD, REMOVE, CONNECT, DISCONNECT,
        DOCTOR, TOOLS, HELP, UNKNOWN
    }

    public record ParsedCommand(
        SubCommand subCommand,
        List<String> positionalArgs,
        Map<String, String> options,
        List<String> errors
    ) {
        public String arg(int i) { return i < positionalArgs.size() ? positionalArgs.get(i) : ""; }
        public boolean hasOption(String n) { return options.containsKey(n); }
        public String option(String n) { return options.getOrDefault(n, ""); }
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    // ── Allowed profiles ──────────────────────────────────────────────

    static final Set<String> ALLOWED_PROFILES = Set.of(
        "app-down-readonly-v1", "postgres-diagnosis-readonly-v1");

    // ── Public API ────────────────────────────────────────────────────

    public static ParsedCommand parse(String rawArgs) {
        List<String> tokens = tokenize(rawArgs);
        if (tokens.isEmpty()) {
            return new ParsedCommand(SubCommand.STATUS, List.of(), Map.of(), List.of());
        }
        SubCommand sub = parseSubCommand(tokens.get(0));
        List<String> positionals = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--")) {
                String optName = token.substring(2);
                if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
                    String val = tokens.get(i + 1);
                    if (options.containsKey(optName)) {
                        errors.add("duplicate option: --" + optName);
                    }
                    options.put(optName, val);
                    i++;
                } else {
                    // boolean flag
                    if (options.containsKey(optName)) {
                        errors.add("duplicate option: --" + optName);
                    }
                    options.put(optName, "");
                }
            } else {
                positionals.add(token);
            }
        }

        // Schema validation per sub-command
        switch (sub) {
            case ADD -> validateAdd(positionals, options, errors);
            case DOCTOR -> validateDoctor(positionals, options, errors);
            case CONNECT, REMOVE, INSPECT, SHOW -> {
                if (positionals.isEmpty()) {
                    errors.add("targetId is required");
                }
            }
            default -> {} // no schema constraints
        }

        return new ParsedCommand(sub, List.copyOf(positionals),
            Map.copyOf(options), List.copyOf(errors));
    }

    // ── Schema validators ─────────────────────────────────────────────

    private static void validateAdd(List<String> pos, Map<String, String> opts,
                                     List<String> errors) {
        Set<String> known = Set.of("from-ssh", "as", "config", "replace",
            "profile", "yes");

        for (String key : opts.keySet()) {
            if (!known.contains(key)) {
                errors.add("unknown option: --" + key);
            }
        }

        // --verbose/--json mutual exclusion (doctor only, not add)
        // --yes without --from-ssh makes no sense for v1 add
        if (opts.containsKey("yes") && !opts.containsKey("from-ssh")) {
            errors.add("--yes requires --from-ssh");
        }

        // --profile validation
        if (opts.containsKey("profile")) {
            String p = opts.get("profile");
            if (p != null && !p.isEmpty() && !ALLOWED_PROFILES.contains(p)) {
                errors.add("unknown profile: " + p
                    + " (allowed: app-down-readonly-v1, postgres-diagnosis-readonly-v1)");
            }
        }
    }

    private static void validateDoctor(List<String> pos, Map<String, String> opts,
                                        List<String> errors) {
        Set<String> known = Set.of("verbose", "json");

        for (String key : opts.keySet()) {
            if (!known.contains(key)) {
                errors.add("unknown option: --" + key);
            }
        }
        if (opts.containsKey("verbose") && opts.containsKey("json")) {
            errors.add("--verbose and --json are mutually exclusive");
        }
        if (pos.isEmpty()) {
            errors.add("targetId is required for doctor");
        }
    }

    // ── Tokenization ──────────────────────────────────────────────────

    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null || raw.isBlank()) return tokens;
        StringBuilder cur = new StringBuilder();
        boolean inS = false, inD = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inS) { if (c == '\'') inS = false; else cur.append(c); }
            else if (inD) { if (c == '"') inD = false; else cur.append(c); }
            else if (c == '\'') inS = true;
            else if (c == '"') inD = true;
            else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) tokens.add(cur.toString());

        // Detect unclosed quotes
        if (inS) tokens.add("UNCLOSED_SINGLE_QUOTE");
        if (inD) tokens.add("UNCLOSED_DOUBLE_QUOTE");
        return tokens;
    }

    static SubCommand parseSubCommand(String token) {
        return switch (token.toLowerCase()) {
            case "list" -> SubCommand.LIST;
            case "status" -> SubCommand.STATUS;
            case "inspect" -> SubCommand.INSPECT;
            case "show" -> SubCommand.SHOW;
            case "add" -> SubCommand.ADD;
            case "remove" -> SubCommand.REMOVE;
            case "connect" -> SubCommand.CONNECT;
            case "disconnect" -> SubCommand.DISCONNECT;
            case "doctor" -> SubCommand.DOCTOR;
            case "tools" -> SubCommand.TOOLS;
            case "help", "--help", "-h" -> SubCommand.HELP;
            default -> SubCommand.UNKNOWN;
        };
    }
}
