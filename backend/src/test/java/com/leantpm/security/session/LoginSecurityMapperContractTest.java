package com.leantpm.security.session;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LoginSecurityMapperContractTest {

    @Test
    void loginGateUsesInsertIfAbsentThenRowLockAndBoundedSafeCleanup() throws Exception {
        String xml = new ClassPathResource(
                "mapper/security/session/AuthSessionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(xml).contains("<insert id=\"ensureLoginSecurityState\">");
        assertThat(xml).contains("INSERT IGNORE INTO auth_login_security_state");
        assertThat(xml).contains("<select id=\"findLoginSecurityStateForUpdate\"");
        assertThat(xml).contains("FOR UPDATE");
        assertThat(xml).contains("<delete id=\"deleteStaleUnlockedLoginSecurityState\">");
        assertThat(xml).contains("locked_until IS NULL OR locked_until &lt;= #{now}");
        assertThat(xml).contains("last_failure_at &lt; #{staleBefore}");
        assertThat(xml).contains("LIMIT #{batchSize}");
    }

    @Test
    void sessionRegistrationAndAccessAreBoundToTheActiveUserVersion() throws Exception {
        String xml = new ClassPathResource(
                "mapper/security/session/AuthSessionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<select id=\"findActiveUserVersionForUpdate\"")
                .contains("FROM system_user")
                .contains("FOR UPDATE")
                .contains("user_version")
                .contains("JOIN system_user")
                .contains("u.auth_epoch = #{userVersion}")
                .contains("u.status = 1")
                .contains("u.deleted = 0");
    }
}
