package com.leantpm.opscontrol.operations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WindowsServiceProbe implements OperationsProbe {

    private final WindowsScServiceOperations services;

    public WindowsServiceProbe(WindowsScServiceOperations services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public String id() {
        return "windows-services";
    }

    @Override
    public List<OperationsComponent> observe(Instant observedAt) {
        List<OperationsComponent> result = new ArrayList<>();
        for (ServiceTarget target : ServiceTarget.values()) {
            ServiceObservation observation = services.query(target);
            result.add(new OperationsComponent(
                target.componentId(), target.label(), OperationsComponentKind.SERVICE,
                observation.status(), observation.summary(), observedAt,
                Map.of("serviceId", target.scmName()),
                observation.status() == OperationsHealth.DOWN ? target.action() : null
            ));
        }
        return List.copyOf(result);
    }
}
