package com.clawkit.cli;

import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine3 tab completer for clawkit slash commands.
 */
class ClawkitCompleter implements Completer {

    private static final List<String> COMMANDS = List.of(
        "/help", "/clear", "/compact", "/context",
        "/remember", "/memory",
        "/thinking", "/plan", "/ask", "/auto",
        "/session", "/session save", "/session load", "/session list",
        "/session delete", "/session search", "/session stats",
        "/session prune", "/session new",
        "/skill", "/skill list", "/skill load", "/skill unload",
        "/feishu-on", "/feishu-off",
        "/exit"
    );

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word().toLowerCase();
        if (word.startsWith("/")) {
            for (String cmd : COMMANDS) {
                if (cmd.startsWith(word)) {
                    candidates.add(new Candidate(cmd));
                }
            }
        }
    }
}
