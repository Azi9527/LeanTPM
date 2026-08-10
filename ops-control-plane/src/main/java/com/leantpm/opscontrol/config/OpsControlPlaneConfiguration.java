package com.leantpm.opscontrol.config;

import com.leantpm.opscontrol.release.FileHostSnapshotProvider;
import com.leantpm.opscontrol.release.FileDeploymentBundleMaterializer;
import com.leantpm.opscontrol.release.FileQueueReleaseAgent;
import com.leantpm.opscontrol.release.FileReleaseAgentStatusReader;
import com.leantpm.opscontrol.release.FileReleaseAgentResultReader;
import com.leantpm.opscontrol.release.JournalReleaseRepository;
import com.leantpm.opscontrol.release.PackageStorage;
import com.leantpm.opscontrol.release.PowerShellReleasePackageVerifier;
import com.leantpm.opscontrol.release.PowerShellDeploymentBundleVerifier;
import com.leantpm.opscontrol.release.ReleaseWorkflowService;
import com.leantpm.opscontrol.security.HashedOperatorTokenAuthenticator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsControlPlaneProperties.class)
public class OpsControlPlaneConfiguration {

    @Bean
    Clock opsClock() {
        return Clock.systemUTC();
    }

    @Bean
    OpsDataLayout opsDataLayout(OpsControlPlaneProperties properties) {
        Path dataRoot = absolute(properties.getDataRoot(), "operations data root");
        Path uploadRoot = createDirectory(dataRoot.resolve("uploads"), "release upload root");
        Path approvalRoot = createDirectory(
            dataRoot.resolve("approvals"), "release approval root"
        );
        return new OpsDataLayout(
            dataRoot,
            uploadRoot,
            approvalRoot,
            dataRoot.resolve("state"),
            dataRoot.resolve("queue")
        );
    }

    @Bean
    PackageStorage packageStorage(
        OpsControlPlaneProperties properties,
        OpsDataLayout layout
    ) {
        return new PackageStorage(layout.uploadRoot(), properties.getMaximumUploadBytes());
    }

    @Bean
    JournalReleaseRepository releaseRepository(OpsDataLayout layout) {
        return new JournalReleaseRepository(layout.stateRoot());
    }

    @Bean
    PowerShellReleasePackageVerifier releasePackageVerifier(
        OpsControlPlaneProperties properties
    ) {
        return new PowerShellReleasePackageVerifier(
            absolute(properties.getPowershellExecutable(), "PowerShell executable"),
            absolute(properties.getVerifierScript(), "release verifier script"),
            properties.getVerifierScriptSha256(),
            properties.getTrustedCertificateThumbprint(),
            properties.getVerifierTimeout(),
            properties.getVerifierMaximumOutputBytes()
        );
    }

    @Bean
    PowerShellDeploymentBundleVerifier deploymentBundleVerifier(
        OpsControlPlaneProperties properties,
        OpsDataLayout layout,
        Clock opsClock
    ) {
        return new PowerShellDeploymentBundleVerifier(
            absolute(properties.getPowershellExecutable(), "PowerShell executable"),
            absolute(properties.getBundleVerifierScript(), "deployment bundle verifier"),
            properties.getBundleVerifierScriptSha256(),
            properties.getDeploymentBundleSchemaSha256(),
            properties.getVerifierScriptSha256(),
            properties.getApprovalVerifierScriptSha256(),
            properties.getTrustedCertificateThumbprint(),
            absolute(properties.getReleaseTrustConfigPath(), "release trust configuration"),
            layout.approvalRoot(),
            layout.uploadRoot(),
            properties.getVerifierTimeout(),
            properties.getVerifierMaximumOutputBytes(),
            opsClock
        );
    }

    @Bean
    FileDeploymentBundleMaterializer deploymentBundleMaterializer(OpsDataLayout layout) {
        return new FileDeploymentBundleMaterializer(
            layout.uploadRoot(), layout.approvalRoot()
        );
    }

    @Bean
    FileHostSnapshotProvider hostSnapshotProvider(OpsControlPlaneProperties properties) {
        return new FileHostSnapshotProvider(
            absolute(properties.getHostLayoutPath(), "host layout"),
            properties.getHostLayoutSha256(),
            absolute(properties.getCurrentReleasePointer(), "current release pointer")
        );
    }

    @Bean
    FileQueueReleaseAgent releaseAgent(OpsDataLayout layout, Clock opsClock) {
        return new FileQueueReleaseAgent(
            layout.queueRoot(), layout.uploadRoot(), layout.approvalRoot(), opsClock
        );
    }

    @Bean
    FileReleaseAgentStatusReader releaseAgentStatusReader(
        OpsControlPlaneProperties properties,
        OpsDataLayout layout,
        FileQueueReleaseAgent releaseAgent,
        Clock opsClock
    ) {
        return new FileReleaseAgentStatusReader(
            layout.queueRoot(),
            properties.getAgentHeartbeatMaximumAge(),
            opsClock
        );
    }

    @Bean
    FileReleaseAgentResultReader releaseAgentResultReader(
        OpsDataLayout layout,
        Clock opsClock
    ) {
        return new FileReleaseAgentResultReader(layout.queueRoot(), opsClock);
    }

    @Bean
    HashedOperatorTokenAuthenticator operatorTokenAuthenticator(
        OpsControlPlaneProperties properties
    ) {
        return new HashedOperatorTokenAuthenticator(properties.getOperatorTokenSha256());
    }

    @Bean
    ReleaseWorkflowService releaseWorkflowService(
        PackageStorage packageStorage,
        PowerShellReleasePackageVerifier releasePackageVerifier,
        PowerShellDeploymentBundleVerifier deploymentBundleVerifier,
        FileDeploymentBundleMaterializer deploymentBundleMaterializer,
        FileHostSnapshotProvider hostSnapshotProvider,
        FileQueueReleaseAgent releaseAgent,
        FileReleaseAgentResultReader releaseAgentResultReader,
        JournalReleaseRepository releaseRepository,
        OpsControlPlaneProperties properties,
        Clock opsClock
    ) {
        return new ReleaseWorkflowService(
            packageStorage,
            releasePackageVerifier,
            deploymentBundleVerifier,
            deploymentBundleMaterializer,
            hostSnapshotProvider,
            releaseAgent,
            releaseAgentResultReader,
            releaseRepository,
            properties.getRequiredApprovals(),
            opsClock
        );
    }

    private static Path absolute(Path value, String label) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(label + " must be an absolute path");
        }
        return value.normalize();
    }

    private static Path createDirectory(Path value, String label) {
        try {
            Path normalized = absolute(value, label);
            Files.createDirectories(normalized);
            if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(label + " must be a regular directory");
            }
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize " + label, exception);
        }
    }

    record OpsDataLayout(
        Path dataRoot,
        Path uploadRoot,
        Path approvalRoot,
        Path stateRoot,
        Path queueRoot
    ) {
    }
}
