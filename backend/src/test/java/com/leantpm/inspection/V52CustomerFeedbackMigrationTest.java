package com.leantpm.inspection;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class V52CustomerFeedbackMigrationTest {

    @Test
    void seedsCameraOnlyPolicyAndFiveTopLevelEquipmentCategoriesForEveryActiveTenant()
            throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V52__customer_feedback_defaults.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(sql)
                .contains("mobile.photo-allow-album-selection")
                .contains("'false'")
                .contains("'PRODUCTION' AS category_code")
                .contains("'生产设备' AS category_name")
                .contains("'ENVIRONMENTAL_EQUIPMENT', '环保设备'")
                .contains("'AUXILIARY_EQUIPMENT', '辅助设备'")
                .contains("'TRANSPORT_EQUIPMENT', '运输设备'")
                .contains("'OTHER_EQUIPMENT', '其它设备'")
                .contains("FROM system_tenant tenant")
                .contains("tenant.status = 1")
                .contains("tenant.deleted = 0")
                .contains("parent_id")
                .contains("ON DUPLICATE KEY UPDATE");
    }
}
