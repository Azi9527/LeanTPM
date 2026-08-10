package com.leantpm.opscontrol.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leantpm.opscontrol.release.ConfirmationResult;
import com.leantpm.opscontrol.release.DeploymentPlan;
import com.leantpm.opscontrol.release.ReleaseRecord;
import com.leantpm.opscontrol.release.ReleaseState;
import com.leantpm.opscontrol.release.ReleaseWorkflowService;
import com.leantpm.opscontrol.security.HashedOperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OperatorTokenAuthenticator;
import com.leantpm.opscontrol.security.OpsSecurityConfiguration;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(
    controllers = ReleaseApiController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class
)
@Import({OpsSecurityConfiguration.class, ReleaseApiControllerSecurityTest.AuthConfiguration.class})
class ReleaseApiControllerSecurityTest {

    private static final String TOKEN = "ops-token-a";
    private static final String AUTHORIZATION = "Bearer " + TOKEN;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReleaseWorkflowService workflow;

    @Test
    void apiRejectsMissingOrInvalidIndependentBearerToken() throws Exception {
        mvc.perform(get("/api/v1/releases/release-001"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/releases/release-001")
                .header("Authorization", "Bearer wrong-token"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(workflow);
    }

    @Test
    void authenticatedOperatorCanUploadAndIdentityComesFromToken() throws Exception {
        ReleaseRecord record = record();
        when(workflow.importRelease(any(), eq("release.zip"), eq(3L), eq("operator-a"), eq("import-001")))
            .thenReturn(record);
        MockMultipartFile upload = new MockMultipartFile(
            "package", "release.zip", "application/zip", new byte[] {1, 2, 3}
        );

        mvc.perform(multipart("/api/v1/releases/import")
                .file(upload)
                .header("Authorization", AUTHORIZATION)
                .header("Idempotency-Key", "import-001")
                .header("X-LeanTPM-Operator", "forged-operator")
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.releaseId").value(record.releaseId()))
            .andExpect(jsonPath("$.state").value("VERIFIED"))
            .andExpect(jsonPath("$.packagePath").doesNotExist());

        verify(workflow).importRelease(
            any(), eq("release.zip"), eq(3L), eq("operator-a"), eq("import-001")
        );
    }

    @Test
    void authenticatedOperatorCanImportOneSignedDeploymentBundleWithoutExposingPaths()
        throws Exception {
        ReleaseRecord record = new ReleaseRecord(
            "1.0.2-abcdef123456",
            "1.0.2",
            51,
            "release-package.zip",
            Path.of("D:/ops/uploads/releases/abc/release-package.zip"),
            4,
            "a".repeat(64),
            "b".repeat(64),
            ReleaseState.AWAITING_CONFIRMATION,
            plan(),
            List.of(),
            null,
            "operator-a",
            Instant.parse("2026-08-09T08:00:00Z")
        );
        when(workflow.importDeploymentBundle(
            any(),
            eq("LeanTPM-1.0.2-deployment-bundle.zip"),
            eq(6L),
            eq("operator-a"),
            eq("import-bundle-001")
        )).thenReturn(record);
        MockMultipartFile upload = new MockMultipartFile(
            "bundle",
            "LeanTPM-1.0.2-deployment-bundle.zip",
            "application/zip",
            new byte[] {1, 2, 3, 4, 5, 6}
        );

        mvc.perform(multipart("/api/v1/releases/import-bundle")
                .file(upload)
                .header("Authorization", AUTHORIZATION)
                .header("Idempotency-Key", "import-bundle-001")
                .header("X-LeanTPM-Operator", "forged-operator")
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.releaseId").value(record.releaseId()))
            .andExpect(jsonPath("$.state").value("AWAITING_CONFIRMATION"))
            .andExpect(jsonPath("$.plan.action").value("DEPLOY_SIGNED_RELEASE"))
            .andExpect(jsonPath("$.packagePath").doesNotExist());

        verify(workflow).importDeploymentBundle(
            any(),
            eq("LeanTPM-1.0.2-deployment-bundle.zip"),
            eq(6L),
            eq("operator-a"),
            eq("import-bundle-001")
        );
    }

    @Test
    void authenticatedOperatorCanListSanitizedReleaseStatus() throws Exception {
        ReleaseRecord record = record();
        when(workflow.list()).thenReturn(List.of(record));

        mvc.perform(get("/api/v1/releases")
                .header("Authorization", AUTHORIZATION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].releaseId").value(record.releaseId()))
            .andExpect(jsonPath("$[0].packageSha256").value(record.packageSha256()))
            .andExpect(jsonPath("$[0].packagePath").doesNotExist());
    }

    @Test
    void planAndExactConfirmationExposeNoArbitraryCommandInputs() throws Exception {
        DeploymentPlan plan = plan();
        when(workflow.createPlan("release-001", "operator-a")).thenReturn(plan);
        when(workflow.confirm(
            "release-001", plan.planSha256(), "operator-a", "approved", "confirm-001"
        )).thenReturn(new ConfirmationResult(
            "release-001", ReleaseState.QUEUED, 1, 1, "job-001"
        ));

        mvc.perform(post("/api/v1/releases/release-001/plan")
                .header("Authorization", AUTHORIZATION)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.planSha256").value(plan.planSha256()));

        mvc.perform(post("/api/v1/releases/release-001/confirm")
                .header("Authorization", AUTHORIZATION)
                .header("Idempotency-Key", "confirm-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedPlanSha256":"%s",
                      "reason":"approved"
                    }
                    """.formatted(plan.planSha256()))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("QUEUED"))
            .andExpect(jsonPath("$.jobId").value("job-001"));
    }

    private static ReleaseRecord record() {
        return new ReleaseRecord(
            "release-001", "1.0.1", 50, "release.zip", Path.of("D:/ops/uploads/package.zip"),
            3, "a".repeat(64), "b".repeat(64), ReleaseState.VERIFIED, null,
            List.of(), null, "operator-a", Instant.parse("2026-08-09T08:00:00Z")
        );
    }

    private static DeploymentPlan plan() {
        return new DeploymentPlan(
            1, "DEPLOY_SIGNED_RELEASE", "release-001", "a".repeat(64), "b".repeat(64),
            "c".repeat(64), "nonce-001", Instant.parse("2026-08-09T08:00:00Z"),
            Instant.parse("2026-08-09T08:15:00Z"), "operator-a", "d".repeat(64)
        );
    }

    @TestConfiguration
    static class AuthConfiguration {
        @Bean
        OperatorTokenAuthenticator operatorTokenAuthenticator() {
            return HashedOperatorTokenAuthenticator.fromPlaintextForTests(
                Map.of("operator-a", TOKEN)
            );
        }
    }
}
