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
                "spring.data.redis.repositories.enabled=false",
                "management.health.redis.enabled=false",
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
        assertThat(bootstrap.androidVersion().minimumVersionCode()).isEqualTo(2);
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
        assertThat(context.inspectionSchemes())
                .extracting(MobileDtos.ApplicableInspectionScheme::schemeCode)
                .contains("ISP-DEMO-CNC-DAILY");
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

        MobileDtos.PersonalInspectionReport report = service.personalInspectionReport(
                LocalDate.now(), LocalDate.now()
        );

        assertThat(report.startDate()).isEqualTo(LocalDate.now());
        assertThat(report.endDate()).isEqualTo(LocalDate.now());
        assertThat(report.due()).isEqualTo(3);
        assertThat(report.completed()).isEqualTo(2);
        assertThat(report.pending()).isEqualTo(1);
        assertThat(report.overdue()).isEqualTo(1);
        assertThatThrownBy(() -> service.personalInspectionReport(
                LocalDate.now(), LocalDate.now().minusDays(1)
        )).isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MOBILE_REPORT_DATE_RANGE_INVALID");
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

    private void authenticate() {
        CurrentUser current = new CurrentUser(
                USER_ID,
                1L,
                "mobile_it",
                "移动端集成测试",
                false,
                Set.of("ADMIN"),
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
