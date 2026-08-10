package com.leantpm.opscontrol.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeOperationsMonitorTest {

    @Test
    void isolatesProbeFailureAndKeepsOtherObservations() {
        Instant now = Instant.parse("2026-08-10T02:00:00Z");
        OperationsProbe healthy = new OperationsProbe() {
            @Override public String id() { return "server"; }
            @Override public List<OperationsComponent> observe(Instant observedAt) {
                return List.of(new OperationsComponent(
                    "server:resources", "服务器资源", OperationsComponentKind.SERVER,
                    OperationsHealth.HEALTHY, "资源正常", observedAt,
                    java.util.Map.of("diskUsedPercent", "20"), null
                ));
            }
        };
        OperationsProbe broken = new OperationsProbe() {
            @Override public String id() { return "database"; }
            @Override public List<OperationsComponent> observe(Instant observedAt) {
                throw new IllegalStateException("secret=mysql-password");
            }
        };

        var observations = new CompositeOperationsMonitor(true, List.of(healthy, broken))
            .observe(now);

        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).status()).isEqualTo(OperationsHealth.HEALTHY);
        assertThat(observations.get(1).id()).isEqualTo("probe:database");
        assertThat(observations.get(1).status()).isEqualTo(OperationsHealth.DEGRADED);
        assertThat(observations.get(1).summary()).doesNotContain("mysql-password");
    }
}
