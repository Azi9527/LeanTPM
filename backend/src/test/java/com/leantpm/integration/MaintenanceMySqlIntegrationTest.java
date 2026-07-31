package com.leantpm.integration;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.maintenance.MaintenanceCatalogService;
import com.leantpm.maintenance.MaintenanceDtos;
import com.leantpm.maintenance.MaintenanceTaskService;
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
import java.time.LocalTime;
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
class MaintenanceMySqlIntegrationTest {
    private static final long ADMIN_ID = 9201L;
    private static final long OPERATOR_ID = 9202L;
    private static final long COLLABORATOR_ID = 9203L;

    @Autowired
    private EquipmentService equipmentService;

    @Autowired
    private MaintenanceCatalogService catalogService;

    @Autowired
    private MaintenanceTaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareUsers() {
        insertUser(ADMIN_ID, "maintenance_admin_it", "维保集成管理员", 4L, 1L);
        insertUser(OPERATOR_ID, "maintenance_executor_it", "维保执行人", 4L, 3L);
        insertUser(COLLABORATOR_ID, "maintenance_collab_it", "维保协同人", 5L, 3L);
        authenticateAdmin();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void completesAssignmentCollaborationPauseMaterialPhotoConfirmationAndRestore() {
        long equipmentId = createEquipment("EQ-MAINTENANCE-IT-001");
        long itemId = catalogService.createItem(new MaintenanceDtos.SaveItemRequest(
                "MNT-IT-SPINDLE",
                "主轴保养集成项目",
                "TRANSMISSION",
                "主轴传动",
                "清洁主轴组件并测量皮带张力",
                "按作业指导书执行",
                "张力计",
                "张力保持在 45～55 N",
                null,
                new BigDecimal("45"),
                new BigDecimal("55"),
                "N",
                "NUMBER",
                List.of(),
                true,
                true,
                true,
                true,
                false,
                true,
                "HIGH",
                "超差时更换皮带并复测",
                30,
                "执行上锁挂牌",
                true,
                "维保集成测试项目",
                null
        ));

        long schemeId = catalogService.createScheme(schemeRequest(
                "MPS-IT-DAILY",
                "维保闭环集成方案",
                "DAILY",
                null,
                equipmentId,
                itemId
        ));
        MaintenanceDtos.SchemeDetail draft = catalogService.scheme(schemeId, null);
        catalogService.publish(schemeId, draft.version().id());

        MaintenanceDtos.GenerationResult generated =
                taskService.generate(1L, ADMIN_ID, LocalDate.now());
        assertThat(generated.generatedTasks()).isEqualTo(1);

        MaintenanceDtos.TaskRow task = taskService.tasks(
                generated.taskCodes().getFirst(), null, null, false, 1, 20
        ).records().getFirst();
        assertThat(task.taskStatus()).isEqualTo("PENDING");

        taskService.replaceCollaborators(
                task.id(),
                new MaintenanceDtos.CollaboratorRequest(
                        List.of(COLLABORATOR_ID), task.version()
                )
        );

        authenticateCollaborator();
        MaintenanceDtos.TaskDetail detail = taskService.detail(task.id());
        assertThat(detail.collaborators()).singleElement()
                .extracting(MaintenanceDtos.CollaboratorRow::userId)
                .isEqualTo(COLLABORATOR_ID);

        taskService.start(
                task.id(),
                new MaintenanceDtos.TaskActionRequest(
                        "协同人员开始保养", detail.task().version()
                )
        );
        assertThat(currentEquipmentStatus(equipmentId)).isEqualTo("MAINTENANCE");
        assertThatThrownBy(() -> taskService.start(
                task.id(),
                new MaintenanceDtos.TaskActionRequest(
                        null, taskService.detail(task.id()).task().version()
                )
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许从");

        detail = taskService.detail(task.id());
        taskService.pause(
                task.id(),
                new MaintenanceDtos.PauseTaskRequest(
                        "等待备件到位", detail.task().version()
                )
        );
        jdbc.update(
                """
                UPDATE maintenance_task
                SET paused_time = DATE_SUB(paused_time, INTERVAL 120 SECOND)
                WHERE id = ?
                """,
                task.id()
        );
        jdbc.update(
                """
                UPDATE maintenance_task_pause
                SET paused_time = DATE_SUB(paused_time, INTERVAL 120 SECOND)
                WHERE task_id = ? AND resumed_time IS NULL
                """,
                task.id()
        );
        detail = taskService.detail(task.id());
        taskService.resume(
                task.id(),
                new MaintenanceDtos.TaskActionRequest(
                        "备件已到位", detail.task().version()
                )
        );

        taskService.saveMaterial(
                task.id(),
                new MaintenanceDtos.MaterialUsageRequest(
                        null,
                        "SP-BELT-001",
                        "主轴皮带",
                        "A-42",
                        new BigDecimal("2"),
                        "条",
                        new BigDecimal("35.50"),
                        "BATCH-IT",
                        "集成测试备件",
                        null
                )
        );
        assertThat(taskService.detail(task.id()).materials()).singleElement()
                .extracting(MaintenanceDtos.MaterialUsageRow::totalCost)
                .isEqualTo(new BigDecimal("71.0000"));

        long beforePhoto = insertAttachment("before.jpg");
        long afterPhoto = insertAttachment("after.jpg");
        long report = insertAttachment("report.pdf");
        detail = taskService.detail(task.id());
        long taskItemId = detail.items().getFirst().id();
        taskService.submit(
                task.id(),
                new MaintenanceDtos.SaveTaskResultsRequest(
                        List.of(new MaintenanceDtos.SaveResultRequest(
                                taskItemId,
                                null,
                                new BigDecimal("60"),
                                null,
                                null,
                                List.of(),
                                true,
                                "皮带张力超出上限，已更换并复测",
                                false,
                                null,
                                List.of(beforePhoto),
                                List.of(afterPhoto),
                                List.of(report),
                                null
                        )),
                        "维保执行完成",
                        detail.task().version()
                )
        );

        detail = taskService.detail(task.id());
        assertThat(detail.task().taskStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(detail.task().totalPausedSeconds()).isGreaterThanOrEqualTo(120);
        assertThat(detail.pauses()).singleElement()
                .extracting(MaintenanceDtos.PauseRow::durationSeconds)
                .satisfies(value -> assertThat((Long) value).isGreaterThanOrEqualTo(120));
        assertThat(detail.abnormalities()).hasSize(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM maintenance_attachment
                WHERE task_id = ?
                  AND attachment_type IN ('BEFORE_PHOTO','AFTER_PHOTO','RESULT_ATTACHMENT')
                """,
                Integer.class,
                task.id()
        )).isEqualTo(3);

        authenticateAdmin();
        taskService.review(
                task.id(),
                new MaintenanceDtos.ReviewTaskRequest(
                        true, "现场确认通过", detail.task().version()
                )
        );

        MaintenanceDtos.TaskDetail completed = taskService.detail(task.id());
        assertThat(completed.task().taskStatus()).isEqualTo("COMPLETED");
        assertThat(completed.task().confirmedTime()).isNotNull();
        assertThat(equipmentService.detail(equipmentId).equipment().currentStatusCode())
                .isEqualTo("IDLE");
        assertThat(completed.items().getFirst().maintenanceStandard())
                .isEqualTo("张力保持在 45～55 N");
    }

    @Test
    void generatesMeterTaskOnceAndAdvancesThreshold() {
        long equipmentId = createEquipment("EQ-MAINTENANCE-METER-IT");
        long schemeId = catalogService.createScheme(schemeRequest(
                "MPS-IT-HOURS",
                "运行小时触发方案",
                "RUNNING_HOURS",
                new BigDecimal("100"),
                equipmentId,
                1L
        ));
        MaintenanceDtos.SchemeDetail draft = catalogService.scheme(schemeId, null);
        catalogService.publish(schemeId, draft.version().id());

        MaintenanceDtos.PlanRow plan = catalogService.plans(
                "MPS-IT-HOURS", "ACTIVE", 1, 20
        ).records().getFirst();
        catalogService.updatePlanMeter(
                plan.id(),
                new MaintenanceDtos.UpdateMeterRequest(
                        new BigDecimal("100"), plan.version()
                )
        );

        MaintenanceDtos.GenerationResult first =
                taskService.generate(1L, ADMIN_ID, LocalDate.now());
        MaintenanceDtos.GenerationResult second =
                taskService.generate(1L, ADMIN_ID, LocalDate.now());
        assertThat(first.generatedTasks()).isEqualTo(1);
        assertThat(second.generatedTasks()).isZero();
        assertThat(catalogService.plans(
                "MPS-IT-HOURS", "ACTIVE", 1, 20
        ).records().getFirst().nextTriggerValue()).isEqualByComparingTo("200");
    }

    private MaintenanceDtos.SaveSchemeRequest schemeRequest(
            String code,
            String name,
            String cycleType,
            BigDecimal threshold,
            long equipmentId,
            long itemId
    ) {
        return new MaintenanceDtos.SaveSchemeRequest(
                code,
                name,
                "LEVEL_1",
                cycleType,
                1,
                threshold,
                null,
                null,
                LocalTime.of(8, 0),
                3,
                7,
                null,
                OPERATOR_ID,
                "MAINTENANCE-TEAM",
                true,
                true,
                true,
                "IDLE",
                LocalDate.now(),
                null,
                List.of(new MaintenanceDtos.SaveSchemeItemRequest(
                        itemId, 10, true, true, true, false, true
                )),
                List.of(),
                List.of(equipmentId),
                true,
                "维保集成测试方案",
                "初始版本",
                null
        );
    }

    private long createEquipment(String code) {
        return equipmentService.create(new EquipmentDtos.SaveEquipmentRequest(
                code,
                "维保集成测试设备",
                3L,
                "CNC-IT",
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
                "维保集成测试",
                List.of(new EquipmentDtos.SaveAttributeValueRequest(1L, "12")),
                List.of(),
                null
        ));
    }

    private long insertAttachment(String name) {
        jdbc.update(
                """
                INSERT INTO system_attachment
                    (tenant_id, original_name, stored_name, storage_path,
                     content_type, extension, file_size, sha256, created_by, updated_by)
                VALUES
                    (1, ?, ?, ?, 'application/octet-stream', 'dat', 1,
                     SHA2(CONCAT(?, UUID()), 256), ?, ?)
                """,
                name, name, "integration/" + name, name, COLLABORATOR_ID, COLLABORATOR_ID
        );
        return jdbc.queryForObject(
                "SELECT id FROM system_attachment WHERE tenant_id = 1 AND original_name = ?",
                Long.class,
                name
        );
    }

    private String currentEquipmentStatus(long equipmentId) {
        return jdbc.queryForObject(
                """
                SELECT status_code FROM equipment_current_status
                WHERE tenant_id = 1 AND equipment_id = ?
                """,
                String.class,
                equipmentId
        );
    }

    private void insertUser(
            long id,
            String username,
            String realName,
            long organizationId,
            long roleId
    ) {
        jdbc.update(
                """
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, ?, 'not-used', ?, ?, 1, 0)
                """,
                id, username, realName, organizationId
        );
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, ?)",
                id, roleId
        );
    }

    private void authenticateAdmin() {
        authenticate(
                ADMIN_ID,
                "maintenance_admin_it",
                "维保集成管理员",
                Set.of("ADMIN"),
                Set.of(
                        "maintenance:item:view",
                        "maintenance:item:manage",
                        "maintenance:scheme:view",
                        "maintenance:scheme:manage",
                        "maintenance:scheme:publish",
                        "maintenance:plan:view",
                        "maintenance:plan:generate",
                        "maintenance:plan:meter",
                        "maintenance:task:view",
                        "maintenance:task:assign",
                        "maintenance:task:collaborate",
                        "maintenance:task:execute",
                        "maintenance:task:confirm"
                )
        );
    }

    private void authenticateCollaborator() {
        authenticate(
                COLLABORATOR_ID,
                "maintenance_collab_it",
                "维保协同人",
                Set.of("OPERATOR"),
                Set.of(
                        "maintenance:my-task:view",
                        "maintenance:task:view",
                        "maintenance:task:execute"
                )
        );
    }

    private void authenticate(
            long userId,
            String username,
            String realName,
            Set<String> roles,
            Set<String> permissions
    ) {
        CurrentUser user = new CurrentUser(
                userId,
                1L,
                username,
                realName,
                false,
                roles,
                permissions,
                "maintenance-it-session-" + userId
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }
}
