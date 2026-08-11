package com.leantpm.inspection;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionMapperContractTest {

    @Test
    void onlyPlanActivationRequiresALiveScheme() throws Exception {
        String xml = new ClassPathResource(
                "mapper/inspection/InspectionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        String updateItem = block(xml, "<update id=\"updateItem\">", "</update>");
        String updatePlan = block(xml, "<update id=\"updatePlanStatus\">", "</update>");

        assertThat(updateItem).doesNotContain("request.planStatus", "inspection_plan");
        assertThat(updatePlan)
                .contains("request.planStatus")
                .contains("FROM inspection_scheme scheme")
                .contains("scheme.deleted = 0");
    }

    @Test
    void attachmentValidationIdentifiesTheItemAndIgnoresAllowedSkips() throws Exception {
        String xml = new ClassPathResource(
                "mapper/inspection/InspectionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        String validation = block(
                xml,
                "<select id=\"findInvalidResultAttachments\"",
                "</select>"
        );
        assertThat(validation)
                .contains("item.item_name")
                .contains("item.sort_order")
                .contains("result.skipped_flag = 0")
                .contains("actual_count")
                .contains("oversized_count")
                .contains("unsupported_type_count");
    }

    private String block(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end + endToken.length());
    }
}
