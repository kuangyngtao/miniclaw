package com.miniclaw.cli;

import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * JLine3 tab completer for miniclaw slash commands.
 */
class MiniclawCompleter implements Completer {

    private static final List<String> COMMANDS = List.of(
        "/help", "/clear", "/compact", "/context",
        "/remember", "/memory",
        "/thinking", "/plan", "/ask", "/auto",
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
