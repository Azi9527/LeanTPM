package com.leantpm.opscontrol.operations;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcDatabaseProbe implements OperationsProbe {

    static final String HEALTH_QUERY = "SELECT @@server_uuid, "
        + "COALESCE((SELECT MAX(CAST(version AS UNSIGNED)) "
        + "FROM flyway_schema_history WHERE success = 1), 0)";

    private final DatabaseConnectionFactory connections;
    private final int timeoutSeconds;

    public JdbcDatabaseProbe(DatabaseConnectionFactory connections, int timeoutSeconds) {
        this.connections = Objects.requireNonNull(connections, "connections");
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("database timeout must be between 1 and 30 seconds");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String id() {
        return "database";
    }

    @Override
    public List<OperationsComponent> observe(Instant observedAt) {
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                try (ResultSet result = statement.executeQuery(HEALTH_QUERY)) {
                    if (!result.next()) {
                        throw new SQLException("database health query returned no row");
                    }
                    String serverUuid = result.getString(1);
                    int schemaVersion = result.getInt(2);
                    return List.of(new OperationsComponent(
                        "database:mysql", "MySQL 数据库", OperationsComponentKind.DATABASE,
                        schemaVersion >= 50 ? OperationsHealth.HEALTHY : OperationsHealth.DEGRADED,
                        schemaVersion >= 50
                            ? "只读连接正常，数据库版本为 V" + schemaVersion
                            : "只读连接正常，但数据库版本低于 V50",
                        observedAt,
                        Map.of(
                            "schemaVersion", Integer.toString(schemaVersion),
                            "serverUuidSuffix", safeUuidSuffix(serverUuid)
                        ),
                        null
                    ));
                }
            }
        } catch (SQLException exception) {
            return List.of(new OperationsComponent(
                "database:mysql", "MySQL 数据库", OperationsComponentKind.DATABASE,
                OperationsHealth.DOWN,
                "固定只读数据库健康检查失败",
                observedAt,
                Map.of(),
                null
            ));
        }
    }

    private static String safeUuidSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.substring(Math.max(0, normalized.length() - 8));
    }
}
