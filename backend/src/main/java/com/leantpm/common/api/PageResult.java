package com.leantpm.common.api;

import java.util.List;

public record PageResult<T>(
        List<T> records,
        long total,
        int page,
        int pageSize
) {
    public static <T> PageResult<T> of(List<T> records, long total, int page, int pageSize) {
        return new PageResult<>(records, total, page, pageSize);
    }
}
