package com.leantpm.opscontrol.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leantpm.opscontrol.release.ReleaseAuditEvent;
import com.leantpm.opscontrol.release.ReleaseAuditPage;
import com.leantpm.opscontrol.release.ReleaseAuditReader;
import com.leantpm.opscontrol.release.ReleaseState;
import com.leantpm.opscontrol.security.HashedOperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OpsSecurityConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = AuditApiController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class
)
@Import({OpsSecurityConfiguration.class, AuditApiControllerSecurityTest.AuthConfiguration.class})
class AuditApiControllerSecurityTest {

    private static final String AUTHORIZATION = "Bearer ops-token-a";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReleaseAuditReader auditReader;

    @Test
    void auditRequiresIndependentAuthentication() throws Exception {
        mvc.perform(get("/api/v1/audit"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(auditReader);
    }

    @Test
    void authenticatedAuditIsSanitizedAndCursorBounded() throws Exception {
        ReleaseAuditEvent event = new ReleaseAuditEvent(
            7,
            "SAVE_RELEASE",
            "release-001",
            ReleaseState.QUEUED,
            null,
            "a".repeat(64),
            "b".repeat(64)
        );
        when(auditReader.audit(5, 20)).thenReturn(
            new ReleaseAuditPage(7, false, List.of(event))
        );

        mvc.perform(get("/api/v1/audit?after=5&limit=20")
                .header("Authorization", AUTHORIZATION)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nextCursor").value(7))
            .andExpect(jsonPath("$.events[0].releaseId").value("release-001"))
            .andExpect(jsonPath("$.events[0].state").value("QUEUED"))
            .andExpect(jsonPath("$.events[0].packagePath").doesNotExist());
    }

    static class AuthConfiguration {
        @Bean
        OperatorTokenAuthenticator operatorTokenAuthenticator() {
            return HashedOperatorTokenAuthenticator.fromPlaintextForTests(
                Map.of("operator-a", "ops-token-a")
            );
        }
    }
}
