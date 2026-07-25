package com.clawkit.ops.mcp;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpsCapabilityProfileTest {
    @Test
    void legacyProfileRemainsTheDefaultAndExactBoundary() {
        assertThat(OpsCapabilityProfile.fromEnvironment(null)).isEqualTo(OpsCapabilityProfile.APP_DOWN_V1);
        assertThat(OpsCapabilityProfile.APP_DOWN_V1.toolNames()).isEqualTo(OpsMcpServer.TOOL_NAMES);
        assertThat(OpsCapabilityProfile.POSTGRES_DIAGNOSIS_V1.toolNames())
            .containsAll(OpsMcpServer.TOOL_NAMES).hasSize(10);
    }

    @Test
    void jdbcBackendSetsReadOnlyBeforeAnyFixedQueryAndRedactsDriverDetails() {
        AtomicBoolean readOnly = new AtomicBoolean();
        AtomicBoolean autoCommitDisabled = new AtomicBoolean();
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "setReadOnly" -> { readOnly.set(Boolean.TRUE.equals(args[0])); yield null; }
                case "setAutoCommit" -> { autoCommitDisabled.set(Boolean.FALSE.equals(args[0])); yield null; }
                case "prepareStatement" -> throw new SQLException(
                    "password=secret jdbc:postgresql://private-host/db", "57014");
                case "close", "rollback" -> null;
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        var backend = new JdbcPostgresDiagnosticBackend(() -> connection,
            Clock.systemUTC(), 1, 10, 1024);

        OpsToolResult result = backend.dbActivity();

        assertThat(readOnly).isTrue();
        assertThat(autoCommitDisabled).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("SQLSTATE 57014")
            .doesNotContain("secret", "private-host", "jdbc:");
    }

    @Test
    void diagnosticSqlIsFixedBoundedAndNeverReturnsSqlText() {
        assertThat(JdbcPostgresDiagnosticBackend.ACTIVITY_SQL).contains("LIMIT 100")
            .doesNotContain("SELECT query", "query AS", "query,");
        assertThat(JdbcPostgresDiagnosticBackend.LOCK_GRAPH_SQL)
            .contains("pg_blocking_pids", "LIMIT 100");
        assertThat(JdbcPostgresDiagnosticBackend.class.getDeclaredMethods())
            .filteredOn(method -> method.getName().startsWith("db"))
            .allMatch(method -> method.getParameterCount() == 0);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
