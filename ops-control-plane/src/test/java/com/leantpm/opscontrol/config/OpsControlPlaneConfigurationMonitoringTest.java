package com.leantpm.opscontrol.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.leantpm.opscontrol.operations.OperationsMonitoringProperties;
import com.leantpm.opscontrol.operations.WindowsScServiceOperations;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpsControlPlaneConfigurationMonitoringTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void hostResourceFlagCanDisableMonitoringWithoutCallingWindowsServices() {
        OperationsMonitoringProperties properties = new OperationsMonitoringProperties();
        properties.setEnabled(false);
        properties.setHostResourcesEnabled(false);
        WindowsScServiceOperations windowsServices = mock(WindowsScServiceOperations.class);
        var layout = new OpsControlPlaneConfiguration.OpsDataLayout(
            temporaryRoot,
            temporaryRoot.resolve("uploads"),
            temporaryRoot.resolve("approvals"),
            temporaryRoot.resolve("state"),
            temporaryRoot.resolve("queue")
        );

        var monitor = new OpsControlPlaneConfiguration().operationsMonitor(
            properties,
            layout,
            windowsServices
        );

        assertThat(monitor.enabled()).isFalse();
        assertThat(monitor.observe(Instant.parse("2026-08-10T12:00:00Z"))).isEmpty();
        verifyNoInteractions(windowsServices);
    }
}
