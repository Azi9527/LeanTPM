package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemResourceProbeTest {

    @TempDir
    Path diskPath;

    @Test
    void reportsResourcesFromTheHostRunningTheControlPlane() {
        OperationsComponent component = new SystemResourceProbe(diskPath, 85, 95)
            .observe(Instant.parse("2026-08-10T03:00:00Z"))
            .getFirst();

        assertThat(component.id()).isEqualTo("server:resources");
        assertThat(component.metrics())
            .containsKeys(
                "hostName",
                "osName",
                "cpuUsedPercent",
                "systemMemoryUsedPercent",
                "systemMemoryAvailableGiB",
                "diskUsedPercent",
                "diskUsableGiB",
                "jvmHeapUsedPercent",
                "availableProcessors",
                "runtimeUptimeSeconds"
            );
        assertPercentageOrUnknown(component, "cpuUsedPercent");
        assertPercentageOrUnknown(component, "systemMemoryUsedPercent");
        assertPercentage(component, "diskUsedPercent");
        assertPercentage(component, "jvmHeapUsedPercent");
        assertThat(component.metrics().get("hostName")).isNotBlank();
        assertThat(component.metrics().get("osName")).isNotBlank();
    }

    private static void assertPercentage(OperationsComponent component, String name) {
        assertThat(Integer.parseInt(component.metrics().get(name))).isBetween(0, 100);
    }

    private static void assertPercentageOrUnknown(
        OperationsComponent component,
        String name
    ) {
        String value = component.metrics().get(name);
        if (!"unknown".equals(value)) {
            assertThat(Integer.parseInt(value)).isBetween(0, 100);
        }
    }
}
