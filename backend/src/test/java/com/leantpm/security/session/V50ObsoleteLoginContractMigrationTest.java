package com.leantpm.security.session;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V50ObsoleteLoginContractMigrationTest {

    @Test
    void removesOnlyTheHistoricalLoginChallengeToggle() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V50__remove_obsolete_login_challenge_toggle.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .isEqualTo(
                        "DELETE FROM system_parameter\n"
                                + "WHERE parameter_key = 'security.captcha.enabled';\n\n"
                                + "UPDATE system_parameter\n"
                                + "SET parameter_value = '101', version = version + 1, updated_by = 0\n"
                                + "WHERE parameter_key = 'mobile.android-min-version-code'\n"
                                + "  AND CAST(parameter_value AS UNSIGNED) < 101;\n"
                );
    }
}
