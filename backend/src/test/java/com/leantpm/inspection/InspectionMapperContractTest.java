package com.leantpm.inspection;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionMapperContractTest {

    @Test
    void hourlyPlanGenerationPersistsTheNextScheduledTime() throws IOException {
        String mapper = new ClassPathResource(
                "mapper/inspection/InspectionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(mapper).contains("scheduled_time = #{nextScheduledTime}");
    }

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

    @Test
    void schemesReferenceLiveItemsAndEditableTasksRefreshBeforeSubmission() throws Exception {
        String xml = new ClassPathResource(
                "mapper/inspection/InspectionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        String schemeItems = block(xml, "<select id=\"findSchemeItems\"", "</select>");
        String copiedItems = block(xml, "<insert id=\"copyTaskItems\">", "</insert>");
        String liveColumns = block(
                xml,
                "<sql id=\"taskItemLiveOrSnapshotColumns\">",
                "</sql>"
        );
        String liveJoins = block(
                xml,
                "<sql id=\"taskItemLiveOrSnapshotJoins\">",
                "</sql>"
        );
        String refresh = block(
                xml,
                "<update id=\"refreshTaskItemSnapshotsFromSource\">",
                "</update>"
        );
        String bumpVersions = block(
                xml,
                "<update id=\"bumpEditableTaskVersionsForItem\">",
                "</update>"
        );

        assertThat(schemeItems)
                .contains("item.required_flag AS required_flag")
                .contains("item.photo_required_flag AS photo_required_flag")
                .doesNotContain("required_override", "photo_required_override",
                        "skip_allowed_override", "abnormal_stop_override");
        assertThat(copiedItems)
                .contains("item.required_flag")
                .contains("item.photo_required_flag")
                .doesNotContain("required_override", "photo_required_override",
                        "skip_allowed_override", "abnormal_stop_override");
        assertThat(liveJoins)
                .contains("JOIN inspection_task task")
                .contains("LEFT JOIN inspection_item source");
        assertThat(liveColumns)
                .contains("task.task_status IN ('PENDING','OVERDUE','IN_PROGRESS')");
        assertThat(refresh)
                .contains("UPDATE inspection_task_item item")
                .contains("JOIN inspection_item source")
                .contains("item.photo_required_flag = source.photo_required_flag")
                .contains("item.photo_min_count = source.photo_min_count");
        assertThat(bumpVersions)
                .contains("UPDATE inspection_task task")
                .contains("source_item_id = #{itemId}")
                .contains("task.version = task.version + 1")
                .contains("task.task_status IN ('PENDING','OVERDUE','IN_PROGRESS')");
    }

    @Test
    void abnormalHandlingKeepsNewMeasureFieldsAndPublishedWebWorkflowCompatibility()
            throws Exception {
        String xml = new ClassPathResource(
                "mapper/inspection/InspectionMapper.xml"
        ).getContentAsString(StandardCharsets.UTF_8);

        String handle = block(xml, "<update id=\"handleAbnormal\">", "</update>");
        assertThat(handle)
                .contains("cause_analysis = #{request.causeAnalysis}")
                .contains("temporary_action = #{request.temporaryAction}")
                .contains(
                        "permanent_countermeasure = COALESCE(#{request.permanentCountermeasure}, #{request.finalResult})",
                        "abnormal_status = CASE",
                        "#{request.targetStatus}",
                        "ELSE 'PROCESSING' END",
                        "closed_by = CASE",
                        "closed_time = CASE"
                );
    }

    private String block(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end + endToken.length());
    }
}
