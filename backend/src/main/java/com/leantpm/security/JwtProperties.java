package com.leantpm.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "leantpm.security")
public class JwtProperties {
    private String jwtSecret;
    private long accessTokenMinutes = 30;
    private long refreshTokenDays = 7;
    private int maxLoginFailures = 5;
    private int maxLoginSourceFailures = 50;
    private int failureWindowMinutes = 10;
    private int loginStateRetentionMinutes = 1440;
    private int sessionStateRetentionDays = 30;
    private int securityCleanupBatchSize = 500;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getAccessTokenMinutes() {
        return accessTokenMinutes;
    }

    public void setAccessTokenMinutes(long accessTokenMinutes) {
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public long getRefreshTokenDays() {
        return refreshTokenDays;
    }

    public void setRefreshTokenDays(long refreshTokenDays) {
        this.refreshTokenDays = refreshTokenDays;
    }

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public void setMaxLoginFailures(int maxLoginFailures) {
        this.maxLoginFailures = maxLoginFailures;
    }

    public int getMaxLoginSourceFailures() {
        return maxLoginSourceFailures;
    }

    public void setMaxLoginSourceFailures(int maxLoginSourceFailures) {
        this.maxLoginSourceFailures = maxLoginSourceFailures;
    }

    public int getFailureWindowMinutes() {
        return failureWindowMinutes;
    }

    public void setFailureWindowMinutes(int failureWindowMinutes) {
        this.failureWindowMinutes = failureWindowMinutes;
    }

    public int getLoginStateRetentionMinutes() {
        return loginStateRetentionMinutes;
    }

    public void setLoginStateRetentionMinutes(int loginStateRetentionMinutes) {
        this.loginStateRetentionMinutes = loginStateRetentionMinutes;
    }

    public int getSessionStateRetentionDays() {
        return sessionStateRetentionDays;
    }

    public void setSessionStateRetentionDays(int sessionStateRetentionDays) {
        this.sessionStateRetentionDays = sessionStateRetentionDays;
    }

    public int getSecurityCleanupBatchSize() {
        return securityCleanupBatchSize;
    }

    public void setSecurityCleanupBatchSize(int securityCleanupBatchSize) {
        this.securityCleanupBatchSize = securityCleanupBatchSize;
    }
}
