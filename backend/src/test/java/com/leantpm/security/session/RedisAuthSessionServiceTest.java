package com.leantpm.security.session;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisAuthSessionServiceTest {
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final JwtProperties properties = properties();
    private final RedisAuthSessionService service = new RedisAuthSessionService(redisTemplate, properties);

    @Test
    void rejectsLoginWhenDistributedFailureLimitIsReached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("5");

        assertThatThrownBy(() -> service.assertLoginAllowed(1L, "admin"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("LOGIN_TEMPORARILY_LOCKED");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    void reportsRedisOutageAsServiceUnavailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> service.assertLoginAllowed(1L, "admin"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("REDIS_UNAVAILABLE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    private JwtProperties properties() {
        JwtProperties value = new JwtProperties();
        value.setMaxLoginFailures(5);
        value.setFailureWindowMinutes(10);
        value.setRefreshTokenDays(7);
        return value;
    }
}
