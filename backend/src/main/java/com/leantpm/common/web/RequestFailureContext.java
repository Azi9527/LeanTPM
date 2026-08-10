package com.leantpm.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class RequestFailureContext {
    public static final String RESPONSE_HEADER = "X-Correlation-Id";

    private static final String ATTRIBUTE_PREFIX = RequestFailureContext.class.getName() + ".";
    private static final String CORRELATION_ID_ATTRIBUTE = ATTRIBUTE_PREFIX + "correlationId";
    private static final String ERROR_CODE_ATTRIBUTE = ATTRIBUTE_PREFIX + "errorCode";
    private static final String ERROR_MESSAGE_ATTRIBUTE = ATTRIBUTE_PREFIX + "errorMessage";
    private static final int MAX_OPERATION_MESSAGE_LENGTH = 320;

    private RequestFailureContext() {
    }

    public static String ensureCorrelationId(HttpServletRequest request) {
        Object existing = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (existing instanceof String correlationId && !correlationId.isBlank()) {
            return correlationId;
        }

        String correlationId = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        return correlationId;
    }

    public static String record(HttpServletRequest request, String code, String message) {
        String correlationId = ensureCorrelationId(request);
        request.setAttribute(ERROR_CODE_ATTRIBUTE, normalizeCode(code));
        request.setAttribute(ERROR_MESSAGE_ATTRIBUTE, normalizeMessage(message));
        return correlationId;
    }

    public static String operationLogError(HttpServletRequest request) {
        Object code = request.getAttribute(ERROR_CODE_ATTRIBUTE);
        Object message = request.getAttribute(ERROR_MESSAGE_ATTRIBUTE);
        if (!(code instanceof String safeCode) || !(message instanceof String safeMessage)) {
            return null;
        }

        return safeCode
                + " [错误编号："
                + ensureCorrelationId(request)
                + "]："
                + safeMessage;
    }

    private static String normalizeCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
            return "REQUEST_FAILED";
        }
        return code;
    }

    private static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= MAX_OPERATION_MESSAGE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_OPERATION_MESSAGE_LENGTH);
    }
}
