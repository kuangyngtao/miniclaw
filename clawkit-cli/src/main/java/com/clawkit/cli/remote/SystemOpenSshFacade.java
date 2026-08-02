package com.clawkit.cli.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Facade for local system OpenSSH operations.
 *
 * <h3>Process lifecycle (single-owner)</h3>
 * Every process is:
 * <ol>
 *   <li>Started</li>
 *   <li>stdout/stderr drained concurrently with byte limit and deadline</li>
 *   <li>Exit code checked</li>
 *   <li>Destroyed (with bounded grace) on ANY non-normal path</li>
 * </ol>
 * No process survives past method return. No common-pool executor leakage.
 */
public class SystemOpenSshFacade {

    private static final int SSH_G_TIMEOUT_SECONDS = 10;
    private static final int MAX_SSH_G_OUTPUT_BYTES = 65536;
    private static final int SSH_V_TIMEOUT_SECONDS = 5;

    private final Path userConfigDir;
    private final Path systemConfigDir;
    private final SshProcessFactory processFactory;
    private final ExecutorService ioExecutor;

    public SystemOpenSshFacade() {
        this(defaultUserSshDir(), defaultSystemSshDir(), new SshProcessFactory.Default());
    }

    public SystemOpenSshFacade(Path userConfigDir, Path systemConfigDir) {
        this(userConfigDir, systemConfigDir, new SshProcessFactory.Default());
    }

    public SystemOpenSshFacade(Path userConfigDir, Path systemConfigDir,
                                SshProcessFactory processFactory) {
        this.userConfigDir = userConfigDir;
        this.systemConfigDir = systemConfigDir;
        this.processFactory = processFactory;
        this.ioExecutor = newIoExecutor();
    }

    // ── ssh -V ────────────────────────────────────────────────────────

    /** Check that OpenSSH is available. Always destroys the process. */
    public SshVersionResult checkVersion() throws IOException {
        Process p = null;
        var readers = newIoExecutor();
        Future<DrainResult> stdoutFuture = null;
        Future<DrainResult> stderrFuture = null;
        try {
            p = processFactory.start(List.of("ssh", "-V"));
            long deadline = deadline(SSH_V_TIMEOUT_SECONDS);
            Process processRef = p;
            stdoutFuture = readers.submit(() -> drain(processRef.getInputStream(),
                MAX_SSH_G_OUTPUT_BYTES));
            stderrFuture = readers.submit(() -> drain(processRef.getErrorStream(),
                MAX_SSH_G_OUTPUT_BYTES));

            boolean finished;
            try {
                finished = waitWithDeadline(p, deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) return new SshVersionResult("", false);
            DrainResult out = awaitDrain(stdoutFuture, deadline, "ssh -V stdout");
            DrainResult err = awaitDrain(stderrFuture, deadline, "ssh -V stderr");
            String text = (!out.output.isEmpty() ? out.output : err.output);
            int exit = finished ? p.exitValue() : -1;

            return new SshVersionResult(text,
                (exit == 0 || exit == 255) && finished && !out.overLimit && !err.overLimit);

        } finally {
            cancelFuture(stdoutFuture);
            cancelFuture(stderrFuture);
            destroyProcess(p);
            shutdownExecutor(readers);
        }
    }

    public record SshVersionResult(String version, boolean available) {}

    // ── ssh -G ────────────────────────────────────────────────────────

    public SshGResult runSshG(String alias, String remoteUser) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.addAll(
            com.clawkit.tools.remote.RemoteSshSafetyPolicy.safetyArgs(SSH_G_TIMEOUT_SECONDS));
        command.add("-G");
        command.add("-l"); command.add(remoteUser);
        command.add(alias);

        Process p = null;
        var readers = newIoExecutor();
        Future<DrainResult> stdoutFuture = null;
        Future<DrainResult> stderrFuture = null;
        try {
            p = processFactory.start(command);
            long deadline = deadline(SSH_G_TIMEOUT_SECONDS);

            // Concurrent drain with a per-call executor that is always shut down.
            Process processRef = p;
            stdoutFuture = readers.submit(() ->
                drain(processRef.getInputStream(), MAX_SSH_G_OUTPUT_BYTES));
            stderrFuture = readers.submit(() ->
                drain(processRef.getErrorStream(), MAX_SSH_G_OUTPUT_BYTES));

            boolean finished = waitWithDeadline(p, deadline);

            if (!finished) {
                throw new IOException("ssh -G timed out after "
                    + SSH_G_TIMEOUT_SECONDS + "s for alias: " + alias);
            }
            DrainResult stdout = awaitDrain(stdoutFuture, deadline, "ssh -G stdout");
            DrainResult stderr = awaitDrain(stderrFuture, deadline, "ssh -G stderr");

            if (stdout.overLimit || stderr.overLimit) {
                throw new IOException("ssh -G stdout exceeded limit of "
                    + MAX_SSH_G_OUTPUT_BYTES + " bytes for alias: " + alias);
            }

            int exitCode = p.exitValue();
            if (exitCode != 0) {
                throw new IOException("ssh -G exited with code " + exitCode
                    + " for alias: " + alias);
            }

            List<String> rawLines = new ArrayList<>();
            for (String line : stdout.output.split("\n")) {
                if (!line.isEmpty()) rawLines.add(line);
            }
            return parseSshGOutput(rawLines);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ssh -G interrupted: " + alias, e);
        } finally {
            cancelFuture(stdoutFuture);
            cancelFuture(stderrFuture);
            destroyProcess(p);
            shutdownExecutor(readers);
        }
    }

    // ── Stream drain ──────────────────────────────────────────────────

