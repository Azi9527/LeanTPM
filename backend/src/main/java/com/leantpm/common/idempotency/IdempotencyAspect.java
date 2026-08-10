package com.leantpm.common.idempotency;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.SecurityUtils;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Aspect
@Component
@Order(300)
public class IdempotencyAspect {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9:_-]{8,128}$");
    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;

    public IdempotencyAspect(
            IdempotencyStore store,
            ObjectMapper objectMapper,
            IdempotencyProperties properties
    ) {
        this.store = store;
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

        CurrentUser currentUser = SecurityUtils.currentUser();
        long tenantId = currentUser.tenantId();
        String keyHash = sha256(currentUser.userId() + ":" + idempotencyKey);
        String fingerprint = fingerprint(request, joinPoint.getArgs());
        String ownerToken = UUID.randomUUID().toString();
        IdempotencyStore.AcquireResult acquired = database(() -> store.acquire(
                tenantId,
                keyHash,
                fingerprint,
                ownerToken,
                properties.getProcessingSeconds(),
                properties.getCompletedHours()
        ));

        switch (acquired.outcome()) {
            case COMPLETED -> {
                return completedResult(acquired, joinPoint);
            }
            case CONFLICT -> throw new BusinessException(
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "同一 Idempotency-Key 不能用于不同请求",
                    HttpStatus.CONFLICT
            );
            case IN_PROGRESS -> throw new BusinessException(
                    "REQUEST_IN_PROGRESS",
                    "相同请求正在处理中",
                    HttpStatus.CONFLICT
            );
            case UNKNOWN -> throw new BusinessException(
                    "IDEMPOTENCY_RESULT_UNKNOWN",
                    "请求结果无法安全确认，请先查询业务结果",
                    HttpStatus.CONFLICT
            );
            case ACQUIRED -> {
                // Continue below. The acquisition transaction has already committed.
            }
        }

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            markUnknownOrThrow(
                    tenantId,
                    keyHash,
                    ownerToken,
                    acquired.fencingToken(),
                    failure
            );
            throw failure;
        }

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(result);
        } catch (Exception exception) {
            markUnknownOrThrow(
                    tenantId,
                    keyHash,
                    ownerToken,
                    acquired.fencingToken(),
                    exception
            );
            throw new BusinessException(
                    "IDEMPOTENCY_RESPONSE_SERIALIZATION_FAILED",
                    "业务已执行，但响应无法安全保存，请先查询业务结果",
                    HttpStatus.CONFLICT
            );
        }
        if (payload.length > properties.getMaxResponseBytes()) {
            markUnknownOrThrow(
                    tenantId,
                    keyHash,
                    ownerToken,
                    acquired.fencingToken(),
                    null
            );
            throw new BusinessException(
                    "IDEMPOTENCY_RESPONSE_TOO_LARGE",
                    "业务已执行，但响应超过幂等存储上限，请先查询业务结果",
                    HttpStatus.CONFLICT
            );
        }

        boolean completed = database(() -> store.complete(
                tenantId,
                keyHash,
                ownerToken,
                acquired.fencingToken(),
                HttpStatus.OK.value(),
                MediaType.APPLICATION_JSON_VALUE,
                payload,
                properties.getCompletedHours()
        ));
        if (!completed) {
            markUnknownOrThrow(
                    tenantId,
                    keyHash,
                    ownerToken,
                    acquired.fencingToken(),
                    null
            );
            throw new BusinessException(
                    "IDEMPOTENCY_STATE_LOST",
                    "业务已执行，但幂等状态无法确认，请先查询业务结果",
                    HttpStatus.CONFLICT
            );
        }
        return result;
    }

    private Object completedResult(
            IdempotencyStore.AcquireResult completed,
            ProceedingJoinPoint joinPoint
    ) throws Exception {
        if (!Integer.valueOf(HttpStatus.OK.value()).equals(completed.responseStatus())
                || !MediaType.APPLICATION_JSON_VALUE.equals(completed.responseContentType())
                || completed.responsePayload() == null) {
            throw new BusinessException(
                    "IDEMPOTENCY_STATE_INVALID",
                    "幂等响应状态无效，请联系管理员核验业务结果",
                    HttpStatus.CONFLICT
            );
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        JavaType returnType = objectMapper.getTypeFactory()
                .constructType(method.getGenericReturnType());
        return objectMapper.readValue(completed.responsePayload(), returnType);
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

    private void markUnknownOrThrow(
            long tenantId,
            String keyHash,
            String ownerToken,
            long fencingToken,
            Throwable originalFailure
    ) {
        boolean marked;
        try {
            marked = database(() -> store.markUnknown(
                    tenantId,
                    keyHash,
                    ownerToken,
                    fencingToken
            ));
        } catch (BusinessException stateFailure) {
            if (originalFailure != null) {
                stateFailure.addSuppressed(originalFailure);
            }
            throw stateFailure;
        }
        if (!marked) {
            BusinessException stateFailure = new BusinessException(
                    "IDEMPOTENCY_STATE_LOST",
                    "幂等状态无法转入待核验，请立即查询业务结果",
                    HttpStatus.CONFLICT
            );
            if (originalFailure != null) {
                stateFailure.addSuppressed(originalFailure);
            }
            throw stateFailure;
        }
    }

    private <T> T database(DatabaseOperation<T> operation) {
        try {
            return operation.get();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    "IDEMPOTENCY_UNAVAILABLE",
                    "幂等状态数据库暂不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    @FunctionalInterface
    private interface DatabaseOperation<T> {
        T get();
    }
}
