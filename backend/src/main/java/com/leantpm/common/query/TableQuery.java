package com.leantpm.common.query;

import java.util.List;

/**
 * 由表头复合查询生成的、已经过服务端白名单校验的数据库查询条件。
 * 字段名和操作符永远不能直接拼接客户端原始值，Mapper 必须使用 choose 显式映射列。
 */
public record TableQuery(
        Logic logic,
        List<Filter> filters,
        String sortBy,
        SortDirection sortDirection
) {
    public TableQuery {
        logic = logic == null ? Logic.AND : logic;
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public static TableQuery empty() {
        return new TableQuery(Logic.AND, List.of(), null, null);
    }

    public boolean active() {
        return !filters.isEmpty() || sortBy != null;
    }

    public enum Logic { AND, OR }

    public enum SortDirection { ASC, DESC }

    public enum Operator {
        CONTAINS,
        NOT_CONTAINS,
        EQ,
        NE,
        STARTS_WITH,
        ENDS_WITH,
        GT,
        GTE,
        LT,
        LTE,
        BETWEEN,
        EMPTY,
        NOT_EMPTY
    }

    public record Filter(
            String field,
            Operator operator,
            String value,
            List<String> values
    ) {
        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
