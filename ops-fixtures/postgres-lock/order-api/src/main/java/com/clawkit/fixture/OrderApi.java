package com.clawkit.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class OrderApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Deque<Sample> SAMPLES = new ArrayDeque<>();
    private static final AtomicLong ARTIFICIAL_DELAY_MS = new AtomicLong();
    private static final AtomicLong CPU_BURN_MS = new AtomicLong();
    private static final AtomicLong LOCK_GENERATION = new AtomicLong();
    private static final AtomicInteger HELD_CONNECTIONS = new AtomicInteger();
    private static final String DEFAULT_ACCOUNT = "hot-0001";
    private static final AtomicLong RECONCILE_HOLD_MS = new AtomicLong();
    private static final AtomicLong RECONCILE_INTERVAL_MS = new AtomicLong();
    private static HikariDataSource pool;
    private static volatile boolean ready;
    private static volatile boolean reconcileRunning;

    private OrderApi() {}

    public static void main(String[] args) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(env("DB_URL"));
        config.setUsername(env("DB_USER"));
        config.setPassword(env("DB_PASSWORD"));
        config.setMaximumPoolSize(Integer.parseInt(System.getenv().getOrDefault("DB_POOL_SIZE", "6")));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(800);
        config.setPoolName("order-api");
        pool = new HikariDataSource(config);
        try (Connection ignored = pool.getConnection()) { ready = true; }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/live", exchange -> json(exchange, 200, Map.of("status", "UP")));
        server.createContext("/ready", exchange -> json(exchange, ready ? 200 : 503,
            Map.of("status", ready ? "UP" : "DOWN")));
        server.createContext("/orders", OrderApi::orders);
        server.createContext("/internal/metrics", OrderApi::metrics);
        server.createContext("/internal/control", OrderApi::control);
        server.createContext("/internal/verify", OrderApi::verifyInvariant);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static void orders(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        boolean success = false;
        boolean duplicate = false;
        try {
            if ("POST".equals(exchange.getRequestMethod())) {
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                String requestId = required(body, "requestId");
                String accountId = body.path("accountId").asText(DEFAULT_ACCOUNT);
                long amountCents = body.path("amountCents").asLong(-1);
                if (amountCents < 1 || amountCents > 1_000_000) throw new IllegalArgumentException("invalid amountCents");
                delay();
                try (Connection connection = pool.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement lock = connection.prepareStatement(
                            "SELECT balance_cents FROM accounts WHERE account_id = ? FOR UPDATE")) {
                        lock.setString(1, accountId);
                        try (ResultSet rows = lock.executeQuery()) { if (!rows.next()) throw new IllegalStateException("account missing: " + accountId); }
                    }
                    int inserted;
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO orders(request_id, account_id, amount_cents) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                        insert.setObject(1, UUID.fromString(requestId));
                        insert.setString(2, accountId);
                        insert.setLong(3, amountCents);
                        inserted = insert.executeUpdate();
                    }
                    duplicate = inserted == 0;
                    if (!duplicate) {
                        try (PreparedStatement deduct = connection.prepareStatement(
                                "UPDATE accounts SET balance_cents = balance_cents - ? WHERE account_id = ? AND balance_cents >= ?")) {
                            deduct.setLong(1, amountCents);
                            deduct.setString(2, accountId);
                            deduct.setLong(3, amountCents);
                            if (deduct.executeUpdate() == 0) {
                                connection.rollback();
                                json(exchange, 402, Map.of("error", "insufficient balance"));
                                return;
                            }
                        }
                    }
                    connection.commit();
                }
                success = true;
                json(exchange, duplicate ? 200 : 201,
                    Map.of("requestId", requestId, "amountCents", amountCents, "duplicate", duplicate));
            } else if ("GET".equals(exchange.getRequestMethod())) {
                String[] parts = exchange.getRequestURI().getPath().split("/");
                if (parts.length != 3) throw new IllegalArgumentException("requestId required");
                try (Connection connection = pool.getConnection(); PreparedStatement query = connection.prepareStatement(
                        "SELECT request_id::text, amount_cents, created_at FROM orders WHERE request_id = ?")) {
                    query.setObject(1, UUID.fromString(parts[2]));
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) { json(exchange, 404, Map.of("error", "not found")); return; }
                        success = true;
                        json(exchange, 200, Map.of("requestId", rows.getString(1),
                            "amountCents", rows.getLong(2), "createdAt", rows.getTimestamp(3).toInstant().toString()));
                    }
                }
            } else {
                json(exchange, 405, Map.of("error", "method not allowed"));
            }
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            json(exchange, 503, Map.of("error", "order unavailable"));
        } finally {
            record(started, success, duplicate);
        }
    }

    private static void metrics(HttpExchange exchange) throws IOException {
        int windowSeconds = boundedWindowSeconds(
            query(exchange.getRequestURI().getRawQuery()).get("windowSeconds"));
        long now = System.currentTimeMillis();
        long retentionCutoff = now - 60_000L;
        long windowCutoff = now - windowSeconds * 1_000L;
        Sample[] samples;
        synchronized (SAMPLES) {
            while (!SAMPLES.isEmpty() && SAMPLES.peekFirst().at < retentionCutoff) SAMPLES.removeFirst();
            samples = SAMPLES.stream().filter(sample -> sample.at >= windowCutoff).toArray(Sample[]::new);
        }
        long[] latency = java.util.Arrays.stream(samples).mapToLong(Sample::latencyMs).sorted().toArray();
        long p95 = latency.length == 0 ? 0 : latency[Math.min(latency.length - 1, (int) Math.ceil(latency.length * .95) - 1)];
        long succeeded = java.util.Arrays.stream(samples).filter(Sample::success).count();
        long duplicates = java.util.Arrays.stream(samples).filter(Sample::duplicate).count();
        ObjectNode result = JSON.createObjectNode();
        result.put("windowSeconds", windowSeconds).put("requestCount", samples.length)
            .put("successCount", succeeded).put("errorCount", samples.length - succeeded)
            .put("duplicateCount", duplicates).put("p95LatencyMs", p95);
        result.putObject("pool").put("active", pool.getHikariPoolMXBean().getActiveConnections())
            .put("idle", pool.getHikariPoolMXBean().getIdleConnections())
            .put("pending", pool.getHikariPoolMXBean().getThreadsAwaitingConnection())
            .put("max", pool.getMaximumPoolSize());
        json(exchange, 200, result);
    }

    private static int boundedWindowSeconds(String raw) {
        if (raw == null || raw.isBlank()) return 60;
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 60) throw new IllegalArgumentException("windowSeconds must be 1..60");
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("windowSeconds must be an integer", error);
        }
    }

    private static void control(HttpExchange exchange) throws IOException {
        if (!env("CONTROL_TOKEN").equals(exchange.getRequestHeaders().getFirst("X-Control-Token"))) {
            json(exchange, 404, Map.of("error", "not found")); return;
        }
        String mode = query(exchange.getRequestURI().getRawQuery()).getOrDefault("mode", "normal");
        cleanup();
        switch (mode) {
            case "normal" -> { }
            case "lock" -> holdDatabaseLock(120_000);
            case "self-recovered" -> holdDatabaseLock(12_000);
            case "cpu" -> CPU_BURN_MS.set(700);
            case "connections" -> exhaustPool();
            case "stale-log" -> {
                ARTIFICIAL_DELAY_MS.set(700);
                System.out.println("historical diagnostic marker: database wait observed and cleared");
            }
            case "unknown" -> ARTIFICIAL_DELAY_MS.set(700);
            case "hot-contention" -> {
                long intervalMs = Long.parseLong(
                    query(exchange.getRequestURI().getRawQuery()).getOrDefault("intervalMs", "3000"));
                long holdMs = Long.parseLong(
                    query(exchange.getRequestURI().getRawQuery()).getOrDefault("holdMs", "2000"));
                startReconciliation(intervalMs, holdMs);
            }
            default -> { json(exchange, 400, Map.of("error", "unknown mode")); return; }
        }
        json(exchange, 200, Map.of("accepted", true));
    }

    private static void holdDatabaseLock(long millis) {
        long generation = LOCK_GENERATION.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try (Connection connection = pool.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE accounts SET balance_cents = balance_cents WHERE account_id = ?")) {
                    statement.setString(1, DEFAULT_ACCOUNT); statement.executeUpdate();
                }
                System.out.println("balance reconciliation transaction acquired account row lock");
                long deadline = System.nanoTime() + millis * 1_000_000;
                while (LOCK_GENERATION.get() == generation && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                connection.rollback();
                System.out.println("balance reconciliation transaction released account row lock");
            } catch (Exception ignored) { }
        });
        try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void exhaustPool() {
        int target = pool.getMaximumPoolSize();
        CountDownLatch acquired = new CountDownLatch(target);
        for (int i = 0; i < target; i++) Thread.startVirtualThread(() -> {
            try (Connection ignored = pool.getConnection()) {
                HELD_CONNECTIONS.incrementAndGet(); acquired.countDown();
                while (HELD_CONNECTIONS.get() > 0) Thread.sleep(50);
            } catch (Exception ignored) { acquired.countDown(); }
        });
        try { acquired.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void startReconciliation(long intervalMs, long holdMs) {
        RECONCILE_INTERVAL_MS.set(intervalMs);
        RECONCILE_HOLD_MS.set(holdMs);
        if (reconcileRunning) return;
        reconcileRunning = true;
        Thread.startVirtualThread(() -> {
            System.out.println("reconciliation scheduler started: interval=" + intervalMs
                + "ms hold=" + holdMs + "ms account=" + DEFAULT_ACCOUNT);
            while (reconcileRunning) {
                long generation = LOCK_GENERATION.incrementAndGet();
                try (Connection connection = pool.getConnection()) {
                    connection.setAutoCommit(false);
                    connection.setTransactionIsolation(
                        java.sql.Connection.TRANSACTION_READ_COMMITTED);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT balance_cents FROM accounts WHERE account_id = ? FOR UPDATE")) {
                        statement.setString(1, DEFAULT_ACCOUNT);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) throw new IllegalStateException("hot account missing");
                        }
                    }
                    // Opening balance conservation check (deterministic read inside lock)
                    try (PreparedStatement sumOrders = connection.prepareStatement(
                            "SELECT COALESCE(SUM(amount_cents), 0) FROM orders WHERE account_id = ?")) {
                        sumOrders.setString(1, DEFAULT_ACCOUNT);
                        try (ResultSet rs = sumOrders.executeQuery()) {
                            rs.next();
                            // Log for observability — not part of Evidence
                        }
                    }
                    // Hold lock for simulation
                    long deadline = System.nanoTime() + holdMs * 1_000_000;
                    while (reconcileRunning && LOCK_GENERATION.get() == generation
                        && System.nanoTime() < deadline) {
                        Thread.sleep(50);
                    }
                    connection.rollback();
                } catch (Exception e) {
                    // Connection timeout or pool exhaustion — expected symptom
                    System.out.println("reconciliation cycle failed: " + e.getMessage());
                }
                try { Thread.sleep(intervalMs - holdMs); } catch (InterruptedException e) { break; }
            }
            System.out.println("reconciliation scheduler stopped");
        });
    }

    private static void stopReconciliation() {
        reconcileRunning = false;
        RECONCILE_INTERVAL_MS.set(0);
        RECONCILE_HOLD_MS.set(0);
    }

    /**
     * Verify the conservation invariant for all accounts.
     * opening_balance_cents = balance_cents + SUM(orders.amount_cents)
     */
    private static void verifyInvariant(HttpExchange exchange) throws IOException {
        if (!env("CONTROL_TOKEN").equals(exchange.getRequestHeaders().getFirst("X-Control-Token"))) {
            json(exchange, 404, Map.of("error", "not found")); return;
        }
        try (Connection connection = pool.getConnection();
             PreparedStatement accounts = connection.prepareStatement(
                 "SELECT account_id, account_class, opening_balance_cents, balance_cents FROM accounts ORDER BY account_id");
             PreparedStatement orderSum = connection.prepareStatement(
                 "SELECT COALESCE(SUM(amount_cents), 0) FROM orders WHERE account_id = ?")) {

            var results = JSON.createArrayNode();
            try (ResultSet rows = accounts.executeQuery()) {
                while (rows.next()) {
                    String accountId = rows.getString(1);
                    String accountClass = rows.getString(2);
                    long opening = rows.getLong(3);
                    long balance = rows.getLong(4);

                    orderSum.setString(1, accountId);
                    long totalOrders;
                    try (ResultSet sumRs = orderSum.executeQuery()) {
                        sumRs.next();
                        totalOrders = sumRs.getLong(1);
                    }

                    long expected = opening - totalOrders;
                    boolean passed = expected == balance;

                    var entry = JSON.createObjectNode();
                    entry.put("accountId", accountId);
                    entry.put("accountClass", accountClass);
                    entry.put("openingBalanceCents", opening);
                    entry.put("balanceCents", balance);
                    entry.put("totalOrderCents", totalOrders);
                    entry.put("expectedBalance", expected);
                    entry.put("passed", passed);
                    entry.put("drift", expected - balance);
                    results.add(entry);
                }
            }
            json(exchange, 200, results);
        } catch (Exception e) {
            json(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private static void cleanup() {
        LOCK_GENERATION.incrementAndGet();
        stopReconciliation();
        ARTIFICIAL_DELAY_MS.set(0); CPU_BURN_MS.set(0); HELD_CONNECTIONS.set(0);
        synchronized (SAMPLES) { SAMPLES.clear(); }
    }

    private static void delay() throws InterruptedException {
        long sleep = ARTIFICIAL_DELAY_MS.get(); if (sleep > 0) Thread.sleep(sleep);
        long burn = CPU_BURN_MS.get(); if (burn > 0) {
            long end = System.nanoTime() + burn * 1_000_000;
            double value = 0; while (System.nanoTime() < end) value += Math.sqrt(value + 17);
            if (value == -1) System.out.println(value);
        }
    }

    private static void record(long started, boolean success, boolean duplicate) {
        Sample sample = new Sample(System.currentTimeMillis(),
            (System.nanoTime() - started) / 1_000_000, success, duplicate);
        synchronized (SAMPLES) { SAMPLES.addLast(sample); }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }

    private static Map<String, String> query(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        return java.util.Arrays.stream(raw.split("&")).map(p -> p.split("=", 2))
            .filter(p -> p.length == 2).collect(java.util.stream.Collectors.toMap(p -> p[0], p -> p[1]));
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing " + name);
        return value;
    }

    private record Sample(long at, long latencyMs, boolean success, boolean duplicate) { }
}
