package com.leantpm.integration;

import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.inspection.InspectionCatalogService;
import com.leantpm.inspection.InspectionDtos;
import com.leantpm.inspection.InspectionTaskService;
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

import java.math.BigDecimal;
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

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private InspectionCatalogService catalogService;

    @Autowired
    private InspectionTaskService taskService;

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
        authenticateAdmin();
    }

    private void authenticateAdmin() {
        CurrentUser user = new CurrentUser(
                USER_ID,
                1L,
                "inspection_it",
                "点检集成测试",
                false,
                Set.of("SUPER_ADMIN"),
                Set.of(
                        "inspection:item:view",
                        "inspection:scheme:view",
                        "inspection:scheme:manage",
                        "inspection:scheme:publish",
                        "inspection:plan:view",
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
        CurrentUser user = new CurrentUser(
                OPERATOR_ID,
                1L,
                "inspection_operator_it",
                "点检执行人集成测试",
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

    @Test
    void completesPublishedPlanTaskReviewAndAbnormalLifecycle() {
        long equipmentId = equipmentService.create(new EquipmentDtos.SaveEquipmentRequest(
                "EQ-INSPECTION-IT-001",
                "点检集成测试设备",
                3L,
                "CNC-IT",
                null,
                "LeanTPM",
                "Integration",
                "INSPECTION-SN-001",
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

        long schemeId = catalogService.createScheme(new InspectionDtos.SaveSchemeRequest(
                "ISP-IT-CNC",
                "点检集成测试方案",
                "DAILY",
                "DAILY",
                1,
                null,
                null,
                LocalTime.of(8, 0),
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
