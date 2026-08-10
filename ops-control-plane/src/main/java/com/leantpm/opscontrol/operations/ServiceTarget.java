package com.leantpm.opscontrol.operations;

public enum ServiceTarget {
    BACKEND(
        "service:backend", "LeanTPM Backend", "LeanTPM.Backend",
        RemediationAction.START_BACKEND
    ),
    CADDY(
        "service:caddy", "Caddy 公网入口", "caddy",
        RemediationAction.START_CADDY
    ),
    RELEASE_AGENT(
        "service:release-agent", "LeanTPM Release Agent", "LeanTPM.ReleaseAgent",
        RemediationAction.START_RELEASE_AGENT
    );

    private final String componentId;
    private final String label;
    private final String scmName;
    private final RemediationAction action;

    ServiceTarget(String componentId, String label, String scmName, RemediationAction action) {
        this.componentId = componentId;
        this.label = label;
        this.scmName = scmName;
        this.action = action;
    }

    public String componentId() { return componentId; }
    public String label() { return label; }
    public String scmName() { return scmName; }
    public RemediationAction action() { return action; }

    public static ServiceTarget forAction(RemediationAction action) {
        for (ServiceTarget target : values()) {
            if (target.action == action) {
                return target;
            }
        }
        throw new IllegalArgumentException("unsupported remediation action");
    }
}
