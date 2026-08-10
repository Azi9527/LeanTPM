package com.leantpm.security.session;

import com.leantpm.security.JwtProperties;
import com.leantpm.security.session.mapper.AuthSessionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class AuthSecurityCleanupJob {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final AuthSessionMapper mapper;
    private final JwtProperties properties;

    public AuthSecurityCleanupJob(AuthSessionMapper mapper, JwtProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${leantpm.security.cleanup-interval-ms:300000}",
            initialDelayString = "${leantpm.security.cleanup-initial-delay-ms:300000}"
    )
    @Transactional
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now(DATABASE_ZONE);
        mapper.deleteStaleUnlockedLoginSecurityState(
                now,
                now.minusMinutes(properties.getLoginStateRetentionMinutes()),
                properties.getSecurityCleanupBatchSize()
        );
        mapper.deleteExpiredAuthSessions(
                now.minusDays(properties.getSessionStateRetentionDays()),
                properties.getSecurityCleanupBatchSize()
        );
    }
}
