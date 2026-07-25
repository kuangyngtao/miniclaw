package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** Executes only the three fixed, bounded PostgreSQL observability queries below. */
public final class JdbcPostgresDiagnosticBackend implements PostgresDiagnosticBackend {
    static final String ACTIVITY_SQL = """
        SELECT pid, usename, application_name, client_addr::text AS client_addr,
               state, wait_event_type, wait_event,
               EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000 AS query_age_ms,
               backend_type
          FROM pg_stat_activity
         WHERE datname = current_database() AND pid <> pg_backend_pid()
         ORDER BY query_start NULLS LAST, pid
         LIMIT 100
        """;
    static final String LOCK_GRAPH_SQL = """
        SELECT blocked.pid AS blocked_pid,
               blocker_pid,
               blocked.usename AS blocked_user,
               blocked.application_name AS blocked_application,
               blocked.wait_event_type,
               blocked.wait_event,
               EXTRACT(EPOCH FROM (clock_timestamp() - blocked.query_start)) * 1000 AS wait_age_ms
          FROM pg_stat_activity blocked
          CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS blocker_pid
         WHERE blocked.datname = current_database()
         ORDER BY wait_age_ms DESC NULLS LAST, blocked.pid, blocker_pid
         LIMIT 100
        """;
    static final String CONNECTION_SQL = """
        SELECT current_database() AS database,
               count(*) FILTER (WHERE backend_type = 'client backend') AS client_connections,
               count(*) FILTER (WHERE backend_type = 'client backend' AND state = 'active') AS active_connections,
               count(*) FILTER (WHERE backend_type = 'client backend' AND state = 'idle') AS idle_connections,
               current_setting('max_connections')::integer AS max_connections,
               (SELECT count(*) FROM pg_roles WHERE rolcanlogin) AS login_roles
          FROM pg_stat_activity
         WHERE datname = current_database()
         GROUP BY current_database()
        """;

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConnectionProvider connections;
    private final Clock clock;
    private final int timeoutSeconds;
    private final int maxRows;
    private final int maxOutputBytes;

    public JdbcPostgresDiagnosticBackend(
        ConnectionProvider connections, Clock clock, int timeoutSeconds,
        int maxRows, int maxOutputBytes
    ) {
        this.connections = connections;
        this.clock = clock;
        if (timeoutSeconds < 1 || maxRows < 1 || maxOutputBytes < 256) {
            throw new IllegalArgumentException("invalid JDBC diagnostic limits");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.maxRows = maxRows;
        this.maxOutputBytes = maxOutputBytes;
    }

    public static JdbcPostgresDiagnosticBackend fromEnvironment(Map<String, String> env) {
        String url = required(env, "CLAWKIT_OPS_DB_URL");
        String user = required(env, "CLAWKIT_OPS_DB_USER");
        String password = required(env, "CLAWKIT_OPS_DB_PASSWORD");
        return new JdbcPostgresDiagnosticBackend(
            () -> DriverManager.getConnection(url, user, password), Clock.systemUTC(),
            3, 100, 32_768);
    }

    @Override public OpsToolResult dbActivity() {
        return query("db_activity", ACTIVITY_SQL);
    }

    @Override public OpsToolResult dbLockGraph() {
        return query("db_lock_graph", LOCK_GRAPH_SQL);
    }

    @Override public OpsToolResult dbConnectionStats() {
        return query("db_connection_stats", CONNECTION_SQL);
    }

    private OpsToolResult query(String tool, String sql) {
        Instant observedAt = clock.instant();
        long started = System.nanoTime();
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            ArrayNode rows = MAPPER.createArrayNode();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows(maxRows);
                try (ResultSet result = statement.executeQuery()) {
                    ResultSetMetaData metadata = result.getMetaData();
                    while (result.next() && rows.size() < maxRows) {
                        ObjectNode row = rows.addObject();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            put(row, metadata.getColumnLabel(column), result, column,
                                metadata.getColumnType(column));
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
            ObjectNode data = MAPPER.createObjectNode();
            data.put("database", "current_database");
            data.put("rowCount", rows.size());
            data.set("rows", rows);
            int bytes = MAPPER.writeValueAsBytes(data).length;
            if (bytes > maxOutputBytes) {
                data.remove("rows");
                data.putArray("rows");
                data.put("truncated", true);
            }
            int returned = MAPPER.writeValueAsBytes(data).length;
            return result(tool, observedAt, true, data, null, null, started,
                bytes, returned, bytes > maxOutputBytes);
        } catch (Exception error) {
            return result(tool, observedAt, false, MAPPER.createObjectNode(),
                "DB_QUERY_FAILED", safeError(error), started, 0, 0, false);
        }
    }

    private OpsToolResult result(
        String tool, Instant observedAt, boolean success, ObjectNode data,
        String errorCode, String error, long started, int totalBytes,
        int returnedBytes, boolean truncated
    ) {
        return new OpsToolResult(tool, "postgres/current_database", observedAt,
            clock.instant(), true, success, data, errorCode, error,
            new OpsToolResult.Audit("postgres-jdbc", elapsed(started),
                timeoutSeconds * 1000, totalBytes, returnedBytes, truncated));
    }

    private static void put(
        ObjectNode row, String label, ResultSet result, int column, int jdbcType
    ) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) {
            row.putNull(label);
        } else if (jdbcType == Types.INTEGER || jdbcType == Types.SMALLINT
            || jdbcType == Types.BIGINT) {
            row.put(label, ((Number) value).longValue());
        } else if (value instanceof Number number) {
            row.put(label, number.doubleValue());
        } else if (value instanceof Boolean bool) {
            row.put(label, bool);
        } else {
            row.put(label, value.toString());
        }
    }

    private static String safeError(Exception error) {
        if (error instanceof SQLException sql) {
            String state = sql.getSQLState();
            return "PostgreSQL diagnostic query failed" +
                (state == null ? "" : " (SQLSTATE " + state.toUpperCase(Locale.ROOT) + ")");
        }
        return "PostgreSQL diagnostic query failed";
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing environment variable: " + name);
        }
        return value;
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
