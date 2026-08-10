package com.leantpm.opscontrol.operations;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leantpm.ops.monitoring")
public class OperationsMonitoringProperties {

    public static final String FIXED_DATABASE_URL =
        "jdbc:mysql://127.0.0.1:3306/leantpm"
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true"
            + "&connectTimeout=5000&socketTimeout=5000";

    private boolean enabled;
    private boolean hostResourcesEnabled = true;
    private Duration interval = Duration.ofSeconds(30);
    private Path diskPath;
    private int diskDegradedPercent = 85;
    private int diskDownPercent = 95;
    private Path scExecutable = Path.of(
        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
        "System32",
        "sc.exe"
    );
    private Duration serviceTimeout = Duration.ofSeconds(5);
    private URI backendReadinessUri = URI.create(
        "http://127.0.0.1:18080/actuator/health/readiness"
    );
    private Duration backendReadinessTimeout = Duration.ofSeconds(5);
    private String databaseUrl = FIXED_DATABASE_URL;
    private String databaseUsername;
    private String databasePassword;
    private int databaseTimeoutSeconds = 5;
    private Path logRoot;
    private List<Path> logFiles = new ArrayList<>(List.of(
        Path.of("LeanTPM.Backend.wrapper.log"),
        Path.of("LeanTPM.Backend.err.log")
    ));
    private int maximumLogTailBytes = 256 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isHostResourcesEnabled() { return hostResourcesEnabled; }
    public void setHostResourcesEnabled(boolean value) { this.hostResourcesEnabled = value; }
    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = interval; }
    public Path getDiskPath() { return diskPath; }
    public void setDiskPath(Path diskPath) { this.diskPath = diskPath; }
    public int getDiskDegradedPercent() { return diskDegradedPercent; }
    public void setDiskDegradedPercent(int value) { this.diskDegradedPercent = value; }
    public int getDiskDownPercent() { return diskDownPercent; }
    public void setDiskDownPercent(int value) { this.diskDownPercent = value; }
    public Path getScExecutable() { return scExecutable; }
    public void setScExecutable(Path scExecutable) { this.scExecutable = scExecutable; }
    public Duration getServiceTimeout() { return serviceTimeout; }
    public void setServiceTimeout(Duration serviceTimeout) { this.serviceTimeout = serviceTimeout; }
    public URI getBackendReadinessUri() { return backendReadinessUri; }
    public void setBackendReadinessUri(URI value) { this.backendReadinessUri = value; }
    public Duration getBackendReadinessTimeout() { return backendReadinessTimeout; }
    public void setBackendReadinessTimeout(Duration value) { this.backendReadinessTimeout = value; }
    public String getDatabaseUrl() { return databaseUrl; }
    public void setDatabaseUrl(String value) { this.databaseUrl = value; }
    public String getDatabaseUsername() { return databaseUsername; }
    public void setDatabaseUsername(String value) { this.databaseUsername = value; }
    public String getDatabasePassword() { return databasePassword; }
    public void setDatabasePassword(String value) { this.databasePassword = value; }
    public int getDatabaseTimeoutSeconds() { return databaseTimeoutSeconds; }
    public void setDatabaseTimeoutSeconds(int value) { this.databaseTimeoutSeconds = value; }
    public Path getLogRoot() { return logRoot; }
    public void setLogRoot(Path logRoot) { this.logRoot = logRoot; }
    public List<Path> getLogFiles() { return logFiles; }
    public void setLogFiles(List<Path> value) {
        this.logFiles = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public int getMaximumLogTailBytes() { return maximumLogTailBytes; }
    public void setMaximumLogTailBytes(int value) { this.maximumLogTailBytes = value; }
}
