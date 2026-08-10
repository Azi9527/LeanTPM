package com.leantpm.integration;

import com.leantpm.security.CurrentUser;
import com.leantpm.system.dto.UserImportDtos;
import com.leantpm.system.service.UserImportService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
class SystemUserImportMySqlIntegrationTest {
    @Autowired
    private UserImportService importService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void authenticateAdmin() {
        jdbc.update("""
                INSERT INTO system_user_role
                    (tenant_id, user_id, role_id, created_by, updated_by, deleted)
                SELECT 1, 1, id, 1, 1, 0 FROM system_role
                WHERE tenant_id = 1 AND role_code = 'ADMIN' AND deleted = 0
                ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1
                """);
        CurrentUser admin = new CurrentUser(
                1L, 1L, "admin", "系统管理员", false,
                Set.of("ADMIN"), Set.of("system:user:import"),
                "system-user-import-it"
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
    void validatesCommitsAndReturnsCommittedBatchIdempotently() {
        UserImportDtos.ImportResult validated = importService.validate(file(
                "users-valid.xlsx", workbook("import_operator_01", "888888", false)
        ));

        assertThat(validated.status())
                .withFailMessage("validation errors: %s", validated.errors())
                .isEqualTo("VALIDATED");
        assertThat(validated.validRows()).isEqualTo(1);
        assertThat(validated.newUsers()).isEqualTo(1);

        UserImportDtos.ImportResult committed = importService.commit(validated.batchId());
        UserImportDtos.ImportResult repeated = importService.commit(validated.batchId());

        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.newUsers()).isEqualTo(1);
        assertThat(repeated).isEqualTo(committed);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM system_user user
                JOIN system_user_role relation
                  ON relation.tenant_id = user.tenant_id
                 AND relation.user_id = user.id
                 AND relation.deleted = 0
                JOIN system_role role
                  ON role.tenant_id = relation.tenant_id
                 AND role.id = relation.role_id
                 AND role.deleted = 0
                WHERE user.tenant_id = 1
                  AND user.username = 'import_operator_01'
                  AND user.mobile_enabled = 1
                  AND user.must_change_password = 1
                  AND user.deleted = 0
                  AND role.role_code = 'OPERATOR'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM system_user user
                JOIN system_user_team_membership membership
                  ON membership.tenant_id = user.tenant_id
                 AND membership.user_id = user.id
                 AND membership.primary_flag = 1
                 AND membership.deleted = 0
                JOIN organization team
                  ON team.tenant_id = membership.tenant_id
                 AND team.id = membership.team_organization_id
                 AND team.deleted = 0
                WHERE user.tenant_id = 1
                  AND user.username = 'import_operator_01'
                  AND team.organization_code = 'TEAM-A-1'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void importsOneTeamLeaderIntoUnifiedOrganizationManagerField() {
        byte[] content;
        try (var input = new ByteArrayInputStream(importService.template());
             var workbook = new XSSFWorkbook(input);
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("用户导入");
            var row = sheet.getRow(1);
            row.getCell(0).setCellValue("import_team_leader_01");
            row.getCell(1).setCellValue("导入班组长");
            row.getCell(2).setCellValue("TL-IT-001");
            row.getCell(5).setCellValue("WORKSHOP-A");
            row.getCell(6).setCellValue("TEAM_LEADER");
            row.getCell(8).setCellValue("888888");
            row.getCell(10).setCellValue("");
            row.getCell(11).setCellValue("");
            row.createCell(12).setCellValue("TEAM-A-2");
            workbook.write(output);
            content = output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }

        UserImportDtos.ImportResult validated = importService.validate(file(
                "team-leader.xlsx", content
        ));
        assertThat(validated.status())
                .withFailMessage("validation errors: %s", validated.errors())
                .isEqualTo("VALIDATED");
        assertThat(validated.validRows()).isEqualTo(1);
        importService.commit(validated.batchId());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM organization organization
                JOIN system_user manager
                  ON manager.tenant_id = organization.tenant_id
                 AND manager.id = organization.manager_user_id
                 AND manager.deleted = 0
                WHERE organization.tenant_id = 1
                  AND organization.organization_code = 'TEAM-A-2'
                  AND manager.username = 'import_team_leader_01'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM organization_manager_relation relation
                JOIN organization organization
                  ON organization.tenant_id = relation.tenant_id
                 AND organization.id = relation.organization_id
                 AND organization.deleted = 0
                JOIN system_user manager
                  ON manager.tenant_id = relation.tenant_id
                 AND manager.id = relation.user_id
                 AND manager.deleted = 0
                WHERE organization.tenant_id = 1
                  AND organization.organization_code = 'TEAM-A-2'
                  AND manager.username = 'import_team_leader_01'
                  AND relation.deleted = 0
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void preservesValidRowsAndReturnsPreciseWeakPasswordError() {
        UserImportDtos.ImportResult result = importService.validate(file(
                "users-with-error.xlsx", workbook("import_operator_02", "123", true)
        ));

        assertThat(result.status()).isEqualTo("VALIDATED_WITH_ERRORS");
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.validRows()).isEqualTo(1);
        assertThat(result.errors())
                .anySatisfy(error -> {
                    assertThat(error.rowNumber()).isEqualTo(2);
                    assertThat(error.column()).isEqualTo("初始密码");
                    assertThat(error.message()).contains("至少 6 位");
                });

        UserImportDtos.ImportResult committed = importService.commit(result.batchId());
        assertThat(committed.newUsers()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_user
                WHERE tenant_id = 1 AND username = 'import_operator_02' AND deleted = 0
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_user
                WHERE tenant_id = 1 AND username = 'import_operator_03' AND deleted = 0
                """, Long.class)).isEqualTo(1L);
    }

    private byte[] workbook(String firstUsername, String firstPassword, boolean addValidSecondRow) {
        try (var input = new ByteArrayInputStream(importService.template());
             var workbook = new XSSFWorkbook(input);
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("用户导入");
            var first = sheet.getRow(1);
            first.getCell(0).setCellValue(firstUsername);
            first.getCell(1).setCellValue("导入操作工");
            first.getCell(8).setCellValue(firstPassword);
            if (addValidSecondRow) {
                var second = sheet.createRow(2);
                String[] values = {
                        "import_operator_03", "有效操作工", "OP-IT-003", "13800009003",
                        "import03@example.com", "TEAM-A-1", "OPERATOR", "是", "888888", "仅新增"
                };
                for (int index = 0; index < values.length; index++) {
                    second.createCell(index).setCellValue(values[index]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile(
                "file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content
        );
    }
}
