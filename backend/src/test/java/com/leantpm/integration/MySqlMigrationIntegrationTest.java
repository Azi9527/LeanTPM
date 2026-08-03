package com.leantpm.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

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
                .isEqualTo(24);
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
                    'equipment_status_history',
                    'inspection_item',
                    'inspection_scheme',
                    'inspection_scheme_version',
                    'inspection_scheme_item',
                    'inspection_scheme_category',
                    'inspection_scheme_equipment',
                    'inspection_plan',
                    'inspection_task',
                    'inspection_task_item',
                    'inspection_task_result',
                    'inspection_abnormal',
                    'inspection_attachment',
                    'inspection_task_event',
                    'maintenance_item',
                    'maintenance_scheme',
                    'maintenance_scheme_version',
                    'maintenance_scheme_item',
                    'maintenance_scheme_category',
                    'maintenance_scheme_equipment',
                    'maintenance_plan',
                    'maintenance_task',
                    'maintenance_task_item',
                    'maintenance_task_result',
                    'maintenance_task_collaborator',
                    'maintenance_task_pause',
                    'maintenance_material_usage',
                    'maintenance_abnormal',
                    'maintenance_attachment',
                    'maintenance_task_event',
                    'equipment_shift',
                    'equipment_calendar',
                    'equipment_oee_target',
                    'equipment_oee_record',
                    'equipment_output_record',
                    'equipment_downtime_record',
                    'equipment_loss_reason',
                    'equipment_oee_calculation_log',
                    'visualization_model_resource',
                    'visualization_scene',
                    'visualization_scene_node',
                    'visualization_status_color',
                    'system_user_import_batch',
                    'notification_rule',
                    'notification_message',
                    'notification_delivery',
                    'notification_escalation',
                    'equipment_fault_report',
                    'equipment_repair_order',
                    'equipment_repair_collaborator',
                    'equipment_repair_material',
                    'equipment_repair_event',
                    'equipment_fault_attachment',
                    'mobile_photo_evidence'
                  )
                """)).isEqualTo(67);
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
                """)).isEqualTo(7);
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
                """)).isEqualTo(8);
        assertThat(number("""
                SELECT COUNT(*)
                FROM equipment_category
                WHERE tenant_id = 1 AND deleted = 0
                """)).isEqualTo(4);
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'equipment_barcode'
                  AND index_name = 'uk_equipment_active_barcode'
                  AND non_unique = 0
                """)).isEqualTo(3);
    }

    @Test
    void preservesInspectionSchemeVersionsAndTaskItemSnapshots() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM inspection_scheme scheme
                JOIN inspection_scheme_version version
                  ON version.tenant_id = scheme.tenant_id
                 AND version.scheme_id = scheme.id
                JOIN inspection_scheme_item relation
                  ON relation.tenant_id = version.tenant_id
                 AND relation.scheme_version_id = version.id
                WHERE scheme.tenant_id = 1
                  AND scheme.current_version_id = version.id
                  AND version.version_status = 'PUBLISHED'
                """)).isEqualTo(3);
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'inspection_task'
                  AND index_name = 'uk_inspection_task_occurrence'
                  AND non_unique = 0
                """)).isEqualTo(3);
    }

    @Test
    void enforcesPreciseAndUniqueOeeModel() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'equipment_oee_record'
                  AND column_name IN (
                    'standard_cycle_seconds',
                    'planned_work_minutes',
                    'actual_quantity',
                    'availability_rate',
                    'performance_rate',
                    'quality_rate',
                    'oee_rate'
                  )
                  AND data_type = 'decimal'
                """)).isEqualTo(7);
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'equipment_oee_record'
                  AND index_name = 'uk_equipment_oee_record'
                  AND non_unique = 0
                """)).isEqualTo(5);
        assertThat(number("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'equipment_shift',
                    'equipment_calendar',
                    'equipment_loss_reason',
                    'equipment_output_record',
                    'equipment_oee_record'
                  )
                  AND column_name = 'active_marker'
                  AND extra LIKE '%STORED GENERATED%'
                """)).isEqualTo(5);
        assertThat(number("""
                SELECT COUNT(*)
                FROM equipment_loss_reason
                WHERE tenant_id = 1
                  AND loss_category IN (
                    'BREAKDOWN',
                    'SETUP_ADJUSTMENT',
                    'MINOR_STOPPAGE',
                    'REDUCED_SPEED',
                    'PROCESS_DEFECT',
                    'STARTUP_REJECT'
                  )
                """)).isEqualTo(6);
    }

    @Test
    void seedsRunnableVisualizationDemoAndStatusPalette() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM equipment
                WHERE tenant_id = 1
                  AND equipment_code LIKE 'VIZ-%'
                  AND deleted = 0
                """)).isEqualTo(8);
        assertThat(number("""
                SELECT COUNT(*)
                FROM visualization_scene
                WHERE tenant_id = 1 AND status = 1 AND deleted = 0
                """)).isEqualTo(7);
        assertThat(number("""
                SELECT COUNT(*)
                FROM visualization_scene_node
                WHERE tenant_id = 1 AND visible_flag = 1 AND deleted = 0
                """)).isEqualTo(14);
        assertThat(number("""
                SELECT COUNT(*)
                FROM visualization_status_color
                WHERE tenant_id = 1 AND status = 1 AND deleted = 0
                """)).isEqualTo(12);
    }

    @Test
    void seedsMobileParametersMenusAndRoleGrants() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_parameter
                WHERE tenant_id = 1
                  AND parameter_key IN (
                    'mobile.draft-retention-days',
                    'mobile.max-upload-mb',
                    'mobile.scan-token-length'
                  )
                  AND status = 1
                  AND deleted = 0
                """)).isEqualTo(3);
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_menu
                WHERE tenant_id = 1
                  AND id BETWEEN 60 AND 65
                  AND permission_code LIKE 'mobile:%'
                  AND status = 1
                  AND deleted = 0
                """)).isEqualTo(6);
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_role role
                JOIN system_role_menu relation
                  ON relation.tenant_id = role.tenant_id
                 AND relation.role_id = role.id
                WHERE role.tenant_id = 1
                  AND role.role_code IN ('ADMIN', 'PLANNER', 'OPERATOR')
                  AND relation.menu_id BETWEEN 60 AND 65
                """)).isEqualTo(18);
    }

    @Test
    void seedsBusinessRolesAndDemoUsers() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_role
                WHERE tenant_id = 1
                  AND role_code IN (
                    'ADMIN', 'PLANNER', 'WORKSHOP_MANAGER', 'TEAM_LEADER', 'OPERATOR'
                  )
                  AND status = 1
                  AND deleted = 0
                """)).isEqualTo(5);
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_role
                WHERE tenant_id = 1 AND deleted = 0
                """)).isEqualTo(5);
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_user
                WHERE tenant_id = 1
                  AND username IN (
                    'planner', 'operator01', 'operator02', 'operator03',
                    'operator04', 'operator05'
                  )
                  AND status = 1
                  AND mobile_enabled = 1
                  AND must_change_password = 0
                  AND deleted = 0
                """)).isEqualTo(6);
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_user user
                JOIN system_user_role relation
                  ON relation.tenant_id = user.tenant_id
                 AND relation.user_id = user.id
                 AND relation.deleted = 0
                JOIN system_role role
                  ON role.tenant_id = relation.tenant_id
                 AND role.id = relation.role_id
                 AND role.deleted = 0
                WHERE user.tenant_id = 1
                  AND (
                    (user.username = 'planner' AND role.role_code = 'PLANNER')
                    OR (
                      user.username IN (
                        'operator01', 'operator02', 'operator03',
                        'operator04', 'operator05'
                      )
                      AND role.role_code = 'OPERATOR'
                    )
                  )
                """)).isEqualTo(6);
        assertThat(new BCryptPasswordEncoder(12).matches(
                "888888",
                text("""
                        SELECT password_hash
                        FROM system_user
                        WHERE tenant_id = 1 AND username = 'planner' AND deleted = 0
                        LIMIT 1
                        """)
        )).isTrue();
    }

    @Test
    void seedsWorkshopAndTeamRoleTemplatesWithScopedPermissions() throws Exception {
        assertThat(text("""
                SELECT data_scope
                FROM system_role
                WHERE tenant_id = 1 AND role_code = 'WORKSHOP_MANAGER' AND deleted = 0
                """)).isEqualTo("ORGANIZATION_AND_CHILDREN");
        assertThat(text("""
                SELECT data_scope
                FROM system_role
                WHERE tenant_id = 1 AND role_code = 'TEAM_LEADER' AND deleted = 0
                """)).isEqualTo("ORGANIZATION");
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_role role
                JOIN system_role_menu relation
                  ON relation.tenant_id = role.tenant_id
                 AND relation.role_id = role.id
                JOIN system_menu menu
                  ON menu.tenant_id = relation.tenant_id
                 AND menu.id = relation.menu_id
                WHERE role.tenant_id = 1
                  AND role.role_code = 'TEAM_LEADER'
                  AND menu.permission_code IN (
                    'inspection:task:view', 'inspection:task:assign',
                    'inspection:abnormal:view', 'inspection:abnormal:handle',
                    'mobile:workbench:view'
                  )
                  AND menu.deleted = 0
                """)).isEqualTo(5);
    }

    @Test
    void seedsNotificationRulesMenusAndChannels() throws Exception {
        assertThat(number("""
                SELECT COUNT(*) FROM notification_rule
                WHERE tenant_id = 1 AND enabled = 1 AND deleted = 0
                """)).isEqualTo(10);
        assertThat(number("""
                SELECT COUNT(*) FROM notification_rule
                WHERE tenant_id = 1
                  AND trigger_type IN ('DUE_SOON', 'MANUAL_CREATED', 'OVERDUE')
                  AND JSON_CONTAINS(channels_json, JSON_QUOTE('SYSTEM'))
                  AND JSON_CONTAINS(channels_json, JSON_QUOTE('ANDROID'))
                """)).isEqualTo(10);
        assertThat(number("""
                SELECT COUNT(*) FROM system_menu
                WHERE tenant_id = 1 AND id IN (70, 71, 72, 73, 721, 731)
                  AND status = 1 AND deleted = 0
                """)).isEqualTo(6);
    }

    @Test
    void seedsFaultRepairNumberRulesMenusAndAbnormalLinks() throws Exception {
        assertThat(number("""
                SELECT COUNT(*) FROM system_number_rule
                WHERE tenant_id = 1 AND rule_code IN ('FAULT_REPORT', 'REPAIR_ORDER')
                  AND status = 1 AND deleted = 0
                """)).isEqualTo(2);
        assertThat(number("""
                SELECT COUNT(*) FROM system_menu
                WHERE tenant_id = 1 AND id IN (
                  74,741,742,743,744,7411,7412,7413,7421,7422,7423,7424
                ) AND status = 1 AND deleted = 0
                """)).isEqualTo(12);
        assertThat(number("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN ('inspection_abnormal','maintenance_abnormal')
                  AND column_name = 'repair_order_id'
                """)).isEqualTo(2);
    }

    @Test
    void createsMobileEvidenceAndVersionPolicy() throws Exception {
        assertThat(number("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'mobile_photo_evidence'
                  AND column_name IN (
                    'original_attachment_id','watermarked_attachment_id',
                    'captured_device_time','device_clock_offset_seconds',
                    'latitude','longitude','watermark_text',
                    'original_sha256','watermarked_sha256'
                  )
                """)).isEqualTo(9);
        assertThat(number("""
                SELECT COUNT(*) FROM system_parameter
                WHERE tenant_id = 1 AND parameter_key IN (
                  'mobile.photo-location-required',
                  'mobile.photo-clock-skew-warning-seconds',
                  'mobile.android-min-version-code',
                  'mobile.android-latest-version-name',
                  'mobile.android-download-url',
                  'mobile.android-release-notes'
                ) AND status = 1 AND deleted = 0
                """)).isEqualTo(6);
    }

    @Test
    void seedsConfigurableCustomerBranding() throws Exception {
        assertThat(number("""
                SELECT COUNT(*) FROM system_parameter
                WHERE tenant_id = 1 AND parameter_key IN (
                  'branding.short-name',
                  'branding.subtitle',
                  'branding.logo-url',
                  'branding.primary-color',
                  'branding.secondary-color',
                  'branding.neutral-color'
                ) AND group_code = 'BRANDING' AND built_in = 1
                  AND status = 1 AND deleted = 0
                """)).isEqualTo(6);
        assertThat(text("""
                SELECT parameter_value FROM system_parameter
                WHERE tenant_id = 1 AND parameter_key = 'branding.primary-color'
                """)).isEqualTo("#1c7d50");
        assertThat(text("""
                SELECT parameter_value FROM system_parameter
                WHERE tenant_id = 1 AND parameter_key = 'branding.secondary-color'
                """)).isEqualTo("#3e3a39");
        assertThat(text("""
                SELECT parameter_value FROM system_parameter
                WHERE tenant_id = 1 AND parameter_key = 'branding.neutral-color'
                """)).isEqualTo("#c4000a");
        assertThat(text("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'system_parameter'
                  AND column_name = 'parameter_value'
                """)).isEqualTo("mediumtext");
    }

    @Test
    void seedsProductionTeamsAndAssignsDemoOperators() throws Exception {
        assertThat(number("""
                SELECT COUNT(*)
                FROM organization
                WHERE tenant_id = 1
                  AND organization_type = 'TEAM'
                  AND organization_code IN (
                    'TEAM-A-1', 'TEAM-A-2', 'TEAM-B-1', 'TEAM-B-2',
                    'TEAM-C-1', 'TEAM-C-2', 'TEAM-D-1', 'TEAM-D-2'
                  )
                  AND status = 1
                  AND deleted = 0
                """)).isEqualTo(8);
        assertThat(number("""
                SELECT COUNT(*)
                FROM system_user user
                JOIN organization team
                  ON team.tenant_id = user.tenant_id
                 AND team.id = user.organization_id
                 AND team.organization_type = 'TEAM'
                 AND team.deleted = 0
                WHERE user.tenant_id = 1
                  AND user.username IN (
                    'operator01', 'operator02', 'operator03',
                    'operator04', 'operator05'
                  )
                  AND user.deleted = 0
                """)).isEqualTo(5);
    }

    private long number(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String text(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
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
