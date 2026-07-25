package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Loopback-only broker used to keep database credentials out of the agent process. */
public final class OpsMcpHttpMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private OpsMcpHttpMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[A-Za-z0-9]{16,128}")) {
            throw new IllegalArgumentException("usage: <port> <unguessable-token>");
        }
        int port = Integer.parseInt(args[0]);
        OpsCapabilityProfile profile = OpsCapabilityProfile.fromEnvironment(
            System.getenv("CLAWKIT_OPS_PROFILE"));
        OpsTargetConfig config = OpsTargetConfig.fromEnvironment(System.getenv());
        CommandExecutor commands = resolveCommandExecutor(System.getenv());
        OpsBackend backend = new DockerOpsBackend(config, commands,
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build(),
            java.time.Clock.systemUTC(), Map.of());
        if (profile == OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1) {
            backend = new CompositeOpsBackend(backend,
                JdbcPostgresDiagnosticBackend.fromEnvironment(System.getenv()));
        }
        OpsMcpServer mcp = new OpsMcpServer(backend, profile);
        HttpServer http = HttpServer.create(new InetSocketAddress(
            InetAddress.getLoopbackAddress(), port), 8);
        http.createContext("/mcp/" + args[1], exchange -> handle(exchange, mcp));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> http.stop(0)));
        http.start();
        new CountDownLatch(1).await();
    }

    private static void handle(HttpExchange exchange, OpsMcpServer server) {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(65_537);
            if (body.length > 65_536) {
                send(exchange, 413, "{\"error\":\"request too large\"}");
                return;
            }
            JsonNode request = MAPPER.readTree(body);
            JsonNode id = request.get("id");
            if (id == null) {
                if (isNotification(request)) {
                    sendEmpty(exchange, 202);
                    return;
                }
                send(exchange, 400, "{\"error\":\"request id required\"}");
                return;
            }
            String response = MAPPER.writeValueAsString(server.handle(id,
                request.path("method").asText(), request.path("params")));
            send(exchange, 200, response);
        } catch (Exception error) {
            try { send(exchange, 400, "{\"error\":\"invalid request\"}"); }
            catch (Exception ignored) { }
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws Exception {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, -1);
    }

    static CommandExecutor resolveCommandExecutor(Map<String, String> env) {
        String sshHost = env.get("CLAWKIT_OPS_SSH_HOST");
        if (sshHost != null && !sshHost.isBlank()) {
            return new SshCommandExecutor(SshTargetConfig.fromEnvironment(env));
        }
        return new ProcessCommandExecutor();
    }

    static boolean isNotification(JsonNode request) {
        return request != null
            && "2.0".equals(request.path("jsonrpc").asText())
            && !request.has("id")
            && request.path("method").asText("").startsWith("notifications/");
    }
}
