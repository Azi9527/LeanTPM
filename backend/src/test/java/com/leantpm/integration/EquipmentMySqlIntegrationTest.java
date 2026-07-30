package com.leantpm.integration;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
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
class EquipmentMySqlIntegrationTest {
    private static final long USER_ID = 9001L;

    @Autowired
    private EquipmentService service;

    @Autowired
    private JdbcTemplate jdbc;

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
        CurrentUser user = new CurrentUser(
                USER_ID,
                1L,
                "equipment_it",
                "设备集成测试",
                false,
                Set.of("SUPER_ADMIN"),
                Set.of(
                        "equipment:ledger:view",
                        "equipment:ledger:create",
                        "equipment:ledger:update",
                        "equipment:ledger:transfer",
                        "equipment:status:update",
                        "equipment:barcode:manage"
                ),
                "equipment-it-session"
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
