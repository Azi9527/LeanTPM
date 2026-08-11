package com.leantpm.inspection;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class V51InspectionItemCategoryMigrationTest {

    @Test
    void seedsTheSixTenantScopedInspectionItemCategoriesWithoutRewritingBusinessRows()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V51__inspection_item_category_dictionary.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("inspection_item_category")
                .contains("TRANSMISSION", "传动系统")
                .contains("LUBRICATION", "润滑系统")
                .contains("FASTENING", "紧固系统")
                .contains("ELECTRICAL", "电气系统")
                .contains("SAFETY", "安全防护")
                .contains("OTHER", "其它")
                .contains("FROM system_tenant")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("status = 1")
                .contains("deleted = 0")
                .doesNotContain("UPDATE inspection_item")
                .doesNotContain("DELETE FROM inspection_item");
    }
}
