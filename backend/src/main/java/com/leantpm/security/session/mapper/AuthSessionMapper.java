package com.leantpm.security.session.mapper;

import com.leantpm.security.session.domain.AuthSessionRecord;
import com.leantpm.security.session.domain.LoginSecurityState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuthSessionMapper {
    int insertSession(AuthSessionRecord session);

    Long findActiveUserVersionForUpdate(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId
    );

    AuthSessionRecord findSession(@Param("sessionId") String sessionId);

    AuthSessionRecord findSessionForUpdate(@Param("sessionId") String sessionId);

    int touchActiveSession(
            @Param("sessionId") String sessionId,
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("userVersion") long userVersion,
            @Param("now") LocalDateTime now
    );

    int rotateRefresh(
            @Param("sessionId") String sessionId,
            @Param("refreshJtiHash") String refreshJtiHash,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("now") LocalDateTime now,
            @Param("version") long version
    );

    int revokeSession(
            @Param("sessionId") String sessionId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now,
            @Param("version") long version
    );

    int revokeSessionById(
            @Param("sessionId") String sessionId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    int revokeAllUserSessions(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    int revokeTenantSession(
            @Param("tenantId") long tenantId,
            @Param("sessionId") String sessionId,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    List<AuthSessionRecord> findActiveTenantSessions(
            @Param("tenantId") long tenantId,
            @Param("now") LocalDateTime now
    );

    LoginSecurityState findLoginSecurityState(
            @Param("tenantId") long tenantId,
            @Param("principalKey") String principalKey
    );

    int ensureLoginSecurityState(
            @Param("tenantId") long tenantId,
            @Param("principalKey") String principalKey,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    LoginSecurityState findLoginSecurityStateForUpdate(
            @Param("tenantId") long tenantId,
            @Param("principalKey") String principalKey
    );

    int upsertLoginFailure(
            @Param("tenantId") long tenantId,
            @Param("principalKey") String principalKey,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("windowEnd") LocalDateTime windowEnd,
            @Param("maxFailures") int maxFailures
    );

    int deleteLoginSecurityState(
            @Param("tenantId") long tenantId,
            @Param("principalKey") String principalKey
    );

    int deleteStaleUnlockedLoginSecurityState(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("batchSize") int batchSize
    );

    int deleteExpiredAuthSessions(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("batchSize") int batchSize
    );
}
