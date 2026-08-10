package com.leantpm.opscontrol.config;

import com.leantpm.opscontrol.notification.HttpPushPlusTransport;
import com.leantpm.opscontrol.notification.NotificationPublisher;
import com.leantpm.opscontrol.notification.PushPlusNotifier;
import com.leantpm.opscontrol.notification.PushPlusProperties;
import com.leantpm.opscontrol.notification.ReadableNotificationFormatter;
import com.leantpm.opscontrol.operations.CompositeOperationsMonitor;
import com.leantpm.opscontrol.operations.FileOperationsStateStore;
import com.leantpm.opscontrol.operations.FixedLogProbe;
import com.leantpm.opscontrol.operations.HttpReadinessProbe;
import com.leantpm.opscontrol.operations.JdbcDatabaseProbe;
import com.leantpm.opscontrol.operations.OperationsCoordinator;
import com.leantpm.opscontrol.operations.OperationsMonitoringProperties;
import com.leantpm.opscontrol.operations.OperationsProbe;
import com.leantpm.opscontrol.operations.OperationsScheduler;
import com.leantpm.opscontrol.operations.OperationsStateStore;
import com.leantpm.opscontrol.operations.RemediationPolicy;
import com.leantpm.opscontrol.operations.RemediationProperties;
import com.leantpm.opscontrol.operations.RepositoryReleaseTerminalSource;
import com.leantpm.opscontrol.operations.SystemResourceProbe;
import com.leantpm.opscontrol.operations.WindowsScServiceOperations;
import com.leantpm.opscontrol.operations.WindowsServiceProbe;
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
import java.net.http.HttpClient;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    OpsControlPlaneProperties.class,
    OperationsMonitoringProperties.class,
    RemediationProperties.class,
    PushPlusProperties.class
})
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

    @Bean
    WindowsScServiceOperations windowsServiceOperations(
        OperationsMonitoringProperties properties
    ) {
        return new WindowsScServiceOperations(
            absolute(properties.getScExecutable(), "Windows service controller"),
            properties.getServiceTimeout(),
            new com.leantpm.opscontrol.operations.BoundedSystemCommandRunner()
        );
    }

    @Bean
    NotificationPublisher notificationPublisher(PushPlusProperties properties) {
        return new PushPlusNotifier(
            properties,
            new HttpPushPlusTransport(properties.getTimeout()),
            new ReadableNotificationFormatter()
        );
    }

    @Bean
    OperationsStateStore operationsStateStore(OpsDataLayout layout) {
        return new FileOperationsStateStore(
            createDirectory(layout.stateRoot().resolve("operations"), "operations state root")
        );
    }

    @Bean
    CompositeOperationsMonitor operationsMonitor(
        OperationsMonitoringProperties properties,
        OpsDataLayout layout,
        WindowsScServiceOperations windowsServices
    ) {
        boolean hostResourcesEnabled = properties.isHostResourcesEnabled();
        boolean fullMonitoringEnabled = properties.isEnabled();
        if (!hostResourcesEnabled && !fullMonitoringEnabled) {
            return new CompositeOperationsMonitor(false, List.of());
        }
        List<OperationsProbe> probes = new ArrayList<>();
        if (hostResourcesEnabled) {
            Path diskPath = properties.getDiskPath() == null
                ? layout.dataRoot()
                : absolute(properties.getDiskPath(), "monitoring disk path");
            probes.add(new SystemResourceProbe(
                diskPath,
                properties.getDiskDegradedPercent(),
                properties.getDiskDownPercent()
            ));
        }
        if (!fullMonitoringEnabled) {
            return new CompositeOperationsMonitor(true, probes);
        }
        validateMonitoring(properties);
        probes.add(new WindowsServiceProbe(windowsServices));
        probes.add(new HttpReadinessProbe(
            properties.getBackendReadinessUri(),
            properties.getBackendReadinessTimeout(),
            HttpClient.newBuilder()
                .connectTimeout(properties.getBackendReadinessTimeout())
                .build()
        ));
        probes.add(new JdbcDatabaseProbe(
            () -> DriverManager.getConnection(
                properties.getDatabaseUrl(),
                properties.getDatabaseUsername(),
                properties.getDatabasePassword()
            ),
            properties.getDatabaseTimeoutSeconds()
        ));
        probes.add(new FixedLogProbe(
            absolute(properties.getLogRoot(), "monitoring log root"),
            properties.getLogFiles(),
            properties.getMaximumLogTailBytes()
        ));
        return new CompositeOperationsMonitor(true, probes);
    }

    @Bean
    RemediationPolicy remediationPolicy(RemediationProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    RepositoryReleaseTerminalSource releaseTerminalSource(
        ReleaseWorkflowService releases
    ) {
        return new RepositoryReleaseTerminalSource(releases);
    }

    @Bean
    OperationsCoordinator operationsCoordinator(
        CompositeOperationsMonitor operationsMonitor,
        WindowsScServiceOperations windowsServices,
        NotificationPublisher notifications,
        RepositoryReleaseTerminalSource releases,
        OperationsStateStore stateStore,
        RemediationPolicy policy,
        Clock opsClock
    ) {
        return new OperationsCoordinator(
            operationsMonitor,
            windowsServices,
            notifications,
            releases,
            stateStore,
            policy,
            opsClock
        );
    }

    @Bean
    OperationsScheduler operationsScheduler(OperationsCoordinator operations) {
        return new OperationsScheduler(operations);
    }

    private static void validateMonitoring(OperationsMonitoringProperties properties) {
        Duration interval = properties.getInterval();
        if (interval == null || interval.compareTo(Duration.ofSeconds(10)) < 0
            || interval.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                "operations monitoring interval must be between 10 seconds and 10 minutes"
            );
        }
        Path expectedSc = Path.of(
            System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32",
            "sc.exe"
        ).toAbsolutePath().normalize();
        if (!expectedSc.equals(properties.getScExecutable().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("operations service monitor requires system sc.exe");
        }
        if (!OperationsMonitoringProperties.FIXED_DATABASE_URL.equals(
            properties.getDatabaseUrl()
        )
            || properties.getDatabaseUsername() == null
            || properties.getDatabaseUsername().isBlank()
            || properties.getDatabasePassword() == null
            || properties.getDatabasePassword().isBlank()) {
            throw new IllegalArgumentException(
                "operations database monitor requires the fixed loopback LeanTPM database identity"
            );
        }
        if (properties.getLogRoot() == null) {
            throw new IllegalArgumentException("operations monitoring log root is required");
        }
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
