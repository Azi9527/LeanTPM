package com.leantpm.common.excel;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ImportWorkbookSupportTest {
    @Test
    void rendersRequiredMarkerAndCanonicalizesOldAndNewHeaders() {
        assertThat(ImportWorkbookSupport.displayHeader(
                "设备名称", Set.of("设备名称")
        )).isEqualTo("*设备名称");
        assertThat(ImportWorkbookSupport.canonicalHeader("*设备名称"))
                .isEqualTo("设备名称");
        assertThat(ImportWorkbookSupport.canonicalHeader("设备名称"))
                .isEqualTo("设备名称");
    }
}
