package com.leantpm.opscontrol.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "leantpm.ops")
public class OpsControlPlaneProperties {

    @NotNull
    private Path dataRoot;

    @Min(1024 * 1024)
    private long maximumUploadBytes = 512L * 1024L * 1024L;

    @Min(1)
    @Max(2)
    private int requiredApprovals = 2;

    @NotNull
    private Path powershellExecutable;

    @NotNull
    private Path verifierScript;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    private String verifierScriptSha256;

    @NotNull
    private Path bundleVerifierScript;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    private String bundleVerifierScriptSha256;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    private String deploymentBundleSchemaSha256;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    private String approvalVerifierScriptSha256;

    @NotNull
    private Path releaseTrustConfigPath;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{40}$")
    private String trustedCertificateThumbprint;

    @NotNull
    private Duration verifierTimeout = Duration.ofMinutes(2);

    @NotNull
    private Duration agentHeartbeatMaximumAge = Duration.ofSeconds(30);

    @Min(1024)
    @Max(1024 * 1024)
    private int verifierMaximumOutputBytes = 256 * 1024;

    @NotNull
    private Path hostLayoutPath;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$")
    private String hostLayoutSha256;

    @NotNull
    private Path currentReleasePointer;

    @NotEmpty
    private Map<String, String> operatorTokenSha256 = new LinkedHashMap<>();

    public Path getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(Path dataRoot) {
        this.dataRoot = dataRoot;
    }

    public long getMaximumUploadBytes() {
        return maximumUploadBytes;
    }

    public void setMaximumUploadBytes(long maximumUploadBytes) {
        this.maximumUploadBytes = maximumUploadBytes;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public void setRequiredApprovals(int requiredApprovals) {
        this.requiredApprovals = requiredApprovals;
    }

    public Path getPowershellExecutable() {
        return powershellExecutable;
    }

    public void setPowershellExecutable(Path powershellExecutable) {
        this.powershellExecutable = powershellExecutable;
    }

    public Path getVerifierScript() {
        return verifierScript;
    }

    public void setVerifierScript(Path verifierScript) {
        this.verifierScript = verifierScript;
    }

    public String getVerifierScriptSha256() {
        return verifierScriptSha256;
    }

    public void setVerifierScriptSha256(String verifierScriptSha256) {
        this.verifierScriptSha256 = verifierScriptSha256;
    }

    public Path getBundleVerifierScript() {
        return bundleVerifierScript;
    }

    public void setBundleVerifierScript(Path bundleVerifierScript) {
        this.bundleVerifierScript = bundleVerifierScript;
    }

    public String getBundleVerifierScriptSha256() {
        return bundleVerifierScriptSha256;
    }

    public void setBundleVerifierScriptSha256(String bundleVerifierScriptSha256) {
        this.bundleVerifierScriptSha256 = bundleVerifierScriptSha256;
    }

    public String getDeploymentBundleSchemaSha256() {
        return deploymentBundleSchemaSha256;
    }

    public void setDeploymentBundleSchemaSha256(String deploymentBundleSchemaSha256) {
        this.deploymentBundleSchemaSha256 = deploymentBundleSchemaSha256;
    }

    public String getApprovalVerifierScriptSha256() {
        return approvalVerifierScriptSha256;
    }

    public void setApprovalVerifierScriptSha256(String approvalVerifierScriptSha256) {
        this.approvalVerifierScriptSha256 = approvalVerifierScriptSha256;
    }

    public Path getReleaseTrustConfigPath() {
        return releaseTrustConfigPath;
    }

    public void setReleaseTrustConfigPath(Path releaseTrustConfigPath) {
        this.releaseTrustConfigPath = releaseTrustConfigPath;
    }

    public String getTrustedCertificateThumbprint() {
        return trustedCertificateThumbprint;
    }

    public void setTrustedCertificateThumbprint(String trustedCertificateThumbprint) {
        this.trustedCertificateThumbprint = trustedCertificateThumbprint;
    }

    public Duration getVerifierTimeout() {
        return verifierTimeout;
    }

    public void setVerifierTimeout(Duration verifierTimeout) {
        this.verifierTimeout = verifierTimeout;
    }

    public Duration getAgentHeartbeatMaximumAge() {
        return agentHeartbeatMaximumAge;
    }

    public void setAgentHeartbeatMaximumAge(Duration agentHeartbeatMaximumAge) {
        this.agentHeartbeatMaximumAge = agentHeartbeatMaximumAge;
    }

    public int getVerifierMaximumOutputBytes() {
        return verifierMaximumOutputBytes;
    }

    public void setVerifierMaximumOutputBytes(int verifierMaximumOutputBytes) {
        this.verifierMaximumOutputBytes = verifierMaximumOutputBytes;
    }

    public Path getHostLayoutPath() {
        return hostLayoutPath;
    }

    public void setHostLayoutPath(Path hostLayoutPath) {
        this.hostLayoutPath = hostLayoutPath;
    }

    public String getHostLayoutSha256() {
        return hostLayoutSha256;
    }

    public void setHostLayoutSha256(String hostLayoutSha256) {
        this.hostLayoutSha256 = hostLayoutSha256;
    }

    public Path getCurrentReleasePointer() {
        return currentReleasePointer;
    }

    public void setCurrentReleasePointer(Path currentReleasePointer) {
        this.currentReleasePointer = currentReleasePointer;
    }

    public Map<String, String> getOperatorTokenSha256() {
        return operatorTokenSha256;
    }

    public void setOperatorTokenSha256(Map<String, String> operatorTokenSha256) {
        this.operatorTokenSha256 = operatorTokenSha256;
    }
}
