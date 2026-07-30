package com.leantpm.common.idempotency;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Aspect
@Component
@Order(300)
public class IdempotencyAspect {
    private static final String PREFIX = "leantpm:idempotency:";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9:_-]{8,128}$");
    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = stringScript("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return existing
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return ''
            """);
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = longScript("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = longScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    public IdempotencyAspect(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            IdempotencyProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Around("@annotation(com.leantpm.common.idempotency.Idempotent)")
    public Object execute(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "写请求必须提供 Idempotency-Key",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BusinessException(
                    "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 格式不正确",
                    HttpStatus.BAD_REQUEST
            );
        }

        long tenantId = SecurityUtils.currentUser().tenantId();
        String redisKey = PREFIX + tenantId + ":" + sha256(idempotencyKey);
        String fingerprint = fingerprint(request, joinPoint.getArgs());
        String token = UUID.randomUUID().toString();
        String processingValue = "P|" + fingerprint + "|" + token;
        String existing = redis(() -> redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(redisKey),
                processingValue,
                Integer.toString(properties.getProcessingSeconds())
        ));

        if (existing != null && !existing.isEmpty()) {
            return existingResult(existing, fingerprint, joinPoint);
        }

        try {
            Object result = joinPoint.proceed();
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(result));
            String completedValue = "C|" + fingerprint + "|" + payload;
            Long completed = redis(() -> redisTemplate.execute(
                    COMPLETE_SCRIPT,
                    List.of(redisKey),
                    processingValue,
                    completedValue,
                    Long.toString(Duration.ofHours(properties.getCompletedHours()).toSeconds())
            ));
            if (completed == null || completed != 1L) {
                throw new BusinessException(
                        "IDEMPOTENCY_STATE_LOST",
                        "幂等状态已失效，请查询业务结果后重试",
                        HttpStatus.CONFLICT
                );
            }
            return result;
        } catch (Throwable failure) {
            redis(() -> redisTemplate.execute(
                    RELEASE_SCRIPT,
                    List.of(redisKey),
                    processingValue
            ));
            throw failure;
        }
    }

    private Object existingResult(
            String existing,
            String fingerprint,
            ProceedingJoinPoint joinPoint
    ) throws Exception {
        String[] parts = existing.split("\\|", 3);
        if (parts.length < 3 || !fingerprint.equals(parts[1])) {
            throw new BusinessException(
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "同一 Idempotency-Key 不能用于不同请求",
                    HttpStatus.CONFLICT
            );
        }
        if ("P".equals(parts[0])) {
            throw new BusinessException(
                    "REQUEST_IN_PROGRESS",
                    "相同请求正在处理中",
                    HttpStatus.CONFLICT
            );
        }
        if (!"C".equals(parts[0])) {
            throw new BusinessException(
                    "IDEMPOTENCY_STATE_INVALID",
                    "幂等状态无效",
                    HttpStatus.CONFLICT
            );
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        JavaType returnType = objectMapper.getTypeFactory()
                .constructType(method.getGenericReturnType());
        return objectMapper.readValue(Base64.getUrlDecoder().decode(parts[2]), returnType);
    }

    private String fingerprint(HttpServletRequest request, Object[] args) {
        try {
            List<Object> normalized = new ArrayList<>();
            for (Object argument : args) {
                Object value = normalizeArgument(argument);
                if (value != null) {
                    normalized.add(value);
                }
            }
            return sha256(
                    request.getMethod()
                            + "\n"
                            + request.getRequestURI()
                            + "\n"
                            + String.valueOf(request.getQueryString())
                            + "\n"
                            + objectMapper.writeValueAsString(normalized)
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    "REQUEST_FINGERPRINT_FAILED",
                    "请求指纹计算失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private Object normalizeArgument(Object argument) throws Exception {
        if (argument == null
                || argument instanceof ServletRequest
                || argument instanceof ServletResponse) {
            return null;
        }
        if (argument instanceof MultipartFile file) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return Map.of(
                    "fieldName", file.getName(),
                    "originalName", String.valueOf(file.getOriginalFilename()),
                    "contentType", String.valueOf(file.getContentType()),
                    "size", file.getSize(),
                    "sha256", HexFormat.of().formatHex(digest.digest())
            );
        }
        return argument;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new BusinessException(
                "REQUEST_CONTEXT_MISSING",
                "无法获取当前请求上下文",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
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
                    "幂等服务暂不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private static DefaultRedisScript<String> stringScript(String source) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(String.class);
        return script;
    }

    private static DefaultRedisScript<Long> longScript(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }
}
