package com.clawkit.ops.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProcessCommandExecutor implements CommandExecutor {

    @Override
    public CommandResult execute(List<String> command, Map<String, String> environment,
                                 Duration timeout, int maxOutputBytes) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            builder.environment().putAll(environment);
            process = builder.start();
            Process running = process;

            var stdout = CompletableFuture.supplyAsync(
                () -> readBounded(running.getInputStream(), maxOutputBytes));
            var stderr = CompletableFuture.supplyAsync(
                () -> readBounded(running.getErrorStream(), maxOutputBytes));

            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            }

            StreamCapture out = stdout.get(2, TimeUnit.SECONDS);
            StreamCapture err = stderr.get(2, TimeUnit.SECONDS);
            return new CommandResult(
                exited ? process.exitValue() : -1, out.text(), err.text(), !exited,
                out.truncated() || err.truncated(), out.totalBytes() + err.totalBytes());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, "", "command interrupted", true, false, 0);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, "", e.getMessage(), false, false, 0);
        }
    }

    private static StreamCapture readBounded(InputStream input, int limit) {
        long total = 0;
        try (input; var retained = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                int remaining = limit - retained.size();
                if (remaining > 0) {
                    retained.write(buffer, 0, Math.min(remaining, read));
                }
            }
            return new StreamCapture(retained.toString(StandardCharsets.UTF_8),
                total, total > limit);
        } catch (IOException e) {
            return new StreamCapture(
                "[stream read failed: " + e.getMessage() + "]", total, false);
        }
    }

    private record StreamCapture(String text, long totalBytes, boolean truncated) {}
}