    private record DrainResult(String output, int totalBytes, boolean overLimit) {
        static final DrainResult EMPTY = new DrainResult("", 0, false);
    }

    /**
     * Drain an input stream with byte limit. Closes the stream on exit.
     * Reads through EOF up to maxBytes. The caller owns the execution deadline
     * and cancels the reader after destroying the process on timeout.
     */
    private static DrainResult drain(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) return DrainResult.EMPTY;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int totalBytes = 0;
        try (stream) {
            byte[] readBuf = new byte[4096];
            int n;
            while ((n = stream.read(readBuf)) != -1) {
                int remaining = maxBytes - totalBytes;
                if (remaining <= 0) {
                    return new DrainResult(buf.toString(StandardCharsets.UTF_8), totalBytes, true);
                }
                int retained = Math.min(n, remaining);
                buf.write(readBuf, 0, retained);
                totalBytes += retained;
                if (retained < n) {
                    return new DrainResult(buf.toString(StandardCharsets.UTF_8), totalBytes, true);
                }
            }
        }
        return new DrainResult(
            buf.toString(StandardCharsets.UTF_8),
            totalBytes,
            false);
    }

    // ── Process control ───────────────────────────────────────────────

    private static long deadline(int timeoutSec) {
        return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec);
    }

    private static boolean waitWithDeadline(Process p, long deadlineMs)
            throws InterruptedException {
        long remain = deadlineMs - System.currentTimeMillis();
        if (remain <= 0) return false;
        return p.waitFor(remain, TimeUnit.MILLISECONDS);
    }

    private static java.util.concurrent.ExecutorService newIoExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ssh-facade-io");
            t.setDaemon(true);
            return t;
        });
    }

    private static DrainResult awaitDrain(Future<DrainResult> future, long deadlineMs,
                                           String streamName) throws IOException {
        long remain = deadlineMs - System.currentTimeMillis();
        if (remain <= 0) throw new IOException(streamName + " reader timed out");
        try {
            return future.get(remain, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(streamName + " reader interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(streamName + " reader failed", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException(streamName + " reader timed out", e);
        }
    }

    private static void shutdownExecutor(java.util.concurrent.ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void destroyProcess(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            if (p.waitFor(2, TimeUnit.SECONDS)) return;
            p.destroyForcibly();
            p.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private static void cancelFuture(Future<?> f) {
        if (f != null) f.cancel(true);
    }

    // ── ssh -G output parsing ─────────────────────────────────────────

    static SshGResult parseSshGOutput(List<String> lines) {
        Map<String, String> config = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf(' ');
            if (eq > 0) {
                config.put(
                    line.substring(0, eq).trim().toLowerCase(),
                    line.substring(eq + 1).trim());
            }
        }

        String hostname = config.get("hostname");
        String port = config.get("port");
        String user = config.get("user");
        String proxyjump = config.get("proxyjump");
        String hostkeyalias = config.get("hostkeyalias");
        String userknownhostsfile = config.get("userknownhostsfile");
        String identityfile = config.get("identityfile");
        String identityagent = config.get("identityagent");
        String proxycommand = config.get("proxycommand");

        boolean hasProxyCommand = proxycommand != null && !proxycommand.isBlank()
            && !"none".equalsIgnoreCase(proxycommand);

        int identityCount = 0;
        if (identityfile != null && !identityfile.isBlank()) {
            identityCount = (int) lines.stream()
                .filter(l -> l.toLowerCase().startsWith("identityfile "))
                .count();
        }
        boolean agentEnabled = identityagent != null
            && identityagent.contains("SSH_AUTH_SOCK");

        return new SshGResult(
            hostname != null ? hostname : "",
            port != null ? parsePort(port) : 22,
            user != null ? user : "",
            proxyjump != null && !proxyjump.isBlank() ? proxyjump : null,
            hostkeyalias != null && !hostkeyalias.isBlank() ? hostkeyalias : null,
            userknownhostsfile != null ? userknownhostsfile : "",
            identityCount, agentEnabled,
            hasProxyCommand, hasProxyCommand);
    }

    private static int parsePort(String s) {
        try { int p = Integer.parseInt(s); return (p > 0 && p < 65536) ? p : 22; }
        catch (NumberFormatException e) { return 22; }
    }

    public record SshGResult(
        String hostname, int port, String user,
        String proxyJump, String hostKeyAlias, String userKnownHostsFile,
        int identityFileCount, boolean agentEnabled,
        boolean hasUnsafeConfig, boolean hasProxyCommand
    ) {
        public boolean hasProxyJump() { return proxyJump != null && !proxyJump.isBlank(); }
    }

    // ── Config path helpers ───────────────────────────────────────────

    public Path defaultUserConfigPath() {
        return userConfigDir.resolve("config");
    }

    public Path defaultSystemConfigPath() {
        if (systemConfigDir == null) return null;
        return systemConfigDir.resolve("ssh_config");
    }

    public List<String> readConfigFile(Path configFile) throws IOException {
        if (configFile == null || !Files.isRegularFile(configFile)) return List.of();
        return Files.readAllLines(configFile);
    }

    // ── Package access for tests ──────────────────────────────────────

    ExecutorService ioExecutor() { return ioExecutor; }

    private static Path defaultUserSshDir() {
        String home = System.getProperty("user.home");
        if (home == null) home = System.getenv("HOME");
        if (home == null) home = System.getenv("USERPROFILE");
        return home != null ? Path.of(home, ".ssh") : Path.of(".ssh");
    }

    private static Path defaultSystemSshDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String progData = System.getenv("PROGRAMDATA");
            return progData != null ? Path.of(progData, "ssh") : null;
        }
        return Path.of("/etc/ssh");
    }
}
