package com.leantpm.integration;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.fault.FaultDtos;
import com.leantpm.fault.FaultService;
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
                "leantpm.bootstrap.admin-password=",
                "leantpm.notification.initial-delay-ms=3600000"
        }
)
@DirtiesContext
@Transactional
class FaultRepairMySqlIntegrationTest {
    @Autowired
    private FaultService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long equipmentId;
    private long operatorId;

    @BeforeEach
    void prepare() {
        equipmentId = jdbc.queryForObject(
                "SELECT id FROM equipment WHERE tenant_id = 1 AND equipment_code = 'VIZ-PUMP-01'",
                Long.class
        );
        operatorId = jdbc.queryForObject(
                "SELECT id FROM system_user WHERE tenant_id = 1 AND username = 'operator01'",
                Long.class
        );
        authenticateAdmin();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void completesReportRepairPauseMaterialAndAcceptanceWorkflow() {
        long reportId = service.createReport(new FaultDtos.CreateReportRequest(
                equipmentId, LocalDateTime.now(), "主轴异响",
                "主轴运行期间出现持续异响，需要停机检查", "HIGH", List.of()
        ));
        FaultDtos.ReportRow reported = service.report(reportId);
        assertThat(reported.reportStatus()).isEqualTo("REPORTED");
        assertThat(status()).isEqualTo("FAULT");

        service.acceptReport(reportId, new FaultDtos.VersionRequest(reported.version()));
        FaultDtos.ReportRow accepted = service.report(reportId);
        long repairId = service.createRepair(reportId, new FaultDtos.CreateRepairRequest(
                null, List.of(), "IDLE", accepted.version()
        ));
        FaultDtos.RepairRow pending = service.repair(repairId);
        service.assign(repairId, new FaultDtos.AssignmentRequest(
                operatorId, List.of(), pending.version()
        ));
        FaultDtos.RepairRow assigned = service.repair(repairId);
        authenticateOperator();
        service.start(repairId, new FaultDtos.ActionRequest("开始拆检", assigned.version()));
        assertThat(status()).isEqualTo("REPAIR");

        FaultDtos.RepairRow started = service.repair(repairId);
        assertThatThrownBy(() -> service.start(
                repairId, new FaultDtos.ActionRequest("重复开始", started.version())
        )).isInstanceOf(BusinessException.class);
        service.pause(repairId, new FaultDtos.ActionRequest("等待备件", started.version()));
        FaultDtos.RepairRow paused = service.repair(repairId);
        service.resume(repairId, new FaultDtos.ActionRequest("备件到货", paused.version()));
        FaultDtos.RepairRow resumed = service.repair(repairId);
        long materialId = service.addMaterial(repairId, new FaultDtos.SaveMaterialRequest(
                "BRG-001", "主轴轴承", new BigDecimal("2"), "件",
                new BigDecimal("128.50"), "更换损坏轴承", null
        ));
        service.updateMaterial(repairId, materialId, new FaultDtos.SaveMaterialRequest(
                "BRG-001", "主轴轴承", new BigDecimal("2"), "件",
                new BigDecimal("128.50"), "更换并涂抹润滑脂", 0
        ));
        long disposableMaterialId = service.addMaterial(repairId, new FaultDtos.SaveMaterialRequest(
                null, "临时材料", BigDecimal.ONE, "件", BigDecimal.ONE, null, null
        ));
        service.deleteMaterial(repairId, disposableMaterialId, 0);
        service.complete(repairId, new FaultDtos.CompleteRequest(
                "更换两只主轴轴承并重新校正", "空载与负载试机无异响",
                List.of(), resumed.version()
        ));
        FaultDtos.RepairRow waitingAcceptance = service.repair(repairId);
        authenticateAdmin();
        service.acceptance(repairId, new FaultDtos.AcceptanceRequest(
                true, "验收通过", "IDLE", List.of(), waitingAcceptance.version()
        ));

        FaultDtos.RepairRow closed = service.repair(repairId);
        assertThat(closed.repairStatus()).isEqualTo("CLOSED");
        assertThat(service.report(reportId).reportStatus()).isEqualTo("CLOSED");
        assertThat(status()).isEqualTo("IDLE");
        assertThat(service.materials(repairId)).singleElement()
                .satisfies(material -> assertThat(material.totalAmount())
                        .isEqualByComparingTo("257.0000"));
        assertThat(service.events(repairId).stream().map(FaultDtos.EventRow::eventType))
                .containsExactly("CREATE", "ASSIGN", "START", "PAUSE", "RESUME", "COMPLETE", "ACCEPTANCE");
    }

    @Test
    void convertsInspectionAbnormalToExactlyOneRepairOrder() {
        long taskId = 9491L;
        long abnormalId = 9492L;
        Long organizationId = jdbc.queryForObject(
                "SELECT organization_id FROM equipment WHERE tenant_id = 1 AND id = ?",
                Long.class, equipmentId
        );
        Long locationId = jdbc.queryForObject(
                "SELECT location_id FROM equipment WHERE tenant_id = 1 AND id = ?",
                Long.class, equipmentId
        );
        jdbc.update("""
                INSERT INTO inspection_task
                    (id, tenant_id, task_code, inspection_type, equipment_id,
                     organization_id, location_id, planned_date, due_time,
                     task_status, source_type, created_by)
                VALUES (?, 1, 'FAULT-IT-TASK', 'ROUTINE', ?, ?, ?, CURRENT_DATE(),
                        CURRENT_TIMESTAMP(3), 'COMPLETED', 'MANUAL', 1)
                """, taskId, equipmentId, organizationId, locationId);
        jdbc.update("""
                INSERT INTO inspection_abnormal
                    (id, tenant_id, abnormal_code, task_id, equipment_id,
                     abnormal_title, abnormal_description, severity, created_by, updated_by)
                VALUES (?, 1, 'FAULT-IT-ABN', ?, ?, '点检发现泄漏',
                        '密封处持续渗油', 'CRITICAL', 1, 1)
                """, abnormalId, taskId, equipmentId);

        long first = service.createFromInspectionAbnormal(abnormalId);
        long repeated = service.createFromInspectionAbnormal(abnormalId);

        assertThat(repeated).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment_repair_order
                WHERE tenant_id = 1 AND id = ? AND deleted = 0
                """, Long.class, first)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT repair_order_id FROM inspection_abnormal
                WHERE tenant_id = 1 AND id = ?
                """, Long.class, abnormalId)).isEqualTo(first);
        assertThat(service.repair(first).repairStatus()).isEqualTo("PENDING_ASSIGNMENT");
    }

    private String status() {
        return jdbc.queryForObject("""
                SELECT status_code FROM equipment_current_status
                WHERE tenant_id = 1 AND equipment_id = ?
                """, String.class, equipmentId);
    }

    private void authenticateAdmin() {
        CurrentUser admin = new CurrentUser(
                1L, 1L, "admin", "系统管理员", false,
                Set.of("ADMIN"), Set.of(
                "fault:report:view", "fault:report:create", "fault:report:accept",
                "fault:repair:view", "fault:repair:create", "fault:repair:assign",
                "fault:repair:execute", "fault:repair:accept", "fault:material:manage"
        ), "fault-repair-it");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, Set.of())
        );
    }

    private void authenticateOperator() {
        CurrentUser operator = new CurrentUser(
                operatorId, 1L, "operator01", "操作工01", false,
                Set.of("OPERATOR"), Set.of(
                "fault:repair:view", "fault:repair:execute", "fault:material:manage"
        ), "fault-repair-operator-it");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null, Set.of())
        );
    }
}
