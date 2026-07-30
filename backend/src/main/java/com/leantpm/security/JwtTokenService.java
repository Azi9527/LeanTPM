package com.leantpm.security;

import com.leantpm.auth.dto.TokenPair;
import com.leantpm.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class JwtTokenService {
    private final JwtProperties properties;
    private final SecretKey key;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("LEANTPM_JWT_SECRET 必须设置为至少 32 字节的安全随机字符串");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public TokenPair issue(CurrentUser user) {
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plus(properties.getAccessTokenMinutes(), ChronoUnit.MINUTES);
        Instant refreshExpiresAt = now.plus(properties.getRefreshTokenDays(), ChronoUnit.DAYS);
        String accessToken = buildToken(user, "access", now, accessExpiresAt);
        String refreshToken = buildToken(user, "refresh", now, refreshExpiresAt);
        return new TokenPair(accessToken, refreshToken, accessExpiresAt, refreshExpiresAt);
    }

    private String buildToken(CurrentUser user, String tokenType, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .subject(user.username())
                .claim("uid", user.userId())
                .claim("tid", user.tenantId())
                .claim("name", user.realName())
                .claim("mustChangePassword", user.mustChangePassword())
                .claim("roles", user.roles())
                .claim("permissions", user.permissions())
                .claim("tokenType", tokenType)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (!expectedType.equals(claims.get("tokenType", String.class))) {
                throw new BusinessException("INVALID_TOKEN", "令牌类型错误", HttpStatus.UNAUTHORIZED);
            }
            return claims;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException("INVALID_TOKEN", "令牌无效或已过期", HttpStatus.UNAUTHORIZED);
        }
    }

    public CurrentUser toCurrentUser(Claims claims) {
        return new CurrentUser(
                claims.get("uid", Number.class).longValue(),
                claims.get("tid", Number.class).longValue(),
                claims.getSubject(),
                claims.get("name", String.class),
                Boolean.TRUE.equals(claims.get("mustChangePassword", Boolean.class)),
                stringSet(claims.get("roles")),
                stringSet(claims.get("permissions"))
        );
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return Set.copyOf(result);
    }
}
