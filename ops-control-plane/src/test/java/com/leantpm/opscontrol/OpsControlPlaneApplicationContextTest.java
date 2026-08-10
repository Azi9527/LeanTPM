package com.leantpm.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.leantpm.opscontrol.config.OpsControlPlaneProperties;
import com.leantpm.opscontrol.release.FileHostSnapshotProvider;
import com.leantpm.opscontrol.release.FileQueueReleaseAgent;
import com.leantpm.opscontrol.release.JournalReleaseRepository;
import com.leantpm.opscontrol.release.PackageStorage;
import com.leantpm.opscontrol.release.FileDeploymentBundleMaterializer;
import com.leantpm.opscontrol.release.PowerShellDeploymentBundleVerifier;
import com.leantpm.opscontrol.release.PowerShellReleasePackageVerifier;
import com.leantpm.opscontrol.release.ReleaseWorkflowService;
import com.leantpm.opscontrol.release.ReleaseAgentStatusReader;
import com.leantpm.opscontrol.security.HashedOperatorTokenAuthenticator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsControlPlaneApplicationContextTest {

    private static final Path ROOT = createFixture();
    private static final Path POWERSHELL = ROOT.resolve("powershell.exe");
    private static final Path VERIFIER = ROOT.resolve("Test-ReleasePackage.ps1");
    private static final Path BUNDLE_VERIFIER = ROOT.resolve(
        "Test-LeanTpmDeploymentBundle.ps1"
    );
    private static final Path APPROVAL_VERIFIER = ROOT.resolve(
        "Test-LeanTpmReleaseApproval.ps1"
    );
    private static final Path BUNDLE_SCHEMA = ROOT.resolve("deployment-bundle.schema.json");
    private static final Path RELEASE_TRUST = ROOT.resolve("release-trust.json");
    private static final Path HOST_LAYOUT = ROOT.resolve("host-layout.json");
    private static final Path POINTER = ROOT.resolve("current-release.json");

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) {
        registry.add("leantpm.ops.data-root", () -> ROOT.resolve("ops-data").toString());
        registry.add("leantpm.ops.powershell-executable", POWERSHELL::toString);
        registry.add("leantpm.ops.verifier-script", VERIFIER::toString);
        registry.add("leantpm.ops.verifier-script-sha256", () -> sha256(VERIFIER));
        registry.add("leantpm.ops.bundle-verifier-script", BUNDLE_VERIFIER::toString);
        registry.add(
            "leantpm.ops.bundle-verifier-script-sha256",
            () -> sha256(BUNDLE_VERIFIER)
        );
        registry.add(
            "leantpm.ops.deployment-bundle-schema-sha256",
            () -> sha256(BUNDLE_SCHEMA)
        );
        registry.add(
            "leantpm.ops.approval-verifier-script-sha256",
            () -> sha256(APPROVAL_VERIFIER)
        );
        registry.add("leantpm.ops.release-trust-config-path", RELEASE_TRUST::toString);
        registry.add(
            "leantpm.ops.trusted-certificate-thumbprint",
            () -> "A".repeat(40)
        );
        registry.add("leantpm.ops.host-layout-path", HOST_LAYOUT::toString);
        registry.add("leantpm.ops.host-layout-sha256", () -> sha256(HOST_LAYOUT));
        registry.add("leantpm.ops.current-release-pointer", POINTER::toString);
        registry.add(
            "leantpm.ops.operator-token-sha256.release_operator",
            () -> "b".repeat(64)
        );
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OpsControlPlaneProperties properties;

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    @Test
    void startsWithOnlyPinnedProductionComponentsAndNoDefaultUser() {
        assertThat(context.getBean(PackageStorage.class)).isNotNull();
        assertThat(context.getBean(JournalReleaseRepository.class)).isNotNull();
        assertThat(context.getBean(PowerShellReleasePackageVerifier.class)).isNotNull();
        assertThat(context.getBean(PowerShellDeploymentBundleVerifier.class)).isNotNull();
        assertThat(context.getBean(FileDeploymentBundleMaterializer.class)).isNotNull();
        assertThat(context.getBean(FileHostSnapshotProvider.class)).isNotNull();
        assertThat(context.getBean(FileQueueReleaseAgent.class)).isNotNull();
        assertThat(context.getBean(HashedOperatorTokenAuthenticator.class)).isNotNull();
        assertThat(context.getBean(ReleaseWorkflowService.class)).isNotNull();
        assertThat(context.getBean(ReleaseAgentStatusReader.class)).isNotNull();
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        assertThat(properties.getRequiredApprovals()).isEqualTo(2);
        assertThat(properties.getDataRoot()).isEqualTo(ROOT.resolve("ops-data"));
        assertThat(properties.getBundleVerifierScript()).isEqualTo(BUNDLE_VERIFIER);
        assertThat(properties.getReleaseTrustConfigPath()).isEqualTo(RELEASE_TRUST);
        assertThat(context.getEnvironment().getProperty("server.address"))
            .isEqualTo("127.0.0.1");

        var home = http.getForEntity(
            "http://127.0.0.1:" + port + "/",
            String.class
        );
        assertThat(home.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(home.getBody())
            .contains("LeanTPM 运维控制台")
            .contains("审计时间线")
            .contains("受限 Agent 未连接")
            .contains("id=\"deploymentBundle\"");
        var applicationScript = http.getForEntity(
            "http://127.0.0.1:" + port + "/app.js",
            String.class
        );
        assertThat(applicationScript.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(applicationScript.getBody())
            .contains("AGENT_VERIFIED")
            .contains("DEPLOYED")
            .contains("生产部署成功")
            .contains("服务器已复核，尚未部署")
            .contains("/api/v1/releases/import-bundle")
            .contains("form.append(\"bundle\"");
        assertThat(http.getForEntity(
            "http://127.0.0.1:" + port + "/actuator/health",
            String.class
        ).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity(
            "http://127.0.0.1:" + port + "/api/v1/releases/missing",
            String.class
        ).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static Path createFixture() {
        try {
            Path root = Files.createTempDirectory("leantpm-ops-context-");
            Files.writeString(root.resolve("powershell.exe"), "pinned", StandardCharsets.UTF_8);
            Files.writeString(
                root.resolve("Test-ReleasePackage.ps1"),
                "# pinned verifier",
                StandardCharsets.UTF_8
            );
            Files.writeString(
                root.resolve("Test-LeanTpmDeploymentBundle.ps1"),
                "# pinned bundle verifier",
                StandardCharsets.UTF_8
            );
            Files.writeString(
                root.resolve("Test-LeanTpmReleaseApproval.ps1"),
                "# pinned approval verifier",
                StandardCharsets.UTF_8
            );
            Files.writeString(
                root.resolve("deployment-bundle.schema.json"),
                "{}",
                StandardCharsets.UTF_8
            );
            Files.writeString(root.resolve("release-trust.json"), "{}", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("host-layout.json"), "{}", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("current-release.json"), "{}", StandardCharsets.UTF_8);
            return root;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            );
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
