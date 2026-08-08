package com.leantpm.integration;

import com.leantpm.masterdata.MasterDataService;
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
class MasterDataOrganizationDeleteMySqlIntegrationTest {
    private static final long RELATED_USER_ID = 9401L;
    @Autowired
    private MasterDataService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void authenticateAdmin() {
        jdbc.update("INSERT IGNORE INTO system_user_role (tenant_id, user_id, role_id) VALUES (1, 1, 1)");
        CurrentUser admin = new CurrentUser(
                1L,
                1L,
                "admin",
                "系统管理员",
                false,
                Set.of("ADMIN"),
                Set.of("master-data:organization:delete"),
                "master-data-delete-it"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportsAndRemovesLeafOrganizationRelationsTransactionally() {
        long parentId = jdbc.queryForObject(
                "SELECT id FROM organization WHERE tenant_id = 1"
                        + " AND organization_code = 'WORKSHOP-A' AND deleted = 0",
                Long.class
        );
        jdbc.update(
                "INSERT INTO organization"
                        + " (tenant_id, parent_id, organization_code, organization_name,"
                        + " organization_type, status, created_by, updated_by)"
                        + " VALUES (1, ?, 'TEAM-DELETE-IT', '级联删除测试班组', 'TEAM', 1, 1, 1)",
                parentId
        );
        long organizationId = jdbc.queryForObject(
                "SELECT id FROM organization WHERE tenant_id = 1"
                        + " AND organization_code = 'TEAM-DELETE-IT'",
                Long.class
        );
        jdbc.update(
                "INSERT INTO system_user"
                        + " (id, tenant_id, username, password_hash, real_name, organization_id,"
                        + " status, must_change_password)"
                        + " VALUES (?, 1, 'organization_delete_it', 'not-used',"
                        + " '组织删除关联测试用户', ?, 1, 0)",
                RELATED_USER_ID,
                parentId
        );
        long userId = RELATED_USER_ID;
        jdbc.update(
                "UPDATE system_user SET organization_id = ? WHERE tenant_id = 1 AND id = ?",
                organizationId,
                userId
        );
        jdbc.update(
                "INSERT INTO system_user_team_membership"
                        + " (tenant_id, user_id, team_organization_id, primary_flag,"
                        + " created_by, updated_by) VALUES (1, ?, ?, 1, 1, 1)",
                userId,
                organizationId
        );

        var impact = service.organizationDeleteImpact(organizationId);
        assertThat(impact.users()).isEqualTo(1);
        assertThat(impact.teamMemberships()).isEqualTo(1);
        assertThat(impact.totalReferences()).isEqualTo(2);

        assertThatThrownBy(() -> service.deleteOrganization(organizationId, 0, false))
                .hasMessageContaining("存在关联关系");

        service.deleteOrganization(organizationId, 0, true);

        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM organization WHERE tenant_id = 1 AND id = ?",
                Integer.class,
                organizationId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT organization_id FROM system_user WHERE tenant_id = 1 AND id = ?",
                Long.class,
                userId
        )).isEqualTo(parentId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_user_team_membership"
                        + " WHERE tenant_id = 1 AND user_id = ? AND team_organization_id = ?",
                Integer.class,
                userId,
                organizationId
        )).isZero();
    }
}
