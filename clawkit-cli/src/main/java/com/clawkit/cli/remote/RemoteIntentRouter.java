package com.clawkit.cli.remote;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic narrow-format router for remote connection intents.
 *
 * <p>Only matches exact registered targetIds in fixed sentence patterns.
 * Does NOT accept IPs, hostnames, URLs, or unregistered IDs.
 * Non-matching input is returned as empty — the caller should pass it
 * to the normal LLM conversation.
 *
 * <p>Design: REMOTE-0 §10.4.
 */
public class RemoteIntentRouter {

    // Patterns for natural-language connection syntax sugar
    private static final Pattern CONNECT_PATTERN = Pattern.compile(
        "^(?:连接到?|连接|connect\\s+to\\s+)([a-z0-9][a-z0-9_-]{0,62})(?:\\s*服务器)?[\\s。.]*$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern STATUS_PATTERN = Pattern.compile(
        "^(?:查看)?(?:远程)?连接状态[\\s。.]*$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern DISCONNECT_PATTERN = Pattern.compile(
        "^(?:断开)(?:远程)?连接[\\s。.]*$",
        Pattern.CASE_INSENSITIVE);

    private final RemoteTargetStore store;

    public RemoteIntentRouter(RemoteTargetStore store) {
        this.store = store;
    }

    /** Recognized intent types. */
    public enum Intent {
        CONNECT,
        STATUS,
        DISCONNECT,
        NONE
    }

    public record ResolvedIntent(Intent intent, String targetId) {}

    /**
     * Try to resolve a user input to a remote connection intent.
     * Only succeeds if the targetId (if any) is already registered.
     *
     * @return the resolved intent, or {@code Intent.NONE} if no match
     */
    public ResolvedIntent resolve(String input) {
        if (input == null || input.isBlank()) return new ResolvedIntent(Intent.NONE, "");

        String trimmed = input.strip();

        // Check disconnect first
        Matcher dm = DISCONNECT_PATTERN.matcher(trimmed);
        if (dm.matches()) return new ResolvedIntent(Intent.DISCONNECT, "");

        // Check status
        Matcher sm = STATUS_PATTERN.matcher(trimmed);
        if (sm.matches()) return new ResolvedIntent(Intent.STATUS, "");

        // Check connect with targetId
        Matcher cm = CONNECT_PATTERN.matcher(trimmed);
        if (cm.matches()) {
            String targetId = cm.group(1).toLowerCase();
            if (store.get(targetId).isPresent()) {
                return new ResolvedIntent(Intent.CONNECT, targetId);
            }
            // targetId not registered — don't match (let LLM explain it's unknown)
        }

        return new ResolvedIntent(Intent.NONE, "");
    }
}
