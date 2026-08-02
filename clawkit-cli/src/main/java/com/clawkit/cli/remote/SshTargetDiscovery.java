package com.clawkit.cli.remote;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static SSH config scanner — discovers explicit Host aliases without
 * network access or command execution.
 *
 * <p>Design: PRODUCT-1 §7.1.
 */
public final class SshTargetDiscovery {

    // Match "Host <tokens>" — but not comments
    private static final Pattern HOST_PATTERN =
        Pattern.compile("^\\s*Host\\s+(.+)", Pattern.CASE_INSENSITIVE);
    // Match "Include <path> [path...]" — captures everything after Include
    private static final Pattern INCLUDE_PATTERN =
        Pattern.compile("^\\s*Include\\s+(.+)", Pattern.CASE_INSENSITIVE);
    // Match "Match [...] exec [...]" — exec keyword anywhere in Match value
    private static final Pattern MATCH_EXEC_PATTERN =
        Pattern.compile("^\\s*Match\\s+.*\\bexec\\b", Pattern.CASE_INSENSITIVE);
    // Unsafe directives (ProxyCommand, KnownHostsCommand, LocalCommand, RemoteCommand)
    private static final Pattern UNSAFE_DIRECTIVE_PATTERN = Pattern.compile(
        "^\\s*(ProxyCommand|KnownHostsCommand|LocalCommand|RemoteCommand)\\b",
        Pattern.CASE_INSENSITIVE);
    // Comment line
    private static final Pattern COMMENT_PATTERN =
        Pattern.compile("^\\s*#");

    private static final Pattern WILDCARD_PATTERN = Pattern.compile("[*?!]");
    private static final Pattern NEGATION_PATTERN = Pattern.compile("^!");

    static final int MAX_INCLUDE_DEPTH = 5;
    static final int MAX_INCLUDE_FILES = 50;

    private final SystemOpenSshFacade sshFacade;

    public SshTargetDiscovery(SystemOpenSshFacade sshFacade) {
        this.sshFacade = sshFacade;
    }

    /**
     * Discover explicit Host aliases from SSH config files.
     *
     * @return result with aliases and any unsafe findings
     */
    public DiscoveryResult discover() {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> aliases = new LinkedHashSet<>();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> unsafeReasons = new ArrayList<>();

        try {
            scanConfig(sshFacade.defaultUserConfigPath(), 0, visited,
                aliases, sources, unsafeReasons);
            Path sysConfig = sshFacade.defaultSystemConfigPath();
            if (sysConfig != null) {
                scanConfig(sysConfig, 0, visited, aliases, sources, unsafeReasons);
            }
        } catch (Exception e) {
            unsafeReasons.add("config scan failed: " + e.getMessage());
        }

        boolean safe = unsafeReasons.isEmpty();
        return new DiscoveryResult(
            safe, List.copyOf(aliases), Map.copyOf(sources), List.copyOf(unsafeReasons));
    }

    void scanConfig(Path file, int depth, Set<String> visited,
                    Set<String> aliases, Map<String, String> sources,
                    List<String> reasons) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return;
        String absPath = file.toRealPath().toString();
        if (!visited.add(absPath)) return; // cycle detection
        if (depth > MAX_INCLUDE_DEPTH) {
            reasons.add("include depth exceeded at " + absPath + " (max " + MAX_INCLUDE_DEPTH + ")");
            return;
        }
        if (visited.size() > MAX_INCLUDE_FILES) {
            reasons.add("include file limit exceeded (max " + MAX_INCLUDE_FILES + ")");
            return;
        }

        List<String> lines = sshFacade.readConfigFile(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) continue;
            // Skip comments — must NOT match unsafe directives in comments
            if (COMMENT_PATTERN.matcher(line).find()) continue;

            String location = absPath + ":" + (i + 1);

            // Check for Match exec — exec keyword anywhere in the Match value
            if (MATCH_EXEC_PATTERN.matcher(line).find()) {
                reasons.add("unsafe Match exec in " + location + ": " + line);
                continue;
            }

