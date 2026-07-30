package com.leantpm.system.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.mapper.ChangeLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

@Service
public class ChangeLogService {
    private final ChangeLogMapper mapper;
    private final ObjectMapper objectMapper;

    public ChangeLogService(ChangeLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(
            String resourceType,
            Object resourceId,
            String operationType,
            Object before,
            Object after
    ) {
        var current = SecurityUtils.currentUser();
        JsonNode beforeNode = jsonNode(before);
        JsonNode afterNode = jsonNode(after);
        mapper.insertChangeLog(
                current.tenantId(),
                resourceType,
                String.valueOf(resourceId),
                operationType,
                json(beforeNode),
                json(afterNode),
                json(changedFields(beforeNode, afterNode)),
                current.userId(),
                current.username(),
                requestId()
        );
    }

    @Transactional(readOnly = true)
    public PageResult<ChangeLogDtos.ChangeLogRow> list(
            String resourceType,
            String keyword,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endTime = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findChangeLogs(
                        current.tenantId(),
                        clean(resourceType),
                        clean(keyword),
                        startTime,
                        endTime,
                        offset,
                        pageSize
                ),
                mapper.countChangeLogs(
                        current.tenantId(),
                        clean(resourceType),
                        clean(keyword),
                        startTime,
                        endTime
                ),
                page,
                pageSize
        );
    }

    private JsonNode jsonNode(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private List<String> changedFields(JsonNode before, JsonNode after) {
        if (before == null && after == null) {
            return List.of();
        }
        if (before == null) {
            return fieldNames(after);
        }
        if (after == null) {
            return fieldNames(before);
        }
        TreeSet<String> names = new TreeSet<>();
        before.fieldNames().forEachRemaining(names::add);
        after.fieldNames().forEachRemaining(names::add);
        return names.stream()
                .filter(name -> !Objects.equals(before.get(name), after.get(name)))
                .toList();
    }

    private List<String> fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of("value");
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        return names;
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "CHANGE_LOG_SERIALIZE_FAILED",
                    "数据变更日志序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String idempotencyKey = request.getHeader("Idempotency-Key");
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return idempotencyKey.substring(0, Math.min(128, idempotencyKey.length()));
            }
            String requestId = request.getHeader("X-Request-ID");
            if (requestId != null && !requestId.isBlank()) {
                return requestId.substring(0, Math.min(128, requestId.length()));
            }
        }
        return null;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
