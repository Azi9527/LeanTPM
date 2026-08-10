package com.leantpm.opscontrol.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leantpm.opscontrol.notification.PushPlusNotificationStatus;
import com.leantpm.opscontrol.notification.PushPlusRecipientView;
import com.leantpm.opscontrol.operations.OperationsComponent;
import com.leantpm.opscontrol.operations.OperationsComponentKind;
import com.leantpm.opscontrol.operations.OperationsDashboard;
import com.leantpm.opscontrol.operations.OperationsHealth;
import com.leantpm.opscontrol.operations.OperationsSnapshot;
import com.leantpm.opscontrol.operations.OperationsStatusService;
import com.leantpm.opscontrol.security.HashedOperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OpsSecurityConfiguration;
import java.time.Instant;
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
    controllers = OperationsApiController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class
)
@Import({
    OpsSecurityConfiguration.class,
    OperationsApiControllerSecurityTest.AuthConfiguration.class
})
class OperationsApiControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OperationsStatusService operations;

    @Test
    void operationsStatusAndRefreshRequireIndependentAuthentication() throws Exception {
        mvc.perform(get("/api/v1/operations/status"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/operations/refresh"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(operations);
    }

    @Test
    void authenticatedStatusIsBoundedAndNeverContainsRecipientTokens() throws Exception {
        when(operations.status()).thenReturn(dashboard());

        mvc.perform(get("/api/v1/operations/status")
                .header("Authorization", "Bearer ops-token-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot.overallStatus").value("DEGRADED"))
            .andExpect(jsonPath("$.snapshot.components[0].id").value("database:mysql"))
            .andExpect(jsonPath("$.notifications.recipients[0].name").value("项目负责人"))
            .andExpect(jsonPath("$.notifications.recipients[0].channel").value("wechat"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret-token")
            )));
    }

    private static OperationsDashboard dashboard() {
        Instant observedAt = Instant.parse("2026-08-10T01:00:00Z");
        OperationsSnapshot snapshot = new OperationsSnapshot(
            true,
            OperationsHealth.DEGRADED,
            observedAt,
            List.of(new OperationsComponent(
                "database:mysql",
                "MySQL",
                OperationsComponentKind.DATABASE,
                OperationsHealth.DEGRADED,
                "数据库版本等待核对",
                observedAt,
                Map.of("schemaVersion", "50"),
                null
            )),
            List.of()
        );
        PushPlusNotificationStatus notifications = new PushPlusNotificationStatus(
            true,
            List.of(new PushPlusRecipientView("owner", "项目负责人", "wechat", true)),
            null
        );
        return new OperationsDashboard(snapshot, notifications);
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
