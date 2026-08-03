package com.leantpm.integration;

import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.inspection.InspectionCatalogService;
import com.leantpm.inspection.InspectionCalendarDtos;
import com.leantpm.inspection.InspectionCalendarService;
import com.leantpm.inspection.InspectionDtos;
import com.leantpm.inspection.InspectionImportDtos;
import com.leantpm.inspection.InspectionImportService;
import com.leantpm.inspection.InspectionTaskService;
import com.leantpm.security.CurrentUser;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
class InspectionMySqlIntegrationTest {
    private static final long USER_ID = 9101L;
    private static final long OPERATOR_ID = 9102L;
    private static final long COLLABORATOR_ID = 9103L;

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private InspectionCatalogService catalogService;

    @Autowired
    private InspectionCalendarService calendarService;

    @Autowired
    private InspectionTaskService taskService;

    @Autowired
    private InspectionImportService importService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareUserAndAuthentication() {
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'inspection_it', 'not-used', '点检集成测试', 4, 1, 0)
                """, USER_ID);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 1)",
                USER_ID
        );
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'inspection_operator_it', 'not-used',
                        '点检执行人集成测试', 5, 1, 0)
                """, OPERATOR_ID);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 3)",
                OPERATOR_ID
        );
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'inspection_collaborator_it', 'not-used',
                        '点检协同人集成测试', 5, 1, 0)
                """, COLLABORATOR_ID);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 3)",
                COLLABORATOR_ID
        );
        authenticateAdmin();
    }

    private void authenticateAdmin() {
        CurrentUser user = new CurrentUser(
                USER_ID,
                1L,
                "inspection_it",
                "点检集成测试",
                false,
                Set.of("ADMIN"),
                Set.of(
                        "inspection:item:view",
                        "inspection:scheme:view",
                        "inspection:scheme:manage",
                        "inspection:scheme:publish",
                        "inspection:calendar:view",
                        "inspection:calendar:manage",
                        "inspection:plan:view",
                        "inspection:plan:manage",
                        "inspection:plan:generate",
                        "inspection:task:view",
                        "inspection:task:execute",
                        "inspection:task:review",
                        "inspection:task:assign",
                        "inspection:abnormal:view",
                        "inspection:abnormal:handle",
                        "inspection:abnormal:verify"
                ),
                "inspection-it-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }

    private void authenticateOperator() {
        authenticateFieldUser(
                OPERATOR_ID, "inspection_operator_it", "点检执行人集成测试"
        );
    }

    private void authenticateCollaborator() {
        authenticateFieldUser(
                COLLABORATOR_ID, "inspection_collaborator_it", "点检协同人集成测试"
        );
    }

    private void authenticateFieldUser(long id, String username, String realName) {
        CurrentUser user = new CurrentUser(
                id,
                1L,
                username,
                realName,
                false,
                Set.of("OPERATOR"),
                Set.of(
                        "inspection:my-task:view",
                        "inspection:task:view",
                        "inspection:task:execute",
                        "inspection:abnormal:view",
                        "inspection:abnormal:handle"
                ),
                "inspection-operator-it-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private long createEquipment(String code, String name, String serialNumber) {
        return equipmentService.create(new EquipmentDtos.SaveEquipmentRequest(
                code,
                name,
                3L,
                "CNC-IT",
                null,
                "LeanTPM",
                "Integration",
                serialNumber,
                null,
                null,
                4L,
                4L,
                USER_ID,
                null,
                "IN_SERVICE",
                true,
                false,
                true,
                true,
                "点检集成测试",
                List.of(new EquipmentDtos.SaveAttributeValueRequest(1L, "12")),
                List.of(),
                null
        ));
    }

    @Test
    void completesPublishedPlanTaskReviewAndAbnormalLifecycle() {
        long equipmentId = createEquipment(
                "EQ-INSPECTION-IT-001",
                "点检集成测试设备",
                "INSPECTION-SN-001"
        );

        long schemeId = catalogService.createScheme(new InspectionDtos.SaveSchemeRequest(
                "ISP-IT-CNC",
                "点检集成测试方案",
                "DAILY",
                "DAILY",
                1,
                null,
                null,
                LocalTime.of(8, 0),
                60,
                1L,
                null,
                OPERATOR_ID,
                null,
                true,
                true,
                LocalDate.now(),
                null,
                List.of(
                        new InspectionDtos.SaveSchemeItemRequest(1L, 10, true, false, false),
                        new InspectionDtos.SaveSchemeItemRequest(2L, 20, true, false, false),
                        new InspectionDtos.SaveSchemeItemRequest(3L, 30, true, false, false)
                ),
                List.of(1L),
                List.of(),
                true,
                "集成测试方案",
                "初始版本",
                null
        ));
        InspectionDtos.SchemeDetail draft = catalogService.scheme(schemeId, null);
        assertThat(draft.version().versionStatus()).isEqualTo("DRAFT");

        catalogService.publish(schemeId, draft.version().id());
        assertThat(catalogService.plans(null, "ACTIVE", 1, 20).records())
                .singleElement()
                .extracting(InspectionDtos.PlanRow::equipmentId)
                .isEqualTo(equipmentId);

        InspectionDtos.GenerationResult generated =
                taskService.generate(1L, USER_ID, LocalDate.now());
        assertThat(generated.generatedTasks()).isEqualTo(1);
        assertThat(generated.taskCodes()).hasSize(1);

        InspectionDtos.TaskRow generatedTask = taskService.tasks(
                generated.taskCodes().getFirst(), null, null, false, 1, 20
        ).records().getFirst();
        taskService.assign(
                generatedTask.id(),
                new InspectionDtos.AssignTaskRequest(
                        List.of(OPERATOR_ID, COLLABORATOR_ID),
                        null,
                        generatedTask.version()
                )
        );
        InspectionDtos.TaskRow assignedTask = taskService.detail(generatedTask.id()).task();
        assertThat(assignedTask.assigneeName())
                .isEqualTo("点检执行人集成测试、点检协同人集成测试");
        assertThat(assignedTask.assigneeUserIdsCsv())
                .isEqualTo(OPERATOR_ID + "," + COLLABORATOR_ID);

        authenticateCollaborator();
        assertThat(taskService.tasks(
                generated.taskCodes().getFirst(), null, null, true, 1, 20
        ).records()).singleElement();

        authenticateOperator();
        InspectionDtos.TaskRow task = taskService.tasks(
                generated.taskCodes().getFirst(), null, null, true, 1, 20
        ).records().getFirst();
        InspectionDtos.TaskDetail detail = taskService.detail(task.id());
        assertThat(detail.items()).hasSize(3);
        assertThat(detail.items())
                .extracting(InspectionDtos.TaskItemRow::sourceItemId)
                .containsExactly(1L, 2L, 3L);

        List<InspectionDtos.SaveResultRequest> results = detail.items().stream()
                .map(item -> switch (item.resultType()) {
                    case "NUMBER" -> result(
                            item.id(), null, new BigDecimal("55"), false, null
                    );
                    case "PASS_FAIL" -> result(
                            item.id(), "PASS", null, false, null
                    );
                    default -> result(
                            item.id(), "ABNORMAL", null, true, "主轴存在异常振动"
                    );
                })
                .toList();

        taskService.submit(
                task.id(),
                new InspectionDtos.SaveTaskResultsRequest(
                        results, "完成现场点检", task.version()
                )
        );
        InspectionDtos.TaskDetail submitted = taskService.detail(task.id());
        assertThat(submitted.task().taskStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(submitted.abnormalities()).singleElement()
                .extracting(InspectionDtos.AbnormalRow::abnormalStatus)
                .isEqualTo("OPEN");

        authenticateAdmin();
        taskService.review(
                task.id(),
                new InspectionDtos.ReviewTaskRequest(
                        true, "复核通过", submitted.task().version()
                )
        );
        assertThat(taskService.detail(task.id()).task().taskStatus()).isEqualTo("COMPLETED");

        InspectionDtos.AbnormalRow abnormal =
                taskService.abnormalities(null, "OPEN", 1, 20).records().getFirst();
        taskService.handleAbnormal(
                abnormal.id(),
                new InspectionDtos.HandleAbnormalRequest(
                        USER_ID,
                        LocalDateTime.now().plusDays(1),
                        "暂停设备并检查紧固件",
                        "紧固主轴组件后试运行正常",
                        "IDLE",
                        "PENDING_VERIFY",
                        abnormal.version()
                )
        );
        InspectionDtos.AbnormalRow pending =
                taskService.abnormalities(null, "PENDING_VERIFY", 1, 20)
                        .records().getFirst();
        taskService.verifyAbnormal(
                pending.id(),
                new InspectionDtos.VerifyAbnormalRequest(
                        true, "现场验证通过", pending.version()
                )
        );

        assertThat(taskService.abnormalities(null, "CLOSED", 1, 20).records())
                .singleElement()
                .extracting(InspectionDtos.AbnormalRow::verificationComment)
                .isEqualTo("现场验证通过");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM inspection_task_item item
                JOIN inspection_task task
                  ON task.tenant_id = item.tenant_id AND task.id = item.task_id
                WHERE task.id = ? AND item.source_item_id IS NOT NULL
                """,
                Integer.class,
                task.id()
        )).isEqualTo(3);

        InspectionDtos.TaskQuery resultQuery = new InspectionDtos.TaskQuery(
                generated.taskCodes().getFirst(),
                "COMPLETED",
                null,
                "COMPLETED_TIME",
                LocalDate.now(),
                LocalDate.now(),
                4L,
                null,
                OPERATOR_ID,
                equipmentId,
                schemeId,
                true,
                "HIGH",
                false
        );
        assertThat(taskService.tasks(resultQuery, 1, 20).records())
                .singleElement()
                .extracting(InspectionDtos.TaskRow::taskCode)
                .isEqualTo(generated.taskCodes().getFirst());

        byte[] workbookBytes = taskService.exportResults(resultQuery);
        assertThat(workbookBytes).isNotEmpty();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            assertThat(workbook.getSheet("任务汇总").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("逐项结果").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheet("异常记录").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("附件索引").getLastRowNum()).isZero();
            assertThat(workbook.getSheet("逐项结果").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo(generated.taskCodes().getFirst());
        } catch (Exception exception) {
            throw new AssertionError("导出的点检结果工作簿无法读取", exception);
        }

        long manualEquipmentOne = createEquipment(
                "EQ-INSPECTION-MANUAL-001",
                "手工计划设备一",
                "INSPECTION-MANUAL-SN-001"
        );
        long manualEquipmentTwo = createEquipment(
                "EQ-INSPECTION-MANUAL-002",
                "手工计划设备二",
                "INSPECTION-MANUAL-SN-002"
        );
        InspectionDtos.CreatePlansResult manualResult = catalogService.createPlans(
                new InspectionDtos.CreatePlansRequest(
                        schemeId, List.of(manualEquipmentOne, manualEquipmentTwo)
                )
        );
        assertThat(manualResult.processedPlans()).isEqualTo(2);
        assertThat(manualResult.nextGenerationDate()).isEqualTo(LocalDate.now());
        assertThat(catalogService.plans(
                "EQ-INSPECTION-MANUAL", "ACTIVE", 1, 20
        ).records())
                .extracting(InspectionDtos.PlanRow::equipmentId)
                .containsExactlyInAnyOrder(manualEquipmentOne, manualEquipmentTwo);
    }

    @Test
    void validatesAndIdempotentlyCommitsInspectionWorkbook() {
        byte[] template = importService.template();
        var file = new MockMultipartFile(
                "file",
                "inspection-import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                template
        );

        InspectionImportDtos.ImportResult validated = importService.validate(file);
        assertThat(validated.status()).isEqualTo("VALIDATED");
        assertThat(validated.errors()).isEmpty();
        assertThat(validated.itemRows()).isEqualTo(1);
        assertThat(validated.schemeRows()).isEqualTo(1);
        assertThat(validated.relationRows()).isEqualTo(2);

        try (var invalidWorkbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             var invalidBytes = new ByteArrayOutputStream()) {
            invalidWorkbook.getSheet("适用设备").getRow(1).getCell(1)
                    .setCellValue("EQUIPMENT-NOT-FOUND");
            invalidWorkbook.write(invalidBytes);
            InspectionImportDtos.ImportResult invalid = importService.validate(
                    new MockMultipartFile(
                            "file", "inspection-invalid.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            invalidBytes.toByteArray()
                    )
            );
            assertThat(invalid.status()).isEqualTo("INVALID");
            assertThat(invalid.errors()).anySatisfy(error -> {
                assertThat(error.sheet()).isEqualTo("适用设备");
                assertThat(error.rowNumber()).isEqualTo(2);
                assertThat(error.column()).isEqualTo("设备编码");
            });
        } catch (Exception exception) {
            throw new AssertionError("无法构造点检导入错误样例", exception);
        }

        InspectionImportDtos.ImportResult committed = importService.commit(
                validated.batchId()
        );
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.newItems()).isEqualTo(1);
        assertThat(committed.newSchemes()).isEqualTo(1);
        assertThat(committed.committedTime()).isNotNull();

        InspectionImportDtos.ImportResult repeated = importService.commit(
                validated.batchId()
        );
        assertThat(repeated.status()).isEqualTo("COMMITTED");
        assertThat(repeated.committedTime()).isEqualTo(committed.committedTime());
        assertThat(catalogService.items(
                "IMP-LUB-001", null, null, null, 1, 20
        ).records())
                .singleElement()
                .extracting(InspectionDtos.ItemRow::itemName)
                .isEqualTo("润滑油液位");
        InspectionDtos.SchemeRow importedScheme = catalogService.schemes(
                "IMP-SCHEME-001", null, null, 1, 20
        ).records().getFirst();
        assertThat(catalogService.scheme(importedScheme.id(), null).version().versionStatus())
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_import_batch WHERE batch_code = ?",
                Integer.class,
                validated.batchId()
        )).isEqualTo(1);
    }

    @Test
    void managesWorkCalendarExceptionsAndFreezesSchemeGenerationPolicy() {
        assertThat(calendarService.calendars(null, 1))
                .anySatisfy(calendar -> assertThat(calendar.defaultFlag()).isTrue());

        long calendarId = calendarService.create(
                new InspectionCalendarDtos.SaveCalendarRequest(
                        "Integration six-day calendar",
                        "6,1,2,2,3,4,5",
                        false,
                        1,
                        "Integration calendar",
                        null
                )
        );
        InspectionCalendarDtos.CalendarDetail created = calendarService.detail(calendarId);
        assertThat(created.calendar().workDays()).isEqualTo("1,2,3,4,5,6");

        long exceptionId = calendarService.createException(
                calendarId,
                new InspectionCalendarDtos.SaveExceptionRequest(
                        "National Day",
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 7),
                        "RESTDAY",
                        200,
                        1,
                        "Factory holiday",
                        null
                )
        );
        InspectionCalendarDtos.CalendarDetail detail = calendarService.detail(calendarId);
        assertThat(detail.exceptions()).singleElement().satisfies(exception -> {
            assertThat(exception.id()).isEqualTo(exceptionId);
            assertThat(exception.dayType()).isEqualTo("RESTDAY");
            assertThat(exception.startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(exception.endDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        });

        long equipmentId = createEquipment(
                "EQ-INSPECTION-CALENDAR-001",
                "Calendar policy equipment",
                "INSPECTION-CALENDAR-SN-001"
        );
        long schemeId = catalogService.createScheme(new InspectionDtos.SaveSchemeRequest(
                "ISP-IT-CALENDAR",
                "Calendar policy scheme",
                "DAILY",
                "DAILY",
                1,
                null,
                null,
                LocalTime.of(7, 30),
                180,
                calendarId,
                null,
                OPERATOR_ID,
                null,
                false,
                true,
                LocalDate.now(),
                null,
                List.of(new InspectionDtos.SaveSchemeItemRequest(
                        1L, 10, true, false, false
                )),
                List.of(),
                List.of(equipmentId),
                true,
                "Calendar policy integration test",
                "Initial version",
                null
        ));
        InspectionDtos.SchemeDetail scheme = catalogService.scheme(schemeId, null);
        catalogService.publish(schemeId, scheme.version().id());

        assertThat(catalogService.plans(
                "EQ-INSPECTION-CALENDAR-001", "ACTIVE", 1, 20
        ).records()).singleElement().satisfies(plan -> {
            assertThat(plan.equipmentId()).isEqualTo(equipmentId);
            assertThat(plan.generationLeadMinutes()).isEqualTo(180);
            assertThat(plan.workCalendarId()).isEqualTo(calendarId);
        });
    }

    private InspectionDtos.SaveResultRequest result(
            long itemId,
            String resultCode,
            BigDecimal numericValue,
            boolean abnormal,
            String abnormalDescription
    ) {
        return new InspectionDtos.SaveResultRequest(
                itemId,
                resultCode,
                numericValue,
                null,
                null,
                List.of(),
                abnormal,
                abnormalDescription,
                false,
                null,
                List.of(),
                null
        );
    }
}
