package com.leantpm.security.session;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.IssuedTokenPair;
import com.leantpm.security.JwtProperties;
import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class RedisAuthSessionService {
    private static final String SESSION_PREFIX = "leantpm:auth:session:";
    private static final String REVOKED_PREFIX = "leantpm:auth:revoked-session:";
    private static final String TENANT_SESSIONS_PREFIX = "leantpm:auth:tenant-sessions:";
    private static final String USER_SESSIONS_PREFIX = "leantpm:auth:user-sessions:";
    private static final String LOGIN_FAILURE_PREFIX = "leantpm:auth:login-failure:";

    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = script("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return -2
            end
            local current = redis.call('HGET', KEYS[1], 'refreshTokenId')
            if not current then
                return 0
            end
            if current ~= ARGV[1] then
                return -1
            end
            redis.call(
                'HSET', KEYS[1],
                'refreshTokenId', ARGV[2],
                'lastActiveTime', ARGV[3],
                'expiresAt', ARGV[4]
            )
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return 1
            """);

    private static final DefaultRedisScript<Long> TOUCH_SCRIPT = script("""
            if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('HSET', KEYS[1], 'lastActiveTime', ARGV[1])
            return 1
            """);

    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = script("""
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return value
            """);

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties properties;

    public RedisAuthSessionService(StringRedisTemplate redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void register(
            CurrentUser user,
            IssuedTokenPair issued,
            String loginIp,
            String userAgent
    ) {
        redis(() -> {
            Instant now = Instant.now();
            String sessionKey = sessionKey(issued.sessionId());
            Map<String, String> values = new HashMap<>();
            values.put("sessionId", issued.sessionId());
            values.put("tenantId", Long.toString(user.tenantId()));
            values.put("userId", Long.toString(user.userId()));
            values.put("username", user.username());
            values.put("realName", safe(user.realName()));
            values.put("loginIp", safe(loginIp));
            values.put("userAgent", safe(userAgent));
            values.put("loginTime", now.toString());
            values.put("lastActiveTime", now.toString());
            values.put("expiresAt", issued.tokens().refreshExpiresAt().toString());
            values.put("refreshTokenId", issued.refreshTokenId());
            redisTemplate.delete(revokedKey(issued.sessionId()));
            redisTemplate.opsForHash().putAll(sessionKey, values);
            Duration ttl = ttl(issued.tokens().refreshExpiresAt());
            redisTemplate.expire(sessionKey, ttl);
            String tenantSet = tenantSessionsKey(user.tenantId());
            String userSet = userSessionsKey(user.tenantId(), user.userId());
            redisTemplate.opsForSet().add(tenantSet, issued.sessionId());
            redisTemplate.opsForSet().add(userSet, issued.sessionId());
            redisTemplate.expire(tenantSet, ttl.plusDays(1));
            redisTemplate.expire(userSet, ttl.plusDays(1));
            return true;
        });
    }

    public void validateAccess(Claims claims) {
        String sessionId = requiredClaim(claims, "sid");
        redis(() -> {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(revokedKey(sessionId)))) {
                throw unauthorized("TOKEN_REVOKED", "登录会话已退出或被强制下线");
            }
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
            if (values.isEmpty()) {
                throw unauthorized("TOKEN_SESSION_INVALID", "登录会话不存在或已过期");
            }
            long tenantId = numberClaim(claims, "tid");
            long userId = numberClaim(claims, "uid");
            if (!Long.toString(tenantId).equals(string(values.get("tenantId")))
                    || !Long.toString(userId).equals(string(values.get("userId")))) {
                throw unauthorized("TOKEN_SESSION_MISMATCH", "登录会话与令牌不匹配");
            }
            Long touched = redisTemplate.execute(
                    TOUCH_SCRIPT,
                    List.of(sessionKey(sessionId), revokedKey(sessionId)),
                    Instant.now().toString()
            );
            if (touched == null || touched != 1L) {
                throw unauthorized("TOKEN_REVOKED", "登录会话已退出或被强制下线");
            }
            return true;
        });
    }

    public void rotate(Claims previousClaims, IssuedTokenPair issued) {
        String sessionId = requiredClaim(previousClaims, "sid");
        String previousTokenId = previousClaims.getId();
        if (previousTokenId == null || previousTokenId.isBlank()
                || !sessionId.equals(issued.sessionId())) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "刷新令牌缺少会话标识");
        }
        redis(() -> {
            Long result = redisTemplate.execute(
                    ROTATE_SCRIPT,
                    List.of(sessionKey(sessionId), revokedKey(sessionId)),
                    previousTokenId,
                    issued.refreshTokenId(),
                    Instant.now().toString(),
                    issued.tokens().refreshExpiresAt().toString(),
                    Long.toString(ttl(issued.tokens().refreshExpiresAt()).toSeconds())
            );
            if (result == null || result == 0L) {
                throw unauthorized("TOKEN_SESSION_INVALID", "登录会话不存在或已过期");
            }
            if (result == -1L) {
                revoke(sessionId);
                throw unauthorized("REFRESH_TOKEN_REUSED", "刷新令牌已使用，会话已安全退出");
            }
            if (result == -2L) {
                throw unauthorized("TOKEN_REVOKED", "登录会话已退出或被强制下线");
            }
            return true;
        });
    }

    public void revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redis(() -> {
            String sessionKey = sessionKey(sessionId);
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey);
            Duration remaining = remaining(values.get("expiresAt"));
            redisTemplate.opsForValue().set(revokedKey(sessionId), "1", remaining);
            redisTemplate.delete(sessionKey);
            Long tenantId = parseLong(values.get("tenantId"));
            Long userId = parseLong(values.get("userId"));
            if (tenantId != null) {
                redisTemplate.opsForSet().remove(tenantSessionsKey(tenantId), sessionId);
            }
            if (tenantId != null && userId != null) {
                redisTemplate.opsForSet().remove(userSessionsKey(tenantId, userId), sessionId);
            }
            return true;
        });
    }

    public void revokeAllUserSessions(long tenantId, long userId) {
        redis(() -> {
            Set<String> sessions = redisTemplate.opsForSet().members(userSessionsKey(tenantId, userId));
            for (String sessionId : sessions == null ? Set.<String>of() : sessions) {
                revoke(sessionId);
            }
            return true;
        });
    }

    public List<OnlineSession> list(long tenantId, String currentSessionId) {
        return redis(() -> {
            String tenantSet = tenantSessionsKey(tenantId);
            Set<String> sessionIds = redisTemplate.opsForSet().members(tenantSet);
            List<OnlineSession> result = new ArrayList<>();
            for (String sessionId : sessionIds == null ? Set.<String>of() : sessionIds) {
                Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
                if (values.isEmpty()) {
                    redisTemplate.opsForSet().remove(tenantSet, sessionId);
                    continue;
                }
                result.add(toOnlineSession(values, sessionId.equals(currentSessionId)));
            }
            result.sort((left, right) -> right.lastActiveTime().compareTo(left.lastActiveTime()));
            return result;
        });
    }

    public void kickout(long tenantId, String currentSessionId, String targetSessionId) {
        if (targetSessionId.equals(currentSessionId)) {
            throw new BusinessException("CANNOT_KICKOUT_SELF", "当前会话请使用退出登录");
        }
        redis(() -> {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(targetSessionId));
            if (values.isEmpty() || !Long.toString(tenantId).equals(string(values.get("tenantId")))) {
                throw new BusinessException("ONLINE_SESSION_NOT_FOUND", "在线会话不存在", HttpStatus.NOT_FOUND);
            }
            revoke(targetSessionId);
            return true;
        });
    }

    public void assertLoginAllowed(long tenantId, String username) {
        redis(() -> {
            String value = redisTemplate.opsForValue().get(loginFailureKey(tenantId, username));
            int failures = value == null ? 0 : Integer.parseInt(value);
            if (failures >= properties.getMaxLoginFailures()) {
                throw locked();
            }
            return true;
        });
    }

    public void recordLoginFailure(long tenantId, String username) {
        redis(() -> {
            Long failures = redisTemplate.execute(
                    FAILURE_SCRIPT,
                    List.of(loginFailureKey(tenantId, username)),
                    Integer.toString(properties.getFailureWindowMinutes() * 60)
            );
            if (failures != null && failures >= properties.getMaxLoginFailures()) {
                throw locked();
            }
            return true;
        });
    }

    public void clearLoginFailures(long tenantId, String username) {
        redis(() -> redisTemplate.delete(loginFailureKey(tenantId, username)));
    }

    private OnlineSession toOnlineSession(Map<Object, Object> values, boolean currentSession) {
        return new OnlineSession(
                string(values.get("sessionId")),
                Long.parseLong(string(values.get("userId"))),
                string(values.get("username")),
                string(values.get("realName")),
                string(values.get("loginIp")),
                string(values.get("userAgent")),
                Instant.parse(string(values.get("loginTime"))),
                Instant.parse(string(values.get("lastActiveTime"))),
                Instant.parse(string(values.get("expiresAt"))),
                currentSession
        );
    }

    private Duration remaining(Object expiresAt) {
        try {
            Duration value = Duration.between(Instant.now(), Instant.parse(string(expiresAt)));
            return value.isNegative() || value.isZero() ? Duration.ofMinutes(1) : value;
        } catch (DateTimeParseException exception) {
            return Duration.ofDays(properties.getRefreshTokenDays());
        }
    }

    private Duration ttl(Instant expiresAt) {
        Duration value = Duration.between(Instant.now(), expiresAt);
        return value.isNegative() || value.isZero() ? Duration.ofSeconds(1) : value;
    }

    private String requiredClaim(Claims claims, String name) {
        String value = claims.get(name, String.class);
        if (value == null || value.isBlank()) {
            throw unauthorized("INVALID_TOKEN", "令牌缺少会话标识");
        }
        return value;
    }

    private long numberClaim(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        if (value == null) {
            throw unauthorized("INVALID_TOKEN", "令牌缺少用户标识");
        }
        return value.longValue();
    }

    private Long parseLong(Object value) {
        String text = string(value);
        return text.isBlank() ? null : Long.parseLong(text);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value.substring(0, Math.min(500, value.length()));
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String revokedKey(String sessionId) {
        return REVOKED_PREFIX + sessionId;
    }

    private String tenantSessionsKey(long tenantId) {
        return TENANT_SESSIONS_PREFIX + tenantId;
    }

    private String userSessionsKey(long tenantId, long userId) {
        return USER_SESSIONS_PREFIX + tenantId + ":" + userId;
    }

    private String loginFailureKey(long tenantId, String username) {
        return LOGIN_FAILURE_PREFIX + tenantId + ":" + username.trim().toLowerCase();
    }

    private <T> T redis(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    "REDIS_UNAVAILABLE",
                    "认证会话服务暂不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private BusinessException unauthorized(String code, String message) {
        return new BusinessException(code, message, HttpStatus.UNAUTHORIZED);
    }

    private BusinessException locked() {
        return new BusinessException(
                "LOGIN_TEMPORARILY_LOCKED",
                "登录失败次数过多，请稍后再试",
                HttpStatus.TOO_MANY_REQUESTS
        );
    }

    private static DefaultRedisScript<Long> script(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }
}
