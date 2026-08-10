package com.leantpm.ops;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** Minimal release-time Flyway entry point that deliberately does not start Spring. */
public final class MigrationMain {

    private MigrationMain() {
    }

    public static void main(String[] args) {
        MigrationSettings settings = MigrationSettings.from(System.getenv());
        Flyway flyway = Flyway.configure()
                .dataSource(settings.jdbcUrl(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();

        String actualServerUuid = serverUuid(flyway);
        if (!actualServerUuid.equalsIgnoreCase(settings.expectedServerUuid())) {
            throw new IllegalStateException("MySQL server UUID does not match the approved target");
        }
        int current = currentVersion(flyway.info().current());
        if (current == settings.schemaTo()) {
            flyway.validate();
            System.out.printf(
                    "{\"status\":\"PASS\",\"schemaFrom\":%d,\"schemaTo\":%d,"
                            + "\"alreadyCurrent\":true,\"serverUuid\":\"%s\"}%n",
                    current, settings.schemaTo(), actualServerUuid);
            return;
        }
        if (current != settings.schemaFrom()) {
            throw new IllegalStateException("Database schemaFrom does not match the approved plan");
        }
        if (settings.schemaFrom() == 0) {
            assertEmptyDatabaseForFirstInstall(flyway);
        }
        flyway.validate();
        flyway.migrate();
        int migrated = currentVersion(flyway.info().current());
        if (migrated != settings.schemaTo()) {
            throw new IllegalStateException("Database did not reach the approved schemaTo");
        }
        System.out.printf(
                "{\"status\":\"PASS\",\"schemaFrom\":%d,\"schemaTo\":%d,"
                        + "\"serverUuid\":\"%s\"}%n",
                settings.schemaFrom(), settings.schemaTo(), actualServerUuid);
    }

    private static String serverUuid(Flyway flyway) {
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT @@server_uuid")) {
            if (!result.next() || result.getString(1) == null || result.getString(1).isBlank()) {
                throw new IllegalStateException("MySQL did not report a server UUID");
            }
            return result.getString(1).trim();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to verify the MySQL server UUID", exception);
        }
    }

    private static void assertEmptyDatabaseForFirstInstall(Flyway flyway) {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = DATABASE())
                  + (SELECT COUNT(*) FROM information_schema.routines
                        WHERE routine_schema = DATABASE())
                  + (SELECT COUNT(*) FROM information_schema.events
                        WHERE event_schema = DATABASE())
                """;
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next() || result.getLong(1) != 0L) {
                throw new IllegalStateException(
                        "First install requires an empty target database schema");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to prove that the first-install database schema is empty", exception);
        }
    }

    private static int currentVersion(MigrationInfo current) {
        if (current == null || current.getVersion() == null) {
            return 0;
        }
        return Integer.parseInt(current.getVersion().getVersion());
    }

    record MigrationSettings(
            String jdbcUrl,
            String username,
            String password,
            int schemaFrom,
            int schemaTo,
            String expectedServerUuid) {

        private static final Pattern JDBC_URL = Pattern.compile(
                "^jdbc:mysql://[A-Za-z0-9.-]+:[0-9]{1,5}/[A-Za-z0-9_]+(?:\\?[A-Za-z0-9=&._-]+)?$");
        private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
        private static final Pattern SERVER_UUID = Pattern.compile("^[A-Fa-f0-9-]{16,64}$");

        static MigrationSettings from(Map<String, String> environment) {
            String jdbcUrl = required(environment, "LEANTPM_MIGRATOR_JDBC_URL");
            String username = required(environment, "LEANTPM_MIGRATOR_DB_USERNAME");
            String password = required(environment, "LEANTPM_MIGRATOR_DB_PASSWORD");
            int schemaFrom = positiveOrZero(environment, "LEANTPM_MIGRATOR_SCHEMA_FROM");
            int schemaTo = positiveOrZero(environment, "LEANTPM_MIGRATOR_SCHEMA_TO");
            String expectedServerUuid = required(
                    environment,
                    "LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID");
            String normalizedUrl = jdbcUrl.toLowerCase();
            if (!JDBC_URL.matcher(jdbcUrl).matches()
                    || normalizedUrl.contains("allowmultiqueries=true")
                    || normalizedUrl.contains("autodeserialize=true")
                    || !normalizedUrl.contains("sslmode=verify_identity")
                    || !USERNAME.matcher(username).matches()
                    || !SERVER_UUID.matcher(expectedServerUuid).matches()
                    || schemaTo < schemaFrom) {
                throw new IllegalArgumentException("Invalid migrator contract");
            }
            return new MigrationSettings(
                    jdbcUrl,
                    username,
                    password,
                    schemaFrom,
                    schemaTo,
                    expectedServerUuid);
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required migrator setting: " + name);
            }
            return value;
        }

        private static int positiveOrZero(Map<String, String> environment, String name) {
            try {
                int value = Integer.parseInt(required(environment, name));
                if (value < 0) {
                    throw new IllegalArgumentException("Invalid migrator schema version");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid migrator schema version", exception);
            }
        }
    }
}
