package com.leantpm.auth.service;

import com.leantpm.auth.dto.LoginRequest;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.service.ParameterService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaptchaServiceTest {
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ParameterService parameterService = mock(ParameterService.class);
    private final CaptchaService service = new CaptchaService(redisTemplate, parameterService);

    @Test
    void shouldReturnDisabledChallengeWhenSwitchIsOff() {
        when(parameterService.getBoolean(1L, "security.captcha.enabled", false))
                .thenReturn(false);

        var challenge = service.create();

        assertThat(challenge.enabled()).isFalse();
        assertThat(challenge.captchaId()).isNull();
        assertThat(challenge.imageDataUrl()).isNull();
    }

    @Test
    void shouldRejectMissingCodeWhenSwitchIsOn() {
        when(parameterService.getBoolean(1L, "security.captcha.enabled", false))
                .thenReturn(true);

        assertThatThrownBy(() -> service.verify(new LoginRequest(
                "admin",
                "password",
                null,
                null
        )))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("CAPTCHA_INVALID");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }
}
