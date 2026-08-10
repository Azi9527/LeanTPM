package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CompositeOperationsMonitor implements OperationsMonitor {

    private final boolean enabled;
    private final List<OperationsProbe> probes;

    public CompositeOperationsMonitor(boolean enabled, List<OperationsProbe> probes) {
        this.enabled = enabled;
        this.probes = probes == null ? List.of() : List.copyOf(probes);
        Set<String> ids = new HashSet<>();
        for (OperationsProbe probe : this.probes) {
            if (probe == null || probe.id() == null || probe.id().isBlank()
                || !ids.add(probe.id())) {
                throw new IllegalArgumentException("operations probe id is invalid or duplicated");
            }
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<OperationsComponent> observe(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        if (!enabled) {
            return List.of();
        }
        List<OperationsComponent> components = new ArrayList<>();
        for (OperationsProbe probe : probes) {
            try {
                List<OperationsComponent> values = probe.observe(observedAt);
                if (values == null || values.stream().anyMatch(Objects::isNull)) {
                    throw new IllegalStateException("probe returned invalid observations");
                }
                components.addAll(values);
            } catch (RuntimeException exception) {
                components.add(new OperationsComponent(
                    "probe:" + safeId(probe.id()),
                    "监控探针 " + probe.id(),
                    OperationsComponentKind.SERVER,
                    OperationsHealth.DEGRADED,
                    "监控采集失败；详细原因仅写入受保护的服务日志",
                    observedAt,
                    java.util.Map.of("probe", probe.id()),
                    null
                ));
            }
        }
        return List.copyOf(components);
    }

    private static String safeId(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }
}
