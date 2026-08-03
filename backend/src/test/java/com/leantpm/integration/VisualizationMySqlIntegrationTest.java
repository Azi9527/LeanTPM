package com.leantpm.integration;

import com.leantpm.security.CurrentUser;
import com.leantpm.visualization.VisualizationDtos;
import com.leantpm.visualization.VisualizationService;
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

import java.time.LocalDate;
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
class VisualizationMySqlIntegrationTest {
    private static final long USER_ID = 9401L;

    @Autowired
    private VisualizationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void authenticate() {
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, must_change_password)
                VALUES (?, 1, 'visualization_it', 'not-used',
                        '可视化集成测试', 2, 1, 0)
                """, USER_ID);
        jdbc.update(
                "INSERT INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, ?, 1)",
                USER_ID
        );
        CurrentUser current = new CurrentUser(
                USER_ID,
                1L,
                "visualization_it",
                "可视化集成测试",
                false,
                Set.of("ADMIN"),
                Set.of(
                        "visualization:cockpit:view",
                        "visualization:3d:view",
                        "visualization:scene:view",
                        "visualization:scene:manage",
                        "visualization:model:manage"
                ),
                "visualization-it-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(current, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loadsAggregateDashboardSceneAndEquipmentSnapshot() {
        VisualizationDtos.DashboardResult dashboard = service.dashboard(
                LocalDate.now().minusDays(6), LocalDate.now(), 2L, "DAY"
        );
        assertThat(dashboard.core().total()).isEqualTo(8);
        assertThat(dashboard.statusDistribution()).hasSize(12);
        assertThat(dashboard.organizationDistribution()).hasSize(4);
        assertThat(dashboard.liveEquipment()).hasSize(8);
        assertThat(dashboard.periodType()).isEqualTo("DAY");
        assertThat(dashboard.refreshSeconds()).isEqualTo(86400);

        VisualizationDtos.DashboardResult weekly = service.dashboard(
                LocalDate.now().minusDays(77), LocalDate.now(), 2L, "week"
        );
        assertThat(weekly.periodType()).isEqualTo("WEEK");
        VisualizationDtos.DashboardResult monthly = service.dashboard(
                LocalDate.now().minusMonths(11), LocalDate.now(), 2L, "MONTH"
        );
        assertThat(monthly.periodType()).isEqualTo("MONTH");

        assertThat(service.scenes()).hasSize(7);
        VisualizationDtos.SceneDetail factory = service.scene(1L);
        assertThat(factory.scene().sceneCode()).isEqualTo("SCENE-FACTORY-A");
        assertThat(factory.nodes()).hasSize(2);
        assertThat(factory.statusColors()).hasSize(12);

        VisualizationDtos.EquipmentSnapshot snapshot = service.equipmentSnapshot(1L);
        assertThat(snapshot.equipmentCode()).isEqualTo("VIZ-CNC-01");
        assertThat(snapshot.statusCode()).isEqualTo("RUNNING");
        assertThat(snapshot.recentEvents()).isNotEmpty();
    }

    @Test
    void createsUpdatesAndDeletesPrimitiveModelWithOptimisticLocking() {
        long id = service.createModel(new VisualizationDtos.SaveModelRequest(
                "it-primitive",
                "集成测试基础模型",
                "EQUIPMENT",
                null,
                "PRIMITIVE",
                "BOX",
                "#123456",
                null,
                "integration",
                1,
                null
        ));
        VisualizationDtos.ModelResource created = service.model(id);
        assertThat(created.resourceCode()).isEqualTo("IT-PRIMITIVE");

        service.updateModel(id, new VisualizationDtos.SaveModelRequest(
                "IT-PRIMITIVE",
                "集成测试更新模型",
                "EQUIPMENT",
                null,
                "PRIMITIVE",
                "BOX",
                "#654321",
                null,
                "updated",
                1,
                created.version()
        ));
        VisualizationDtos.ModelResource updated = service.model(id);
        assertThat(updated.resourceName()).isEqualTo("集成测试更新模型");
        assertThat(updated.version()).isEqualTo(created.version() + 1);

        service.deleteModel(id, updated.version());
        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM visualization_model_resource WHERE id = ?",
                Integer.class,
                id
        )).isEqualTo(1);
    }
}
