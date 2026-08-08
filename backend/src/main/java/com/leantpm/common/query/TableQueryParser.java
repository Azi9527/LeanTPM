package com.leantpm.common.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class TableQueryParser {
    private static final int MAX_FILTERS = 20;
    private static final int MAX_VALUE_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public TableQueryParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TableQuery parse(
            String filtersJson,
            String sortBy,
            String sortDirection,
            Set<String> allowedFields
    ) {
        ParsedFilterGroup group = readGroup(filtersJson);
        List<TableQuery.Filter> filters = new ArrayList<>();
        if (group.filters() != null) {
            if (group.filters().size() > MAX_FILTERS) {
                throw invalid("复合查询最多支持 " + MAX_FILTERS + " 个条件");
            }
            for (ParsedFilter requested : group.filters()) {
                filters.add(validateFilter(requested, allowedFields));
            }
        }
        String normalizedSort = clean(sortBy);
        if (normalizedSort != null && !allowedFields.contains(normalizedSort)) {
            throw invalid("不支持按字段 " + normalizedSort + " 排序");
        }
        return new TableQuery(
                parseEnum(TableQuery.Logic.class, group.logic(), TableQuery.Logic.AND, "组合方式"),
                filters,
                normalizedSort,
                parseEnum(TableQuery.SortDirection.class, sortDirection, null, "排序方向")
        );
    }

    private ParsedFilterGroup readGroup(String filtersJson) {
        if (clean(filtersJson) == null) return new ParsedFilterGroup("AND", List.of());
        try {
            return objectMapper.readValue(filtersJson, ParsedFilterGroup.class);
        } catch (JsonProcessingException exception) {
            throw invalid("表头查询条件格式错误");
        }
    }

    private TableQuery.Filter validateFilter(ParsedFilter requested, Set<String> allowedFields) {
        String field = clean(requested.field());
        if (field == null || !allowedFields.contains(field)) {
            throw invalid("不支持筛选字段 " + (field == null ? "（空）" : field));
        }
        TableQuery.Operator operator = parseEnum(
                TableQuery.Operator.class, requested.operator(), null, "筛选操作符"
        );
        if (operator == null) throw invalid("筛选操作符不能为空");
        String value = bounded(requested.value());
        List<String> values = requested.values() == null
                ? List.of()
                : requested.values().stream().map(this::bounded).toList();
        if (operator == TableQuery.Operator.BETWEEN) {
            if (values.size() != 2 || values.stream().anyMatch(item -> item == null)) {
                throw invalid("区间查询必须提供开始值和结束值");
            }
        } else if (operator != TableQuery.Operator.EMPTY
                && operator != TableQuery.Operator.NOT_EMPTY
                && value == null) {
            throw invalid("筛选值不能为空");
        }
        return new TableQuery.Filter(field, operator, value, values);
    }

    private String bounded(String value) {
        String cleaned = clean(value);
        if (cleaned != null && cleaned.length() > MAX_VALUE_LENGTH) {
            throw invalid("单个筛选值不能超过 " + MAX_VALUE_LENGTH + " 个字符");
        }
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static <T extends Enum<T>> T parseEnum(
            Class<T> type, String value, T fallback, String label
    ) {
        String cleaned = clean(value);
        if (cleaned == null) return fallback;
        try {
            return Enum.valueOf(type, cleaned.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(label + "不受支持");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("TABLE_QUERY_INVALID", message);
    }

    private record ParsedFilterGroup(String logic, List<ParsedFilter> filters) {
    }

    private record ParsedFilter(String field, String operator, String value, List<String> values) {
    }
}
