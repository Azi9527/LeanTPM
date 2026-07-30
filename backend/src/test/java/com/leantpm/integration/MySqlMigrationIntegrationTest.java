package com.leantpm.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlMigrationIntegrationTest {
    private String url;
    private String username;
    private String password;

    @BeforeAll
    void migrateFreshDatabase() {
        url = System.getenv("LEANTPM_TEST_DB_URL");
        username = environment("LEANTPM_TEST_DB_USERNAME", "root");
        password = environment("LEANTPM_TEST_DB_PASSWORD", "");
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @Test
    void appliesEveryMigrationAndFoundationTable() throws Exception {
        assertThat(number("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"))
                .isEqualTo(8);
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'organization',
                    'system_data_scope',
                    'system_change_log',
                    'system_attachment_relation',
                    'location',
                    'equipment_category',
                    'equipment_attribute_definition',
                    'equipment',
                    'equipment_attribute_value',
                    'equipment_responsible_person',
                    'equipment_transfer_record',
                    'equipment_barcode',
                    'equipment_current_status',
                    'equipment_status_history'
                  )
                """)).isEqualTo(14);
    }

    @Test
    void supportsRecursiveOrganizationScope() throws Exception {
        assertThat(number("""
                WITH RECURSIVE organization_tree AS (
                    SELECT id FROM organization WHERE id = 3
                    UNION ALL
                    SELECT child.id
                    FROM organization child
                    JOIN organization_tree parent ON child.parent_id = parent.id
                )
                SELECT COUNT(*) FROM organization_tree
                """)).isEqualTo(3);
    }

    @Test
    void storesValidatedJsonChangeSnapshots() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO system_change_log
                        (tenant_id, resource_type, resource_id, operation_type,
                         before_data, after_data, changed_fields, operator_id, operator_name)
                    VALUES
                        (1, 'INTEGRATION_TEST', '1', 'UPDATE',
                         JSON_OBJECT('status', 'OLD'),
                         JSON_OBJECT('status', 'NEW'),
                         JSON_ARRAY('status'), 1, 'integration')
                    """);
        }
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_change_log
                WHERE resource_type = 'INTEGRATION_TEST'
                  AND JSON_UNQUOTE(JSON_EXTRACT(after_data, '$.status')) = 'NEW'
                """)).isEqualTo(1);
    }

    @Test
    void separatesOrganizationLocationAndEquipmentStatusModels() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM location l
                JOIN organization o
                  ON o.tenant_id = l.tenant_id
                 AND o.id = l.organization_id
                WHERE l.tenant_id = 1
                  AND l.deleted = 0
                  AND o.deleted = 0
                """)).isEqualTo(5);
        assertThat(number("""
                SELECT COUNT(*)
                FROM equipment_category
                WHERE tenant_id = 1 AND deleted = 0
                """)).isEqualTo(3);
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'equipment_barcode'
                  AND index_name = 'uk_equipment_active_barcode'
                  AND non_unique = 0
                """)).isEqualTo(3);
    }

    private long number(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(url, username, password);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }
}
