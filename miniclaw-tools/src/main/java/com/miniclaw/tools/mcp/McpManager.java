package com.miniclaw.tools.mcp;

import com.miniclaw.tools.Tool;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP 管理器：并行启动所有 server、工具聚合、故障隔离、生命周期管理。 */
public class McpManager {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final int STARTUP_TIMEOUT_SEC = 35;

    private final Map<String, McpClientState> clients = new ConcurrentHashMap<>();
    private final ExecutorService startupExecutor;
    private McpConfig config;
    private Path workDir;

    public McpManager() {
        this.startupExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "mcp-startup");
            t.setDaemon(true);
            return t;
        });
    }

    /** 并行启动所有 server，返回全部适配工具。 */
    public List<Tool> startAll(McpConfig config, Path workDir) {
        this.config = config;
        this.workDir = workDir;
        Map<String, McpServerConfig> servers = config.servers();
        if (servers.isEmpty()) {
            log.debug("[MCP] no servers configured");
            return List.of();
        }

        List<CompletableFuture<List<Tool>>> futures = new ArrayList<>();
        for (McpServerConfig sc : servers.values()) {
            if (sc.disabled()) {
                log.info("[MCP] {}: disabled, skipping", sc.name());
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> startServer(sc), startupExecutor));
        }

        List<Tool> allTools = new ArrayList<>();
        for (var future : futures) {
            try {
                allTools.addAll(future.get(STARTUP_TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                log.warn("[MCP] server startup timed out after {}s", STARTUP_TIMEOUT_SEC);
            } catch (Exception e) {
                log.warn("[MCP] server startup failed: {}", e.getMessage());
            }
        }
        log.info("[MCP] total {} tools from {} servers", allTools.size(), clients.size());
        return allTools;
    }

    private List<Tool> startServer(McpServerConfig sc) {
        try {
            McpTransport transport;
            switch (sc.transport()) {
                case STDIO -> transport = new StdioTransport(
                    sc.command(), sc.args(), sc.env(), workDir);
                case HTTP -> transport = new HttpSseTransport(sc.url());
                default -> throw new IllegalArgumentException("Unknown transport: " + sc.transport());
            }

            transport.start();
            McpClient client = new McpClient(transport, sc.name());
            client.initialize();

            List<McpToolDef> defs = client.listTools();
            List<Tool> adapters = new ArrayList<>();
            for (McpToolDef def : defs) {
                String schema = McpSchemaSanitizer.sanitize(def.inputSchema());
                adapters.add(new McpToolAdapter(sc.name(), def, client, schema));
            }

            clients.put(sc.name(), new McpClientState(client, transport, adapters));
            log.info("[MCP] {}: {} tools (via {})", sc.name(), defs.size(), sc.transport());
            return adapters;
        } catch (Exception e) {
            log.warn("[MCP] {}: start failed — {}", sc.name(), e.getMessage());
            return List.of();
        }
    }

    /** 优雅关闭所有 server */
    public void shutdown() {
        for (var entry : clients.entrySet()) {
            try {
                log.info("[MCP] shutting down {}...", entry.getKey());
                entry.getValue().transport().stop();
            } catch (Exception e) {
                log.warn("[MCP] {} shutdown error: {}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        startupExecutor.shutdownNow();
    }

    /** 重启指定 server */
    public List<Tool> restart(String serverName) {
        McpClientState state = clients.remove(serverName);
        if (state != null) {
            state.transport().stop();
        }
        if (config == null || workDir == null) return List.of();
        McpServerConfig sc = config.servers().get(serverName);
        if (sc == null) {
            log.warn("[MCP] unknown server: {}", serverName);
            return List.of();
        }
        return startServer(sc);
    }

    /** 禁用指定 server */
    public void disable(String serverName) {
        McpClientState state = clients.remove(serverName);
        if (state != null) {
            state.transport().stop();
            log.info("[MCP] {}: disabled, {} tools removed", serverName, state.adapters().size());
        }
    }

    /** 查看 server 状态 */
    public List<McpStatus> status() {
        if (config == null) return List.of();
        return config.servers().values().stream()
            .map(sc -> {
                McpClientState st = clients.get(sc.name());
                return new McpStatus(
                    sc.name(),
                    sc.transport().name().toLowerCase(),
                    st != null && st.transport().isAlive() ? "RUNNING" : "STOPPED",
                    st != null ? st.adapters().size() : 0
                );
            })
            .toList();
    }

    /** 获取指定 server 的 stderr 日志 */
    public List<String> logs(String serverName) {
        McpClientState state = clients.get(serverName);
        if (state == null) return List.of("Server not found: " + serverName);
        if (state.transport() instanceof StdioTransport stdio) {
            List<String> log = stdio.getStderrLog();
            if (log.isEmpty()) return List.of("(no stderr output)");
            int start = Math.max(0, log.size() - 50);
            return log.subList(start, log.size());
        }
        return List.of("stderr logs only available for stdio servers");
    }

    public Map<String, McpClientState> clientStates() { return Map.copyOf(clients); }

    /** MCP 客户端状态 */
    public record McpClientState(McpClient client, McpTransport transport, List<Tool> adapters) {}

    /** MCP 状态摘要（供 CLI 显示） */
    public record McpStatus(String name, String transport, String state, int toolCount) {}
}
