package com.leantpm.auth.service;

import com.leantpm.auth.dto.CaptchaChallenge;
import com.leantpm.auth.dto.LoginRequest;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.service.ParameterService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CaptchaService {
    private static final String PARAMETER_KEY = "security.captcha.enabled";
    private static final String REDIS_PREFIX = "leantpm:auth:captcha:";
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = script("""
            local value = redis.call('GET', KEYS[1])
            if not value then
                return ''
            end
            redis.call('DEL', KEYS[1])
            return value
            """);

    private final StringRedisTemplate redisTemplate;
    private final ParameterService parameterService;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(
            StringRedisTemplate redisTemplate,
            ParameterService parameterService
    ) {
        this.redisTemplate = redisTemplate;
        this.parameterService = parameterService;
    }

    public CaptchaChallenge create() {
        if (!enabled()) {
            return CaptchaChallenge.disabled();
        }
        String captchaId = UUID.randomUUID().toString();
        String code = randomCode();
        redis(() -> {
            redisTemplate.opsForValue().set(
                    REDIS_PREFIX + captchaId,
                    sha256(code),
                    TTL
            );
            return null;
        });
        return new CaptchaChallenge(
                true,
                captchaId,
                svgDataUrl(code),
                Instant.now().plus(TTL)
        );
    }

    public void verify(LoginRequest request) {
        if (!enabled()) {
            return;
        }
        if (request.captchaId() == null
                || request.captchaId().isBlank()
                || request.captchaCode() == null
                || request.captchaCode().isBlank()) {
            throw captchaInvalid();
        }
        String expected = redis(() -> redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(REDIS_PREFIX + request.captchaId().trim())
        ));
        String actual = sha256(request.captchaCode().trim().toUpperCase());
        if (expected == null
                || expected.isBlank()
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII)
                )) {
            throw captchaInvalid();
        }
    }

    private boolean enabled() {
        return parameterService.getBoolean(
                AuthService.DEFAULT_TENANT_ID,
                PARAMETER_KEY,
                false
        );
    }

    private String randomCode() {
        StringBuilder value = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    private String svgDataUrl(String code) {
        StringBuilder glyphs = new StringBuilder();
        for (int index = 0; index < code.length(); index++) {
            int x = 25 + index * 34;
            int y = 39 + random.nextInt(9);
            int rotation = random.nextInt(25) - 12;
            glyphs.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" transform=\"rotate(").append(rotation).append(' ')
                    .append(x).append(' ').append(y)
                    .append(")\" font-size=\"27\" font-family=\"Arial\" font-weight=\"700\" fill=\"#163b4a\">")
                    .append(code.charAt(index))
                    .append("</text>");
        }
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="160" height="54" viewBox="0 0 160 54">
                  <rect width="160" height="54" rx="7" fill="#eef5f7"/>
                  <path d="M5 14L155 42M8 46L150 9M3 30L158 24" stroke="#8cb7c3" stroke-width="1" opacity=".55"/>
                  %s
                </svg>
                """.formatted(glyphs);
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T redis(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    "REDIS_UNAVAILABLE",
                    "验证码服务暂不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private BusinessException captchaInvalid() {
        return new BusinessException(
                "CAPTCHA_INVALID",
                "验证码错误或已过期",
                HttpStatus.BAD_REQUEST
        );
    }

    private static DefaultRedisScript<String> script(String source) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(String.class);
        return script;
    }
}
