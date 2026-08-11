package com.leantpm.integration;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.mobile.MobileDtos;
import com.leantpm.mobile.MobileService;
import com.leantpm.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${LEANTPM_TEST_DB_URL}",
                "spring.datasource.username=${LEANTPM_TEST_DB_USERNAME:root}",
                "spring.datasource.password=${LEANTPM_TEST_DB_PASSWORD:}",
                "leantpm.security.jwt-secret=integration-test-secret-at-least-32-characters",
                "leantpm.bootstrap.admin-password="
        }
)
@DirtiesContext
@Transactional
class MobileMySqlIntegrationTest {
    private static final long USER_ID = 9501L;
    private static final String TOKEN = "a".repeat(64);

    @Autowired
    private MobileService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareMobileUserAndBarcode() {
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, mobile_enabled, must_change_password)
                VALUES (?, 1, 'mobile_it', 'not-used', '移动端集成测试',
                        2, 1, 1, 0)
                """, USER_ID);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 1)",
                USER_ID
        );
        jdbc.update("""
                INSERT INTO equipment_barcode
                    (tenant_id, equipment_id, access_token, barcode_type,
                     active_slot, generated_by)
                VALUES (1, 1, ?, 'QR', 1, ?)
                """, TOKEN, USER_ID);
        authenticate();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loadsBootstrapAndScopedEquipmentContext() {
        MobileDtos.Bootstrap bootstrap = service.bootstrap();
        assertThat(bootstrap.draftRetentionDays()).isEqualTo(7);
        assertThat(bootstrap.maxUploadMb()).isEqualTo(10);
        assertThat(bootstrap.photoPolicy().clockSkewWarningSeconds()).isEqualTo(300);
        assertThat(bootstrap.androidVersion().minimumVersionCode()).isEqualTo(101);
        assertThat(bootstrap.equipmentStatus().total()).isEqualTo(8);
        assertThat(bootstrap.inspection()).isNotNull();
        assertThat(bootstrap.inspectionAbnormal()).isNotNull();
        assertThat(bootstrap.personalInspectionReport().startDate())
                .isEqualTo(LocalDate.now().withDayOfMonth(1));
        assertThat(bootstrap.personalInspectionReport().endDate()).isEqualTo(LocalDate.now());
        assertThat(bootstrap.maintenance()).isNotNull();
        assertThat(bootstrap.messages()).isNotNull();

        MobileDtos.EquipmentContext context = service.equipment(TOKEN.toUpperCase());
        assertThat(context.equipment().equipmentId()).isEqualTo(1L);
        assertThat(context.equipment().equipmentCode()).isEqualTo("VIZ-CNC-01");
        assertThat(context.equipment().model()).isNotBlank();
        assertThat(context.equipment().assetNumber()).isNotBlank();
        assertThat(context.equipment().lifecycleStage()).isNotBlank();
        assertThat(context.equipment().statusCode()).isEqualTo("RUNNING");
        assertThat(context.equipment().statusColor()).startsWith("#");
        assertThat(context.activeTasks()).isNotNull();
        assertThat(context.todayInspections()).isNotNull();
        assertThat(context.inspectionSchemes())
                .extracting(MobileDtos.ApplicableInspectionScheme::schemeCode)
                .contains("ISP-DEMO-CNC-DAILY");
    }

    @Test
    void directRegistrationFallsBackToPublishedTemplateWhenEquipmentHasNoDedicatedScheme() {
        long equipmentId = 3L;
        String equipmentToken = "b".repeat(64);
        jdbc.update("""
                INSERT INTO equipment_barcode
                    (tenant_id, equipment_id, access_token, barcode_type,
                     active_slot, generated_by)
                VALUES (1, ?, ?, 'QR', 1, ?)
                """, equipmentId, equipmentToken, USER_ID);
        Long dedicatedSchemeCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM inspection_scheme scheme
                JOIN inspection_scheme_version version
                  ON version.tenant_id = scheme.tenant_id
                 AND version.id = scheme.current_version_id
                JOIN equipment equipment_row
                  ON equipment_row.tenant_id = scheme.tenant_id
                 AND equipment_row.id = ?
                WHERE scheme.tenant_id = 1
                  AND scheme.status = 1 AND scheme.deleted = 0
                  AND version.version_status = 'PUBLISHED'
                  AND (
                      EXISTS (
                          SELECT 1 FROM inspection_scheme_equipment relation
                          WHERE relation.tenant_id = version.tenant_id
                            AND relation.scheme_version_id = version.id
                            AND relation.equipment_id = equipment_row.id
                      )
                      OR EXISTS (
                          SELECT 1 FROM inspection_scheme_category relation
                          WHERE relation.tenant_id = version.tenant_id
                            AND relation.scheme_version_id = version.id
                            AND relation.equipment_category_id = equipment_row.category_id
                      )
                  )
                """, Long.class, equipmentId);
        assertThat(dedicatedSchemeCount).isZero();

        MobileDtos.EquipmentContext context = service.equipment(equipmentToken);
        assertThat(context.inspectionSchemes()).isNotEmpty();

        MobileDtos.ApplicableInspectionScheme template = context.inspectionSchemes().getFirst();
        long taskId = service.createDirectInspectionReport(
                equipmentToken,
                new MobileDtos.DirectInspectionReportRequest(
                        template.schemeVersionId(), "无专属方案时扫码登记", false
                ),
                "mobile-direct-fallback-it"
        );

        assertThat(jdbc.queryForObject(
                "SELECT equipment_id FROM inspection_task WHERE tenant_id = 1 AND id = ?",
                Long.class,
                taskId
        )).isEqualTo(equipmentId);
        assertThat(jdbc.queryForObject(
                "SELECT source_type FROM inspection_task WHERE tenant_id = 1 AND id = ?",
                String.class,
                taskId
        )).isEqualTo("QUICK_ENTRY");
    }

    @Test
    void upperOrganizationUsesTheSameDescendantScopeForSummaryAndScannedEquipment() {
        long scopedUserId = 9502L;
        Long equipmentOrganizationId = jdbc.queryForObject(
                "SELECT organization_id FROM equipment WHERE tenant_id = 1 AND id = 1",
                Long.class
        );
        Long upperOrganizationId = jdbc.queryForObject(
                "SELECT parent_id FROM organization WHERE tenant_id = 1 AND id = ?",
                Long.class,
                equipmentOrganizationId
        );
        Long managerRoleId = jdbc.queryForObject("""
                SELECT id FROM system_role
                WHERE tenant_id = 1 AND role_code = 'WORKSHOP_MANAGER' AND deleted = 0
                LIMIT 1
                """, Long.class);
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, mobile_enabled, must_change_password)
                VALUES (?, 1, 'mobile_scope_it', 'not-used', '移动端范围测试',
                        ?, 1, 1, 0)
                """, scopedUserId, upperOrganizationId);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, ?)",
                scopedUserId,
                managerRoleId
        );
        authenticate(scopedUserId, "mobile_scope_it", Set.of("WORKSHOP_MANAGER"));

        Long expectedEquipmentCount = jdbc.queryForObject("""
                WITH RECURSIVE organization_tree AS (
                    SELECT id FROM organization
                    WHERE tenant_id = 1 AND id = ? AND status = 1 AND deleted = 0
                    UNION ALL
                    SELECT child.id
                    FROM organization child
                    JOIN organization_tree parent ON child.parent_id = parent.id
                    WHERE child.tenant_id = 1 AND child.status = 1 AND child.deleted = 0
                )
                SELECT COUNT(*) FROM equipment
                WHERE tenant_id = 1 AND status = 1 AND deleted = 0
                  AND organization_id IN (SELECT id FROM organization_tree)
                """, Long.class, upperOrganizationId);

        MobileDtos.Bootstrap bootstrap = service.bootstrap();
        MobileDtos.EquipmentContext context = service.equipment(TOKEN.toUpperCase());

        assertThat(bootstrap.equipmentStatus().total()).isEqualTo(expectedEquipmentCount);
        assertThat(context.equipment().equipmentId()).isEqualTo(1L);
    }

    @Test
    void selfScopeEmployeeCanScanEquipmentInOwnTeam() {
        long employeeUserId = 9503L;
        Long equipmentOrganizationId = jdbc.queryForObject(
                "SELECT organization_id FROM equipment WHERE tenant_id = 1 AND id = 1",
                Long.class
        );
        Long operatorRoleId = jdbc.queryForObject("""
                SELECT id FROM system_role
                WHERE tenant_id = 1 AND role_code = 'OPERATOR' AND deleted = 0
                LIMIT 1
                """, Long.class);
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, mobile_enabled, must_change_password)
                VALUES (?, 1, 'mobile_self_scope_it', 'not-used', '本班组扫码测试',
                        ?, 1, 1, 0)
                """, employeeUserId, equipmentOrganizationId);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, ?)",
                employeeUserId,
                operatorRoleId
        );
        authenticate(employeeUserId, "mobile_self_scope_it", Set.of("OPERATOR"));

        Long expectedEquipmentCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment
                WHERE tenant_id = 1 AND status = 1 AND deleted = 0
                  AND organization_id = ?
                """, Long.class, equipmentOrganizationId);

        MobileDtos.Bootstrap bootstrap = service.bootstrap();
        MobileDtos.EquipmentContext context = service.equipment(TOKEN.toUpperCase());

        assertThat(bootstrap.equipmentStatus().total()).isEqualTo(expectedEquipmentCount);
        assertThat(context.equipment().equipmentId()).isEqualTo(1L);
        assertThat(context.equipment().organizationName()).isNotBlank();
    }

    @Test
    void teamEmployeeCanScanTheDirectParentTreeButNotItsSiblingBranch() {
        long lineId = 9580L;
        long siblingLineId = 9581L;
        long teamId = 9582L;
        long siblingTeamId = 9583L;
        long outsideTeamId = 9584L;
        long teamUserId = 9585L;
        long lineUserId = 9586L;
        String allowedToken = "c".repeat(64);
        String deniedToken = "d".repeat(64);
        jdbc.update("""
                INSERT INTO organization
                    (id, tenant_id, parent_id, organization_code, organization_name,
                     organization_type, status, created_by, updated_by)
                VALUES
                    (?, 1, 3, 'MOBILE-LINE-A', '扫码范围一工段', 'LINE', 1, 1, 1),
                    (?, 1, 3, 'MOBILE-LINE-B', '扫码范围二工段', 'LINE', 1, 1, 1),
                    (?, 1, ?, 'MOBILE-TEAM-A1', '扫码范围一班', 'TEAM', 1, 1, 1),
                    (?, 1, ?, 'MOBILE-TEAM-A2', '扫码范围二班', 'TEAM', 1, 1, 1),
                    (?, 1, ?, 'MOBILE-TEAM-B1', '扫码范围外部班组', 'TEAM', 1, 1, 1)
                """, lineId, siblingLineId, teamId, lineId,
                siblingTeamId, lineId, outsideTeamId, siblingLineId);
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, mobile_enabled, must_change_password)
                VALUES
                    (?, 1, 'mobile_team_parent_it', 'not-used', '班组父级扫码测试',
                     ?, 1, 1, 0),
                    (?, 1, 'mobile_line_descendant_it', 'not-used', '工段下级扫码测试',
                     ?, 1, 1, 0)
                """, teamUserId, teamId, lineUserId, lineId);
        jdbc.update("""
                INSERT INTO system_user_role (tenant_id, user_id, role_id)
                SELECT 1, ?, id FROM system_role
                WHERE tenant_id = 1 AND role_code = 'OPERATOR' AND deleted = 0
                """, teamUserId);
        jdbc.update("""
                INSERT INTO system_user_role (tenant_id, user_id, role_id)
                SELECT 1, ?, id FROM system_role
                WHERE tenant_id = 1 AND role_code = 'OPERATOR' AND deleted = 0
                """, lineUserId);
        insertScopedEquipment(9587L, "MOBILE-SCOPE-ALLOWED", siblingTeamId, allowedToken);
        insertScopedEquipment(9588L, "MOBILE-SCOPE-DENIED", outsideTeamId, deniedToken);

        authenticate(teamUserId, "mobile_team_parent_it", Set.of("OPERATOR"));
        assertThat(service.equipment(allowedToken).equipment().equipmentId()).isEqualTo(9587L);
        assertThatThrownBy(() -> service.equipment(deniedToken))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_EQUIPMENT_DATA_SCOPE_DENIED");

        authenticate(lineUserId, "mobile_line_descendant_it", Set.of("OPERATOR"));
        assertThat(service.equipment(allowedToken).equipment().equipmentId()).isEqualTo(9587L);
        assertThatThrownBy(() -> service.equipment(deniedToken))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_EQUIPMENT_DATA_SCOPE_DENIED");
    }

    @Test
    void filtersPersonalInspectionReportBySelectedDateRange() {
        Long organizationId = jdbc.queryForObject(
                "SELECT organization_id FROM equipment WHERE tenant_id = 1 AND id = 1", Long.class
        );
        Long locationId = jdbc.queryForObject(
                "SELECT location_id FROM equipment WHERE tenant_id = 1 AND id = 1", Long.class
        );
        insertReportTask(9701L, "MOBILE-REPORT-DONE", organizationId, locationId, "COMPLETED");
        insertReportTask(9702L, "MOBILE-REPORT-OVERDUE", organizationId, locationId, "OVERDUE");
        insertReportTask(9703L, "MOBILE-REPORT-CANCELLED", organizationId, locationId, "CANCELLED");
        insertReportTaskAt(
                9704L, "MOBILE-REPORT-END-OF-DAY", organizationId, locationId,
                "COMPLETED", LocalDate.now(), LocalDate.now().plusDays(1).atStartOfDay()
        );
        insertReportTaskAt(
                9705L, "MOBILE-REPORT-NEXT-DAY", organizationId, locationId,
                "COMPLETED", LocalDate.now().plusDays(1), LocalDate.now().atTime(23, 59, 59)
        );
        insertCollaborativeReportTask(
                9706L, "MOBILE-REPORT-COLLABORATOR", organizationId, locationId
        );

        MobileDtos.PersonalInspectionReport report = service.personalInspectionReport(
                LocalDate.now(), LocalDate.now()
        );

        assertThat(report.startDate()).isEqualTo(LocalDate.now());
        assertThat(report.endDate()).isEqualTo(LocalDate.now());
        assertThat(report.due()).isEqualTo(5);
        assertThat(report.completed()).isEqualTo(3);
        assertThat(report.pending()).isEqualTo(2);
        assertThat(report.overdue()).isEqualTo(1);
        assertThat(report.planDue()).isZero();
        assertThat(report.planCompleted()).isZero();
        assertThat(report.planOverdue()).isZero();
        assertThat(report.registered()).isEqualTo(3);
        assertThat(report.quickRegistered()).isZero();
        assertThat(report.equipmentCovered()).isEqualTo(1);
        assertThatThrownBy(() -> service.personalInspectionReport(
                LocalDate.now(), LocalDate.now().minusDays(1)
        )).isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_REPORT_DATE_RANGE_INVALID");
    }

    @Test
    void createsQuickEntryAndWarnsBeforeSameDayDuplicate() {
        MobileDtos.EquipmentContext context = service.equipment(TOKEN);
        MobileDtos.ApplicableInspectionScheme scheme = context.inspectionSchemes().getFirst();

        long taskId = service.createDirectInspectionReport(
                TOKEN,
                new MobileDtos.DirectInspectionReportRequest(
                        scheme.schemeVersionId(), "扫码直接登记", true
                ),
                "mobile-direct-it-first"
        );

        assertThat(jdbc.queryForObject(
                "SELECT source_type FROM inspection_task WHERE tenant_id = 1 AND id = ?",
                String.class,
                taskId
        )).isEqualTo("QUICK_ENTRY");
        assertThat(service.equipment(TOKEN).todayInspections())
                .extracting(MobileDtos.TodayInspectionRecord::taskId)
                .contains(taskId);
        assertThatThrownBy(() -> service.createDirectInspectionReport(
                TOKEN,
                new MobileDtos.DirectInspectionReportRequest(
                        scheme.schemeVersionId(), "重复扫码", false
                ),
                "mobile-direct-it-duplicate"
        )).isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_INSPECTION_TODAY_EXISTS");
    }

    @Test
    void rejectsDisabledMobileAccount() {
        jdbc.update(
                "UPDATE system_user SET mobile_enabled = 0 WHERE tenant_id = 1 AND id = ?",
                USER_ID
        );

        assertThatThrownBy(service::bootstrap)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_ACCESS_DISABLED");
        assertThatThrownBy(() -> service.equipment(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_ACCESS_DISABLED");
    }

    @Test
    void registersIdempotentPhotoEvidenceWithServerHashes() {
        long taskId = 9600L;
        long taskItemId = 9600L;
        Long organizationId = jdbc.queryForObject(
                "SELECT organization_id FROM equipment WHERE tenant_id = 1 AND id = 1", Long.class
        );
        Long locationId = jdbc.queryForObject(
                "SELECT location_id FROM equipment WHERE tenant_id = 1 AND id = 1", Long.class
        );
        jdbc.update("""
                INSERT INTO inspection_task
                    (id, tenant_id, task_code, inspection_type, equipment_id,
                     organization_id, location_id, planned_date, due_time,
                     assignee_user_id, task_status, source_type, created_by)
                VALUES (?, 1, 'MOBILE-EVIDENCE-IT', 'ROUTINE', 1, ?, ?, CURRENT_DATE(),
                        CURRENT_TIMESTAMP(3), ?, 'IN_PROGRESS', 'MANUAL', ?)
                """, taskId, organizationId, locationId, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO inspection_task_item
                    (id, tenant_id, task_id, item_code, item_name, item_category,
                     inspection_part, inspection_content, inspection_standard, result_type)
                VALUES (?, 1, ?, 'PHOTO-IT', '现场照片', '外观',
                        '主轴箱', '拍摄设备现场照片', '照片清晰并带水印', 'PHOTO')
                """, taskItemId, taskId);
        insertAttachment(9601L, "original.jpeg", "a".repeat(64));
        insertAttachment(9602L, "watermarked.jpeg", "b".repeat(64));
        LocalDateTime captured = LocalDateTime.now().withNano(0);
        MobileDtos.RegisterPhotoEvidenceRequest request =
                new MobileDtos.RegisterPhotoEvidenceRequest(
                        "INSPECTION", taskId, taskItemId, 9601L, 9602L,
                        captured, captured.minusSeconds(12), 12,
                        "主轴箱",
                        "宝山矿业\nVIZ-CNC-01\n服务时间 2026-08-03 18:00:00\n故障位置/部位 主轴箱"
                );

        MobileDtos.PhotoEvidence created = service.registerPhotoEvidence(request);
        MobileDtos.PhotoEvidence repeated = service.registerPhotoEvidence(request);

        assertThat(repeated.id()).isEqualTo(created.id());
        assertThat(created.clockSkewWarning()).isFalse();
        assertThat(created.originalSha256()).isEqualTo("a".repeat(64));
        assertThat(created.watermarkedSha256()).isEqualTo("b".repeat(64));
        assertThat(service.photoEvidence(created.id()).faultLocationText())
                .isEqualTo("主轴箱");
        assertThat(service.photoEvidence(created.id()).latitude()).isNull();

        insertAttachment(9603L, "task-original.jpeg", "c".repeat(64));
        insertAttachment(9604L, "task-watermarked.jpeg", "d".repeat(64));
        MobileDtos.PhotoEvidence taskLevel = service.registerPhotoEvidence(
                new MobileDtos.RegisterPhotoEvidenceRequest(
                        "INSPECTION", taskId, null, 9603L, 9604L,
                        captured, captured.minusSeconds(12), 12,
                        "设备现场", "大宝山矿业\n整单现场图片\n2026-08-07 09:00:00"
                )
        );
        assertThat(taskLevel.taskItemId()).isNull();
    }

    private void insertAttachment(long id, String name, String sha256) {
        jdbc.update("""
                INSERT INTO system_attachment
                    (id, tenant_id, original_name, stored_name, storage_path,
                     content_type, extension, file_size, sha256, created_by, updated_by)
                VALUES (?, 1, ?, ?, ?, 'image/jpeg', 'jpeg', 1024, ?, ?, ?)
                """, id, name, name, "it/" + name, sha256, USER_ID, USER_ID);
    }

    private void insertReportTask(
            long id,
            String code,
            long organizationId,
            long locationId,
            String status
    ) {
        insertReportTaskAt(
                id, code, organizationId, locationId, status,
                LocalDate.now(), LocalDateTime.now().minusHours(1)
        );
    }

    private void insertReportTaskAt(
            long id,
            String code,
            long organizationId,
            long locationId,
            String status,
            LocalDate plannedDate,
            LocalDateTime dueTime
    ) {
        jdbc.update("""
                INSERT INTO inspection_task
                    (id, tenant_id, task_code, inspection_type, equipment_id,
                     organization_id, location_id, planned_date, due_time,
                     assignee_user_id, task_status, source_type, completed_time, created_by)
                VALUES (?, 1, ?, 'ROUTINE', 1, ?, ?, ?,
                        ?, ?, ?, 'MANUAL',
                        IF(? = 'COMPLETED', CURRENT_TIMESTAMP(3), NULL), ?)
                """, id, code, organizationId, locationId, plannedDate, dueTime,
                USER_ID, status, status, USER_ID);
    }

    private void insertCollaborativeReportTask(
            long id,
            String code,
            long organizationId,
            long locationId
    ) {
        jdbc.update("""
                INSERT INTO inspection_task
                    (id, tenant_id, task_code, inspection_type, equipment_id,
                     organization_id, location_id, planned_date, due_time,
                     assignee_user_id, task_status, source_type, created_by)
                VALUES (?, 1, ?, 'ROUTINE', 1, ?, ?, CURRENT_DATE(),
                        TIMESTAMP(CURRENT_DATE(), '23:59:59'), 9903, 'PENDING',
                        'MANUAL', ?)
                """, id, code, organizationId, locationId, USER_ID);
        jdbc.update("""
                INSERT INTO inspection_task_assignee
                    (tenant_id, task_id, user_id, sort_order, primary_flag, created_by)
                VALUES (1, ?, ?, 2, 0, ?)
                """, id, USER_ID, USER_ID);
    }

    private void insertScopedEquipment(
            long equipmentId,
            String equipmentCode,
            long organizationId,
            String token
    ) {
        jdbc.update("""
                INSERT INTO equipment
                    (id, tenant_id, equipment_code, equipment_name, category_id,
                     organization_id, location_id, lifecycle_stage, status,
                     created_by, updated_by)
                SELECT ?, tenant_id, ?, ?, category_id,
                       ?, location_id, 'IN_SERVICE', 1, ?, ?
                FROM equipment
                WHERE tenant_id = 1 AND id = 1
                """, equipmentId, equipmentCode, equipmentCode, organizationId, USER_ID, USER_ID);
        jdbc.update("""
                INSERT INTO equipment_barcode
                    (tenant_id, equipment_id, access_token, barcode_type,
                     active_slot, generated_by)
                VALUES (1, ?, ?, 'QR', 1, ?)
                """, equipmentId, token, USER_ID);
    }

    private void authenticate() {
        authenticate(USER_ID, "mobile_it", Set.of("ADMIN"));
    }

    private void authenticate(long userId, String username, Set<String> roles) {
        CurrentUser current = new CurrentUser(
                userId,
                1L,
                username,
                "移动端集成测试",
                false,
                roles,
                Set.of(
                        "mobile:access",
                        "mobile:workbench:view",
                        "mobile:scan",
                        "mobile:task:view",
                        "mobile:message:view",
                        "mobile:profile:view"
                ),
                "mobile-it-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(current, null, Set.of())
        );
    }
}
