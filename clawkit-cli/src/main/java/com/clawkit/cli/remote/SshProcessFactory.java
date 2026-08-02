package com.clawkit.cli.remote;

import java.io.IOException;
import java.util.List;

/**
 * Injectable factory for SSH subprocesses. Enables testing without
 * depending on system {@code ssh} in PATH.
 */
public interface SshProcessFactory {
    /** Start a process. Caller must drain streams and destroy. */
    Process start(List<String> command) throws IOException;

    /** Production factory using ProcessBuilder. */
    final class Default implements SshProcessFactory {
        @Override
        public Process start(List<String> command) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            return pb.start();
        }
    }
}