            // Check for ProxyCommand / KnownHostsCommand / LocalCommand / RemoteCommand
            if (UNSAFE_DIRECTIVE_PATTERN.matcher(line).find()) {
                reasons.add("unsafe directive in " + location + ": " + line);
                continue;
            }

            // Include — supports multiple paths and quotes
            var incMatcher = INCLUDE_PATTERN.matcher(line);
            if (incMatcher.matches()) {
                String includeValue = incMatcher.group(1).strip();
                List<String> includePaths = parseIncludePaths(includeValue);
                for (String includeGlob : includePaths) {
                    List<Path> resolved = resolveIncludeGlob(includeGlob, file.getParent());
                    if (resolved.isEmpty()) {
                        reasons.add("include not found/unreadable in " + location
                            + ": " + includeGlob);
                    }
                    for (Path includeFile : resolved) {
                        scanConfig(includeFile, depth + 1, visited,
                            aliases, sources, reasons);
                    }
                }
                continue;
            }

            // Host
            var hostMatcher = HOST_PATTERN.matcher(line);
            if (hostMatcher.matches()) {
                String hostTokens = hostMatcher.group(1).strip();
                for (String token : hostTokens.split("\\s+")) {
                    if (token.equals("*")) continue; // wildcard catch-all
                    if (NEGATION_PATTERN.matcher(token).find()) continue; // !pattern
                    if (WILDCARD_PATTERN.matcher(token).find()) continue; // *? patterns
                    try {
                        com.clawkit.tools.remote.RemoteSshSafetyPolicy.validateAlias(token);
                        aliases.add(token);
                        sources.putIfAbsent(token, location);
                    } catch (IllegalArgumentException ignored) {
                        // Not a valid alias — skip
                    }
                }
            }
        }
    }

    /**
     * Parse Include value into individual path tokens.
     * Handles quoted paths (single and double quotes).
     * Example: {@code Include "~/foo.conf" /etc/ssh/*.conf}
     */
    static List<String> parseIncludePaths(String includeValue) {
        List<String> paths = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < includeValue.length(); i++) {
            char c = includeValue.charAt(i);
            if (inSingle) {
                if (c == '\'') { inSingle = false; }
                else { current.append(c); }
            } else if (inDouble) {
                if (c == '"') { inDouble = false; }
                else { current.append(c); }
            } else {
                if (c == '\'') { inSingle = true; }
                else if (c == '"') { inDouble = true; }
                else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        paths.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (current.length() > 0) paths.add(current.toString());
        return paths;
    }

    /**
     * Resolve an Include glob pattern to actual files.
     * Expands ~ to user home, resolves relative paths, and evaluates globs.
     */
    static List<Path> resolveIncludeGlob(String globPattern, Path relativeTo) {
        String resolved = globPattern;
        // Expand ~
        if (resolved.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home == null) home = System.getenv("HOME");
            if (home == null) home = System.getenv("USERPROFILE");
            if (home != null) {
                resolved = home + resolved.substring(1);
            }
        }

        Path path = Path.of(resolved);
        if (!path.isAbsolute() && relativeTo != null) {
            path = relativeTo.resolve(path).normalize();
        }

        // If the path contains glob metacharacters, expand
        if (hasGlobMeta(resolved)) {
            return expandGlob(path);
        }

        // Simple file path
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        return List.of();
    }

    private static boolean hasGlobMeta(String s) {
        return s.contains("*") || s.contains("?") || s.contains("[");
    }

    private static List<Path> expandGlob(Path globPath) {
        Path parent = globPath.getParent();
        if (parent == null) return List.of();
        if (!Files.isDirectory(parent)) return List.of();

        String glob = globPath.getFileName().toString();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<Path> results = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parent)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p.getFileName()))
                .sorted()
                .forEach(results::add);
        } catch (IOException ignored) {
            // unreadable directory — fail closed (empty result)
        }
        return results;
    }

    /** Result of SSH config discovery. */
    public record DiscoveryResult(
        boolean safe,
        List<String> aliases,
        Map<String, String> sources,
        List<String> unsafeReasons
    ) {}
}
