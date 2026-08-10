package com.leantpm.opscontrol.operations;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leantpm.ops.remediation")
public class RemediationProperties {

    private boolean enabled;
    private int failureThreshold = 3;
    private Duration cooldown = Duration.ofMinutes(10);
    private int maximumAttemptsPerHour = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int value) { this.failureThreshold = value; }
    public Duration getCooldown() { return cooldown; }
    public void setCooldown(Duration value) { this.cooldown = value; }
    public int getMaximumAttemptsPerHour() { return maximumAttemptsPerHour; }
    public void setMaximumAttemptsPerHour(int value) { this.maximumAttemptsPerHour = value; }

    public RemediationPolicy toPolicy() {
        return new RemediationPolicy(
            enabled,
            failureThreshold,
            cooldown,
            maximumAttemptsPerHour
        );
    }
}
