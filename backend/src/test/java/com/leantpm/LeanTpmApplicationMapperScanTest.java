package com.leantpm;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;

class LeanTpmApplicationMapperScanTest {

    @Test
    void scansPersistentSecurityAndIdempotencyMappers() {
        MapperScan[] mapperScans =
                LeanTpmApplication.class.getAnnotationsByType(MapperScan.class);

        assertNotNull(mapperScans);
        assertTrue(Arrays.stream(mapperScans).anyMatch(scan ->
                Arrays.asList(scan.value()).contains("com.leantpm.security.session.mapper")));
        assertTrue(Arrays.stream(mapperScans).anyMatch(scan ->
                scan.annotationClass() == Mapper.class
                        && Arrays.asList(scan.value()).equals(
                                java.util.List.of("com.leantpm.common.idempotency"))));
    }
}
