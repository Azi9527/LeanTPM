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
        assertThat(bootstrap.inspection()).isNotNull();
        assertThat(bootstrap.maintenance()).isNotNull();
        assertThat(bootstrap.messages()).isNotNull();

        MobileDtos.EquipmentContext context = service.equipment(TOKEN.toUpperCase());
        assertThat(context.equipment().equipmentId()).isEqualTo(1L);
        assertThat(context.equipment().equipmentCode()).isEqualTo("VIZ-CNC-01");
        assertThat(context.equipment().statusCode()).isEqualTo("RUNNING");
        assertThat(context.equipment().statusColor()).startsWith("#");
        assertThat(context.activeTasks()).isNotNull();
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

    private void authenticate() {
        CurrentUser current = new CurrentUser(
                USER_ID,
                1L,
                "mobile_it",
                "移动端集成测试",
                false,
                Set.of("SUPER_ADMIN"),
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
