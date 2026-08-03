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
                "spring.data.redis.repositories.enabled=false",
                "management.health.redis.enabled=false",
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

        assertThat(validated.status()).isEqualTo("VALIDATED");
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
