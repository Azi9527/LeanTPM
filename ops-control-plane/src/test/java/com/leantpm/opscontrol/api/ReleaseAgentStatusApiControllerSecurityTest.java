package com.leantpm.opscontrol.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leantpm.opscontrol.release.ReleaseAgentConnectionState;
import com.leantpm.opscontrol.release.ReleaseAgentStatus;
import com.leantpm.opscontrol.release.ReleaseAgentStatusReader;
import com.leantpm.opscontrol.security.HashedOperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OpsSecurityConfiguration;
import java.time.Instant;
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
    controllers = ReleaseAgentStatusApiController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class
)
@Import({
    OpsSecurityConfiguration.class,
    ReleaseAgentStatusApiControllerSecurityTest.AuthConfiguration.class
})
class ReleaseAgentStatusApiControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReleaseAgentStatusReader statusReader;

    @Test
    void agentStatusRequiresIndependentAuthentication() throws Exception {
        mvc.perform(get("/api/v1/agent"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(statusReader);
    }

    @Test
    void authenticatedOperatorReceivesBoundedAgentStatus() throws Exception {
        when(statusReader.status()).thenReturn(new ReleaseAgentStatus(
            ReleaseAgentConnectionState.ONLINE,
            "release-agent-01",
            "1.0.1",
            Instant.parse("2026-08-09T10:59:50Z"),
            2,
            false
        ));

        mvc.perform(get("/api/v1/agent")
                .header("Authorization", "Bearer ops-token-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ONLINE"))
            .andExpect(jsonPath("$.pendingJobs").value(2))
            .andExpect(jsonPath("$.productionExecutionEnabled").value(false));
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
