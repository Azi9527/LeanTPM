package com.leantpm.integration;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.oee.OeeCatalogService;
import com.leantpm.oee.OeeDtos;
import com.leantpm.oee.OeeImportService;
import com.leantpm.oee.OeeService;
import com.leantpm.security.CurrentUser;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

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
class OeeMySqlIntegrationTest {
    private static final long ADMIN_ID = 9301L;

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private OeeService oeeService;

    @Autowired
    private OeeCatalogService oeeCatalogService;

    @Autowired
    private OeeImportService oeeImportService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareUser() {
        jdbc.update(
                """
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'oee_admin_it', 'not-used', 'OEE集成管理员', 1, 1, 0)
                """,
                ADMIN_ID
        );
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 1)",
                ADMIN_ID
        );
        CurrentUser user = new CurrentUser(
                ADMIN_ID,
                1L,
                "oee_admin_it",
                "OEE集成管理员",
                true,
                Set.of("ADMIN"),
                Set.of(
                        "oee:record:view",
                        "oee:record:manage",
                        "oee:record:approve",
                        "oee:record:lock",
                        "oee:record:recalculate",
                        "oee:output:manage",
                        "oee:downtime:manage",
                        "oee:analysis:view"
                ),
                "oee-it-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void calculatesAuditsLocksRevisesAndAnalyzesOeeWithDecimals() {
        long equipmentId = createEquipment("EQ-OEE-IT-001");
        LocalDate productionDate = LocalDate.of(2026, 7, 30);
        long downtimeId = oeeService.createDowntime(new OeeDtos.SaveDowntimeRequest(
                equipmentId,
                productionDate,
                1L,
                1L,
                LocalDateTime.of(2026, 7, 30, 10, 0),
                LocalDateTime.of(2026, 7, 30, 10, 50),
                new BigDecimal("50"),
                false,
                "MANUAL",
                null,
                "主轴故障",
                null
        ));
        oeeService.createDowntime(new OeeDtos.SaveDowntimeRequest(
                equipmentId,
                productionDate,
                1L,
                3L,
                LocalDateTime.of(2026, 7, 30, 11, 0),
                LocalDateTime.of(2026, 7, 30, 11, 10),
                new BigDecimal("10"),
                false,
                "MANUAL",
                null,
                "短暂停机",
                null
        ));

        long recordId = oeeService.createRecord(recordRequest(
                equipmentId, productionDate, null
        ));
        OeeDtos.OeeRecordRow record = oeeService.record(recordId);
        assertThat(record.loadingTimeMinutes()).isEqualByComparingTo("450.000");
        assertThat(record.unplannedDowntimeMinutes()).isEqualByComparingTo("50.000");
        assertThat(record.runTimeMinutes()).isEqualByComparingTo("400.000");
        assertThat(record.availabilityRate()).isEqualByComparingTo("0.888889");
        assertThat(record.performanceRate()).isEqualByComparingTo("0.900000");
        assertThat(record.qualityRate()).isEqualByComparingTo("0.950000");
        assertThat(record.oeeRate()).isEqualByComparingTo("0.760000");
        assertThat(record.targetOeeRate()).isEqualByComparingTo("0.850725");
        assertThat(record.anomalyFlag()).isFalse();
        assertThat(oeeService.calculationLogs(recordId)).singleElement()
                .extracting(OeeDtos.CalculationLogRow::triggerType)
                .isEqualTo("CREATE");

        assertThatThrownBy(() -> oeeService.createRecord(
                recordRequest(equipmentId, productionDate, null)
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能存在一条");

        oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("SUBMIT", record.version(), "提交审核")
        );
        record = oeeService.record(recordId);
        assertThat(record.dataStatus()).isEqualTo("SUBMITTED");
        oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("APPROVE", record.version(), "审核通过")
        );
        record = oeeService.record(recordId);
        assertThat(record.dataStatus()).isEqualTo("APPROVED");
        LocalDateTime approvedTime = record.approvedTime();
        authenticate(Set.of("oee:record:approve"));
        OeeDtos.OeeRecordRow approved = record;
        assertThatThrownBy(() -> oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("LOCK", approved.version(), "越权锁定")
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
        authenticate(allOeePermissions());
        oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("LOCK", record.version(), "月结锁定")
        );
        OeeDtos.OeeRecordRow locked = oeeService.record(recordId);
        assertThat(locked.dataStatus()).isEqualTo("LOCKED");
        OeeDtos.DowntimeRow downtime = oeeService.downtime(downtimeId);
        int lockedDowntimeVersion = downtime.version();
        assertThatThrownBy(() -> oeeService.updateDowntime(
                downtimeId,
                downtimeRequest(equipmentId, productionDate, lockedDowntimeVersion, 60)
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("锁定");

        oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("UNLOCK", locked.version(), "修订停机时间")
        );
        assertThat(oeeService.record(recordId).approvedTime()).isEqualTo(approvedTime);
        downtime = oeeService.downtime(downtimeId);
        oeeService.updateDowntime(
                downtimeId,
                downtimeRequest(equipmentId, productionDate, downtime.version(), 60)
        );
        record = oeeService.record(recordId);
        assertThat(record.dataStatus()).isEqualTo("DRAFT");
        assertThat(record.unplannedDowntimeMinutes()).isEqualByComparingTo("60.000");
        assertThat(record.runTimeMinutes()).isEqualByComparingTo("390.000");
        assertThat(oeeService.calculationLogs(recordId)).hasSize(3);

        oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("SUBMIT", record.version(), "修订后提交")
        );
        record = oeeService.record(recordId);
        oeeService.workflow(
                recordId,
                new OeeDtos.WorkflowRequest("APPROVE", record.version(), "修订后审核")
        );
        OeeDtos.AnalysisResult analysis = oeeService.analysis(
                productionDate, productionDate, null, equipmentId,
                "DAY", "EQUIPMENT", 20
        );
        assertThat(analysis.summary().recordCount()).isEqualTo(1);
        assertThat(analysis.trend()).singleElement();
        assertThat(analysis.ranking()).singleElement()
                .extracting(OeeDtos.RankingRow::scopeId)
                .isEqualTo(equipmentId);
        OeeDtos.AnalysisResult workshopAnalysis = oeeService.analysis(
                productionDate, productionDate, null, null,
                "DAY", "WORKSHOP", 20
        );
        assertThat(workshopAnalysis.ranking()).singleElement()
                .extracting(
                        OeeDtos.RankingRow::scopeId,
                        OeeDtos.RankingRow::scopeType
                )
                .containsExactly(3L, "WORKSHOP");
        OeeDtos.AnalysisResult enterpriseAnalysis = oeeService.analysis(
                productionDate, productionDate, 1L, null,
                "DAY", "ENTERPRISE", 20
        );
        assertThat(enterpriseAnalysis.summary().recordCount()).isEqualTo(1);
        assertThat(analysis.losses())
                .extracting(
                        OeeDtos.LossAnalysisRow::reasonCode,
                        OeeDtos.LossAnalysisRow::durationMinutes
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "BREAKDOWN", new BigDecimal("60.000")
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "MINOR_STOPPAGE", new BigDecimal("10.000")
                        )
                );
        assertThat(analysis.records()).singleElement()
                .extracting(OeeDtos.OeeRecordRow::id)
                .isEqualTo(recordId);
    }

    @Test
    void importsXlsxWithPerRowResultAndImportCalculationLog() throws Exception {
        long equipmentId = createEquipment("EQ-OEE-IT-IMPORT");
        byte[] template = oeeImportService.template();
        byte[] workbookBytes;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).getRow(1);
            row.getCell(0).setCellValue("EQ-OEE-IT-IMPORT");
            row.getCell(1).setCellValue("2026-07-29");
            row.getCell(2).setCellValue("DAY");
            row.getCell(3).setCellValue("60");
            row.getCell(4).setCellValue("480");
            row.getCell(5).setCellValue("30");
            row.getCell(6).setCellValue("400");
            row.getCell(7).setCellValue("360");
            row.getCell(8).setCellValue("342");
            row.getCell(9).setCellValue("18");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "oee-import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytes
        );

        OeeDtos.ImportResult result = oeeImportService.importWorkbook(file);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failureRows()).isZero();
        OeeDtos.OeeRecordRow imported = oeeService.records(
                equipmentId,
                null,
                null,
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 7, 29),
                1,
                20
        ).records().getFirst();
        assertThat(imported.sourceType()).isEqualTo("EXCEL");
        assertThat(imported.oeeRate()).isEqualByComparingTo("0.760000");
        assertThat(oeeService.calculationLogs(imported.id())).singleElement()
                .extracting(OeeDtos.CalculationLogRow::triggerType)
                .isEqualTo("IMPORT");
    }

    @Test
    void managesCatalogHierarchyTargetsAndSoftDeletedUniqueKeys() {
        OeeDtos.SaveCalendarRequest calendarRequest =
                new OeeDtos.SaveCalendarRequest(
                        3L,
                        LocalDate.of(2026, 8, 1),
                        1L,
                        "WORKDAY",
                        660,
                        30,
                        "ENABLED",
                        "集成测试日历",
                        null
                );
        long calendarId = oeeCatalogService.createCalendar(calendarRequest);
        OeeDtos.CalendarRow calendar = oeeCatalogService.calendar(calendarId);
        oeeCatalogService.deleteCalendar(calendarId, calendar.version());
        long recreatedCalendarId =
                oeeCatalogService.createCalendar(calendarRequest);
        assertThat(recreatedCalendarId).isGreaterThan(calendarId);

        long targetId = oeeCatalogService.createTarget(
                new OeeDtos.SaveTargetRequest(
                        "车间精益目标",
                        "WORKSHOP",
                        3L,
                        null,
                        new BigDecimal("0.910000"),
                        new BigDecimal("0.960000"),
                        new BigDecimal("0.990000"),
                        LocalDate.of(2027, 1, 1),
                        null,
                        1,
                        "目标乘积由后端生成",
                        null
                )
        );
        assertThat(oeeCatalogService.target(targetId).oeeTarget())
                .isEqualByComparingTo("0.864864");

        long parentId = oeeCatalogService.createLossReason(
                lossReasonRequest(0L, "IT_PARENT", "测试父原因", null)
        );
        long childId = oeeCatalogService.createLossReason(
                lossReasonRequest(parentId, "IT_CHILD", "测试子原因", null)
        );
        assertThatThrownBy(() -> oeeCatalogService.updateLossReason(
                parentId,
                lossReasonRequest(childId, "IT_PARENT", "测试父原因", 0)
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("循环");
    }

    private OeeDtos.SaveOeeRecordRequest recordRequest(
            long equipmentId,
            LocalDate productionDate,
            Integer version
    ) {
        return new OeeDtos.SaveOeeRecordRequest(
                equipmentId,
                productionDate,
                1L,
                new BigDecimal("60"),
                new BigDecimal("480"),
                new BigDecimal("30"),
                new BigDecimal("400"),
                new BigDecimal("360"),
                new BigDecimal("342"),
                new BigDecimal("18"),
                "MANUAL",
                version
        );
    }

    private OeeDtos.SaveDowntimeRequest downtimeRequest(
            long equipmentId,
            LocalDate productionDate,
            int version,
            int durationMinutes
    ) {
        return new OeeDtos.SaveDowntimeRequest(
                equipmentId,
                productionDate,
                1L,
                1L,
                LocalDateTime.of(2026, 7, 30, 10, 0),
                LocalDateTime.of(2026, 7, 30, 10, 0).plusMinutes(durationMinutes),
                BigDecimal.valueOf(durationMinutes),
                false,
                "MANUAL",
                null,
                "修订停机时间",
                version
        );
    }

    private long createEquipment(String code) {
        return equipmentService.create(new EquipmentDtos.SaveEquipmentRequest(
                code,
                "OEE集成测试设备",
                3L,
                "CNC-OEE-IT",
                null,
                "LeanTPM",
                "Integration",
                code + "-SN",
                null,
                null,
                4L,
                4L,
                ADMIN_ID,
                null,
                "IN_SERVICE",
                true,
                false,
                true,
                true,
                "OEE集成测试",
                List.of(new EquipmentDtos.SaveAttributeValueRequest(1L, "12")),
                List.of(),
                null
        ));
    }

    private OeeDtos.SaveLossReasonRequest lossReasonRequest(
            long parentId,
            String code,
            String name,
            Integer version
    ) {
        return new OeeDtos.SaveLossReasonRequest(
                parentId,
                code,
                name,
                "OTHER",
                "AVAILABILITY",
                false,
                "#409EFF",
                100,
                1,
                "集成测试",
                version
        );
    }

    private void authenticate(Set<String> permissions) {
        CurrentUser user = new CurrentUser(
                ADMIN_ID,
                1L,
                "oee_admin_it",
                "OEE集成管理员",
                true,
                Set.of("ADMIN"),
                permissions,
                "oee-it-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }

    private Set<String> allOeePermissions() {
        return Set.of(
                "oee:record:view",
                "oee:record:manage",
                "oee:record:approve",
                "oee:record:lock",
                "oee:record:recalculate",
                "oee:output:manage",
                "oee:downtime:manage",
                "oee:analysis:view"
        );
    }
}
