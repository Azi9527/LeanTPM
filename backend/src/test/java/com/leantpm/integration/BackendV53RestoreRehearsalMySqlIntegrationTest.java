package com.leantpm.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "LEANTPM_V53_REHEARSAL_PHASE",
        matches = "PREPARE_V52|UPGRADE_V53|VERIFY_RESTORED_V52")
class BackendV53RestoreRehearsalMySqlIntegrationTest {

    private final String url = required("LEANTPM_TEST_DB_URL");
    private final String username = required("LEANTPM_TEST_DB_USERNAME");
    private final String password = required("LEANTPM_TEST_DB_PASSWORD");
    private final String phase = required("LEANTPM_V53_REHEARSAL_PHASE");

    @Test
    void executesRequestedRehearsalPhase() throws Exception {
        switch (phase) {
            case "PREPARE_V52" -> prepareV52();
            case "UPGRADE_V53" -> upgradeV53();
            case "VERIFY_RESTORED_V52" -> verifyRestoredV52();
            default -> throw new IllegalArgumentException("Unsupported V53 rehearsal phase");
        }
    }

    private void prepareV52() throws Exception {
        var result = flyway(MigrationVersion.fromVersion("52")).migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("52");
        assertThat(currentVersion()).isEqualTo(52L);
        assertThat(measureColumnCount()).isZero();
        executeUpdate("""
                INSERT INTO inspection_abnormal
                    (tenant_id, abnormal_code, task_id, equipment_id,
                     abnormal_title, abnormal_description, severity,
                     final_result, created_by, updated_by)
                VALUES
                    (1, 'V53-HISTORY-RESTORE-FIXTURE', 5390001, 5390001,
                     'V53 restore fixture', 'legacy V52 result must survive', 'LOW',
                     'V52 legacy final result', 0, 0)
                """);
        assertLegacyFixture();
    }

    private void upgradeV53() throws Exception {
        assertThat(currentVersion()).isEqualTo(52L);
        assertThat(measureColumnCount()).isZero();
        assertLegacyFixture();

        var upgrade = flyway(null).migrate();
        assertThat(upgrade.migrationsExecuted).isEqualTo(1);
        assertThat(currentVersion()).isEqualTo(53L);
        assertThat(measureColumnCount()).isEqualTo(2L);
        assertLegacyFixture();
        assertThat(text("""
                SELECT permanent_countermeasure FROM inspection_abnormal
                WHERE tenant_id = 1
                  AND abnormal_code = 'V53-HISTORY-RESTORE-FIXTURE'
                """)).isNull();

        var rerun = flyway(null).migrate();
        assertThat(rerun.migrationsExecuted).isZero();
        assertThat(currentVersion()).isEqualTo(53L);
    }

    private void verifyRestoredV52() throws Exception {
        assertThat(currentVersion()).isEqualTo(52L);
        assertThat(measureColumnCount()).isZero();
        assertLegacyFixture();
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void assertLegacyFixture() throws Exception {
        assertThat(text("""
                SELECT final_result FROM inspection_abnormal
                WHERE tenant_id = 1
                  AND abnormal_code = 'V53-HISTORY-RESTORE-FIXTURE'
                """)).isEqualTo("V52 legacy final result");
    }

    private long currentVersion() throws Exception {
        return number("""
                SELECT COALESCE(MAX(CASE WHEN success = 1 THEN CAST(version AS UNSIGNED) END), 0)
                FROM flyway_schema_history
                """);
    }

    private long measureColumnCount() throws Exception {
        return number("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'inspection_abnormal'
                  AND column_name IN ('cause_analysis', 'permanent_countermeasure')
                """);
    }

    private void executeUpdate(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long number(String sql) throws Exception {
        String value = text(sql);
        return value == null ? 0L : Long.parseLong(value);
    }

    private String text(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required V53 rehearsal setting: " + name);
        }
        return value;
    }
}
