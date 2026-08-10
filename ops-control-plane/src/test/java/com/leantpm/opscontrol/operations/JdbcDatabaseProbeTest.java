package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcDatabaseProbeTest {

    @Test
    void usesFixedReadOnlyQueryAndReturnsNonSecretMetrics() {
        StringBuilder sql = new StringBuilder();
        ResultSet resultSet = proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
            case "next" -> true;
            case "getString" -> "007df095-92ef-11f1-8f53-00163e059faa";
            case "getInt" -> 50;
            default -> defaultValue(method.getReturnType());
        });
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("executeQuery")) {
                sql.append(args[0]);
                return resultSet;
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
            case "createStatement" -> statement;
            case "isReadOnly" -> true;
            default -> defaultValue(method.getReturnType());
        });
        JdbcDatabaseProbe probe = new JdbcDatabaseProbe(() -> connection, 5);

        OperationsComponent result = probe.observe(Instant.parse("2026-08-10T02:00:00Z"))
            .getFirst();

        assertThat(sql.toString()).isEqualTo(JdbcDatabaseProbe.HEALTH_QUERY);
        assertThat(result.status()).isEqualTo(OperationsHealth.HEALTHY);
        assertThat(result.metrics()).containsEntry("schemaVersion", "50");
        assertThat(result.summary()).doesNotContain("password").doesNotContain("jdbc:");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
