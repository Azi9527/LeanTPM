package com.leantpm.security.session;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V49PersistentSecurityStateMigrationTest {

    @Test
    void definesAdditivePersistentSecurityStateTables() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V49__persistent_security_state.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE auth_session")
                .contains("user_version BIGINT NOT NULL")
                .contains("CREATE TABLE auth_login_security_state")
                .contains("CREATE TABLE request_idempotency")
                .contains("PRIMARY KEY (tenant_id, key_hash)")
                .contains("key_hash CHAR(64)")
                .contains("COLLATE ascii_bin")
                .contains("fingerprint CHAR(64)")
                .contains("state VARCHAR(16)")
                .contains("owner_token CHAR(36)")
                .contains("fencing_token BIGINT")
                .contains("lease_expires_at DATETIME(3)")
                .contains("response_status INT")
                .contains("response_content_type VARCHAR(100)")
                .contains("response_payload MEDIUMBLOB")
                .contains("completed_at DATETIME(3)")
                .contains("expires_at DATETIME(3)")
                .contains("created_at DATETIME(3)")
                .contains("updated_at DATETIME(3)")
                .contains("KEY idx_request_idempotency_state_expiry (state, expires_at)");
    }
}
