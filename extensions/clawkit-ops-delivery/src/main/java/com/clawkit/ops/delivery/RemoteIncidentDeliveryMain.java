package com.clawkit.ops.delivery;

import com.clawkit.im.feishu.FeishuApi;
import com.clawkit.ops.loop.RemoteDiscoveryWorkflow;
import com.clawkit.ops.loop.RemoteDiscoveryMain;
import com.clawkit.ops.loop.RemoteIncidentResult;
import com.clawkit.ops.loop.notify.NotificationOutbox;
import com.clawkit.ops.loop.notify.OpsFeishuNotifier;
import com.clawkit.ops.loop.report.HumanIncidentReport;
import com.clawkit.ops.loop.report.IncidentReportAssembler;
import com.clawkit.ops.loop.report.JsonIncidentRenderer;
import com.clawkit.ops.loop.report.MarkdownIncidentRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Production entry: Discovery → Diagnosis → Report → optional Feishu.
 *
 * <p>R3. Default: no Feishu. Use {@code --notify} with env config to send.
 */
public final class RemoteIncidentDeliveryMain {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private RemoteIncidentDeliveryMain() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        try {
            return runInternal(args);
        } catch (RemoteDiscoveryMain.ConfigException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }

    private static int runInternal(String[] args) {
        String targetId = null;
        Path outputDir = Path.of(".");
        boolean notify = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> targetId = nextArg(args, i++);
                case "--output" -> outputDir = Path.of(nextArg(args, i++));
                case "--notify" -> notify = true;
                default -> {
                    System.err.println("usage: deliver --target <id> [--output <dir>] [--notify]");
                    return 4;
                }
            }
        }
        if (targetId == null) { System.err.println("--target required"); return 4; }

        try { Files.createDirectories(outputDir); }
        catch (IOException e) { System.err.println("output dir: " + e.getMessage()); return 5; }

        try {
            RemoteDiscoveryWorkflow.Config wf = buildConfig(targetId);
            RemoteIncidentResult result = new RemoteDiscoveryWorkflow(wf).execute();

            String rid = result.discovery().runId();
            Path rf = outputDir.resolve("incident-" + rid + ".json");
            atomicWriteJson(rf, result);
            HumanIncidentReport report = IncidentReportAssembler.assemble(result);
            Path rj = outputDir.resolve("report-" + rid + ".json");
            atomicWriteText(rj, JsonIncidentRenderer.render(report));
            Path rm = outputDir.resolve("report-" + rid + ".md");
            atomicWriteText(rm, MarkdownIncidentRenderer.render(report));
            System.out.println("incident: " + rf.toAbsolutePath());
            System.out.println("report:   " + rj.toAbsolutePath());
            System.out.println("markdown: " + rm.toAbsolutePath());

            if (notify && tryNotify(report, outputDir) != 0)
                System.err.println("feishu failed (incident preserved)");

            if (!result.providerCalled() && result.diagnosisFailureCode() != null
                && result.diagnosisFailureCode().startsWith("PROVIDER_")) return 6;
            return switch (result.discovery().status()) {
                case COMPLETE -> 0; case INCOMPLETE -> 2; case TRANSPORT_FAILED -> 3;
            };
        } catch (RemoteDiscoveryMain.ConfigException e) {
            System.err.println(e.getMessage()); return 4;
        } catch (Exception e) {
            System.err.println("delivery: " + e.getMessage()); return 3;
        }
    }

    private static int tryNotify(HumanIncidentReport report, Path dir) {
        String appId = System.getenv("FEISHU_APP_ID");
        String secret = System.getenv("FEISHU_APP_SECRET");
        String chatId = System.getenv("FEISHU_OPS_CHAT_ID");
        if (appId == null || secret == null || chatId == null) {
            System.err.println("feishu not configured — skip"); return 1;
        }
        var outbox = new NotificationOutbox(dir.resolve(".outbox"));
        var api = new FeishuApi(appId, secret);
        var notifier = new OpsFeishuNotifier(
            (c, content, key) -> { try { return api.sendChatMessage(c, content, key); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } },
            (m, content, key) -> { try { return api.replyMessage(m, content, key); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } },
            chatId, outbox);
        var entry = notifier.notify(report, NotificationOutbox.EventType.INITIAL_REPORT);
        return entry != null && entry.state() == NotificationOutbox.State.SENT ? 0 : 2;
    }

    private static RemoteDiscoveryWorkflow.Config buildConfig(String targetId) {
        return new RemoteDiscoveryWorkflow.Config(targetId,
            require("CLAWKIT_REMOTE_OPS_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_PORT", "22")),
            require("CLAWKIT_REMOTE_OPS_USER"),
            Path.of(require("CLAWKIT_REMOTE_OPS_IDENTITY_FILE")),
            Path.of(System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_KNOWN_HOSTS",
                System.getProperty("user.home") + "/.ssh/known_hosts")),
            require("CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE"),
            System.getenv().getOrDefault("CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION", "1"),
            System.getenv("CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH"),
            System.getenv("CLAWKIT_API_KEY"),
            System.getenv().getOrDefault("CLAWKIT_DIAGNOSIS_MODEL",
                RemoteDiscoveryWorkflow.Config.DEFAULT_DIAGNOSIS_MODEL),
            Duration.ofSeconds(120));
    }

    private static String nextArg(String[] args, int i) {
        if (i + 1 >= args.length) throw new RemoteDiscoveryMain.ConfigException("missing value for " + args[i]);
        return args[i + 1];
    }

    static void atomicWriteJson(Path target, Object content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void atomicWriteText(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new RemoteDiscoveryMain.ConfigException("missing env: " + name);
        return v;
    }
}
