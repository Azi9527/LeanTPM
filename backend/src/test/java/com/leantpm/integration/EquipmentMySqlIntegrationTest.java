package com.leantpm.integration;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermissionService;
import org.apache.ibatis.session.SqlSession;
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
import java.util.List;
import java.util.Map;
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
                "leantpm.security.jwt-secret=integration-test-secret-at-least-32-characters",
                "leantpm.bootstrap.admin-password="
        }
)
@DirtiesContext
@Transactional
class EquipmentMySqlIntegrationTest {
    private static final long USER_ID = 9001L;
    private static final long RESTRICTED_USER_ID = 9002L;
    private static final long SAME_ORGANIZATION_USER_ID = 9003L;

    @Autowired
    private EquipmentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataPermissionService dataPermissionService;

    @Autowired
    private SqlSession sqlSession;

    @BeforeEach
    void prepareUserAndAuthentication() {
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'equipment_it', 'not-used', '设备集成测试', 4, 1, 0)
                """, USER_ID);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 1)",
                USER_ID
        );
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'equipment_reader_it', 'not-used', '普通设备查看人', 4, 1, 0)
                """, RESTRICTED_USER_ID);
        jdbc.update("""
                INSERT INTO system_user_role (tenant_id, user_id, role_id)
                SELECT 1, ?, id
                FROM system_role
                WHERE tenant_id = 1 AND role_code = 'OPERATOR' AND deleted = 0
                """, RESTRICTED_USER_ID);
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'equipment_team_leader_it', 'not-used',
                        '同组织设备查看人', 5, 1, 0)
                """, SAME_ORGANIZATION_USER_ID);
        jdbc.update("""
                INSERT INTO system_user_role (tenant_id, user_id, role_id)
                SELECT 1, ?, id
                FROM system_role
                WHERE tenant_id = 1 AND role_code = 'TEAM_LEADER' AND deleted = 0
                """, SAME_ORGANIZATION_USER_ID);
        authenticateAdministrator();
    }

    private void authenticateAdministrator() {
        authenticate(new CurrentUser(
                USER_ID,
                1L,
                "equipment_it",
                "设备集成测试",
                false,
                Set.of("ADMIN"),
                Set.of(
                        "equipment:ledger:view",
                        "equipment:ledger:create",
                        "equipment:ledger:update",
                        "equipment:ledger:transfer",
                        "equipment:status:update",
                        "equipment:barcode:manage"
                ),
                "equipment-it-session"
        ));
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void completesLedgerStatusTransferAndSecureBarcodeLifecycle() {
        long equipmentId = service.create(new EquipmentDtos.SaveEquipmentRequest(
                "EQ-IT-001",
                "集成测试数控设备",
                3L,
                "IT-MODEL",
                "IT-SPEC",
                "LeanTPM",
                "Integration",
                "SN-IT-001",
                null,
                null,
                4L,
                4L,
                USER_ID,
                "ASSET-IT-001",
                "IN_SERVICE",
                true,
                false,
                true,
                true,
                "集成测试事务内数据",
                List.of(new EquipmentDtos.SaveAttributeValueRequest(1L, "11.5")),
                List.of(),
                null
        ));

        EquipmentDtos.EquipmentDetail created = service.detail(equipmentId);
        assertThat(created.equipment().equipmentCode()).isEqualTo("EQ-IT-001");
        assertThat(created.equipment().currentStatusCode()).isEqualTo("IDLE");
        assertThat(created.attributes())
                .filteredOn(value -> value.definitionId() == 1L)
                .extracting(EquipmentDtos.AttributeValueRow::value)
                .containsExactly("11.500000");
        assertThat(created.responsiblePersons())
                .extracting(EquipmentDtos.ResponsiblePersonRow::userId)
                .contains(USER_ID);

        service.changeStatus(
                equipmentId,
                new EquipmentDtos.ChangeStatusRequest(
                        "RUNNING", "集成测试开机", "MANUAL",
                        created.equipment().currentStatusVersion()
                )
        );
        EquipmentDtos.EquipmentDetail running = service.detail(equipmentId);
        assertThat(running.equipment().currentStatusCode()).isEqualTo("RUNNING");
        assertThat(running.statusHistory()).hasSize(2);
        assertThat(service.statusSummary("EQ-IT-001", null, com.leantpm.common.query.TableQuery.empty()))
                .containsEntry("RUNNING", 1L)
                .containsEntry("IDLE", 0L)
                .containsEntry("STOPPED", 0L)
                .containsEntry("SCRAPPED", 0L);

        service.transfer(
                equipmentId,
                new EquipmentDtos.TransferRequest(
                        5L, 5L, USER_ID, "集成测试调拨",
                        running.equipment().version()
                )
        );
        EquipmentDtos.EquipmentDetail transferred = service.detail(equipmentId);
        assertThat(transferred.equipment().organizationId()).isEqualTo(5L);
        assertThat(transferred.equipment().locationId()).isEqualTo(5L);
        assertThat(transferred.transfers()).hasSize(1);

        EquipmentDtos.BarcodeRow first = service.generateBarcode(
                equipmentId,
                new EquipmentDtos.GenerateBarcodeRequest("QR", null),
                false
        );
        assertThat(first.accessToken()).matches("[a-f0-9]{64}");
        assertThat(service.publicView(first.accessToken()).equipmentCode())
                .isEqualTo("EQ-IT-001");
        assertThat(service.barcodeImage(first.id(), 220, 220)).isNotEmpty();

        EquipmentDtos.BarcodeRow replacement = service.generateBarcode(
                equipmentId,
                new EquipmentDtos.GenerateBarcodeRequest("QR", "标签换新"),
                true
        );
        assertThat(replacement.accessToken()).isNotEqualTo(first.accessToken());
        assertThatThrownBy(() -> service.publicView(first.accessToken()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("BARCODE_NOT_FOUND");

        authenticate(new CurrentUser(
                RESTRICTED_USER_ID, 1L, "equipment_reader_it", "普通设备查看人", false,
                Set.of("OPERATOR"),
                Set.of("equipment:ledger:view", "equipment:status:view", "equipment:barcode:view"),
                "equipment-reader-session"
        ));
        assertThat(service.page(
                "EQ-IT-001", null, null, null, null, null, null, 1, 20
        ).records()).isEmpty();
        assertThatThrownBy(() -> service.detail(equipmentId))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("EQUIPMENT_NOT_FOUND");
        assertThatThrownBy(() -> service.changeStatus(
                equipmentId,
                new EquipmentDtos.ChangeStatusRequest(
                        "IDLE", "out-of-scope status change", "MANUAL",
                        transferred.equipment().currentStatusVersion()
                )
        )).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("EQUIPMENT_NOT_FOUND");

        jdbc.update("""
                INSERT INTO system_user_team_membership
                    (tenant_id, user_id, team_organization_id, primary_flag,
                     created_by, updated_by)
                VALUES (1, ?, 5, 0, ?, ?)
                """, RESTRICTED_USER_ID, USER_ID, USER_ID);
        sqlSession.clearCache();
        assertThat(service.detail(equipmentId).equipment().id()).isEqualTo(equipmentId);
        assertThat(service.page(
                "EQ-IT-001", null, null, null, null, null, null, 1, 20
        ).records()).singleElement()
                .extracting(EquipmentDtos.EquipmentRow::id).isEqualTo(equipmentId);

        jdbc.update("""
                UPDATE system_user_team_membership
                SET deleted = 1, updated_by = ?
                WHERE tenant_id = 1 AND user_id = ? AND team_organization_id = 5
                """, USER_ID, RESTRICTED_USER_ID);
        sqlSession.clearCache();
        assertThatThrownBy(() -> service.detail(equipmentId))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("EQUIPMENT_NOT_FOUND");

        jdbc.update(
                "UPDATE system_user SET organization_id = 5 WHERE tenant_id = 1 AND id = ?",
                RESTRICTED_USER_ID
        );
        sqlSession.clearCache();
        assertThat(service.detail(equipmentId).equipment().id()).isEqualTo(equipmentId);
        jdbc.update(
                "UPDATE system_user SET organization_id = 4 WHERE tenant_id = 1 AND id = ?",
                RESTRICTED_USER_ID
        );
        sqlSession.clearCache();

        jdbc.update("""
                INSERT INTO inspection_task
                    (id, tenant_id, task_code, inspection_type, equipment_id,
                     organization_id, location_id, planned_date, due_time,
                     assignee_user_id, task_status, source_type, created_by)
                VALUES (9901, 1, 'EQ-SCOPE-ASSIGNED', 'ROUTINE', ?, 5, 5,
                        CURRENT_DATE(), CURRENT_TIMESTAMP(3), ?, 'PENDING', 'MANUAL', ?)
                """, equipmentId, RESTRICTED_USER_ID, USER_ID);
        // The setup insert bypasses MyBatis, so discard the transaction-local null
        // cached by the preceding out-of-scope detail lookup.
        sqlSession.clearCache();
        assertThat(dataPermissionService.current().selfData()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM equipment equipment_row
                JOIN inspection_task task
                  ON task.tenant_id = equipment_row.tenant_id
                 AND task.equipment_id = equipment_row.id
                 AND task.deleted = 0
                WHERE equipment_row.tenant_id = 1
                  AND equipment_row.id = ?
                  AND task.assignee_user_id = ?
                """, Long.class, equipmentId, RESTRICTED_USER_ID)).isEqualTo(1L);
        assertThat(service.detail(equipmentId).equipment().id()).isEqualTo(equipmentId);
        assertThat(service.page(
                "EQ-IT-001", null, null, null, "RUNNING", null, null, 1, 20
        ).records()).singleElement()
                .extracting(EquipmentDtos.EquipmentRow::id).isEqualTo(equipmentId);

        authenticate(new CurrentUser(
                SAME_ORGANIZATION_USER_ID, 1L, "equipment_team_leader_it",
                "同组织设备查看人", false,
                Set.of("TEAM_LEADER"),
                Set.of("equipment:ledger:view", "equipment:status:view", "equipment:barcode:view"),
                "equipment-team-leader-session"
        ));
        assertThat(service.page(
                "EQ-IT-001", null, null, null, null, null, null, 1, 20
        ).records()).singleElement()
                .extracting(EquipmentDtos.EquipmentRow::id).isEqualTo(equipmentId);
        assertThat(service.detail(equipmentId).equipment().id()).isEqualTo(equipmentId);
        assertThat(service.statusHistory(equipmentId)).isNotEmpty();
        assertThat(service.barcodes(equipmentId, true)).singleElement()
                .extracting(EquipmentDtos.BarcodeRow::id).isEqualTo(replacement.id());

        authenticateAdministrator();

        service.unbindBarcode(equipmentId, "批量生成测试");
        EquipmentDtos.BulkBarcodeResult bulk = service.generateMissingBarcodes(
                new EquipmentDtos.GenerateBarcodeRequest("QR", null)
        );
        assertThat(bulk.generatedCount()).isGreaterThanOrEqualTo(1);
        EquipmentDtos.BarcodeRow bulkGenerated = service.barcodes(equipmentId, true).getFirst();
        byte[] archive = service.barcodeArchive(List.of(bulkGenerated.id()), 600, 600);
        assertThat(archive).startsWith((byte) 'P', (byte) 'K');

        assertThat(service.importTemplate()).isNotEmpty();
        assertThat(service.exportWorkbook(
                "EQ-IT-001", null, null, null, null, null, null
        )).isNotEmpty();

        assertThatThrownBy(() ->
                service.delete(equipmentId, transferred.equipment().version())
        )
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("EQUIPMENT_HAS_BUSINESS_RECORDS");

        BigDecimal stored = jdbc.queryForObject(
                """
                SELECT decimal_value
                FROM equipment_attribute_value
                WHERE tenant_id = 1
                  AND equipment_id = ?
                  AND attribute_definition_id = 1
                """,
                BigDecimal.class,
                equipmentId
        );
        assertThat(stored).isEqualByComparingTo("11.5");
    }
}
