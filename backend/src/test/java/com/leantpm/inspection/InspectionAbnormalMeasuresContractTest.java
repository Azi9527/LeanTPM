package com.leantpm.inspection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionAbnormalMeasuresContractTest {

    @Test
    void abnormalContractsExposeThreeMeasuresAndKeepThePublishedWebContract() {
        assertThat(componentNames(InspectionDtos.HandleAbnormalRequest.class))
                .contains(
                        "causeAnalysis", "temporaryAction", "permanentCountermeasure",
                        "finalResult", "targetStatus"
                );
        assertThat(componentNames(InspectionDtos.AbnormalRow.class))
                .contains(
                        "causeAnalysis", "temporaryAction", "permanentCountermeasure",
                        "finalResult"
                );
        assertThat(componentNames(InspectionDtos.TaskAbnormalExportRow.class))
                .contains(
                        "causeAnalysis", "temporaryAction", "permanentCountermeasure",
                        "finalResult"
                );
    }

    @Test
    void publishedWebCanStillAdvanceTheWorkflowUsingFinalResultAndTargetStatus()
            throws IOException {
        String mapper = source("src/main/resources/mapper/inspection/InspectionMapper.xml");
        String update = block(mapper, "<update id=\"handleAbnormal\">", "</update>");

        assertThat(update)
                .contains(
                        "#{request.finalResult}",
                        "#{request.targetStatus}",
                        "abnormal_status = CASE"
                );
    }

    @Test
    void appMeasuresEndpointOnlyUpdatesTheThreeRegistrationFields() throws IOException {
        assertThat(componentNames(InspectionDtos.RecordAbnormalMeasuresRequest.class))
                .containsExactly(
                        "causeAnalysis", "temporaryAction", "permanentCountermeasure", "version"
                );

        String controller = source("src/main/java/com/leantpm/inspection/InspectionController.java");
        String mapper = source("src/main/resources/mapper/inspection/InspectionMapper.xml");
        String update = block(
                mapper, "<update id=\"recordAbnormalMeasures\">", "</update>"
        );

        assertThat(controller)
                .contains("@PutMapping(\"/abnormalities/{id}/measures\")")
                .contains("hasAuthority('inspection:abnormal:handle')")
                .contains("taskService.recordAbnormalMeasures(id, request)");
        assertThat(update)
                .contains(
                        "cause_analysis = #{request.causeAnalysis}",
                        "temporary_action = #{request.temporaryAction}",
                        "permanent_countermeasure = #{request.permanentCountermeasure}",
                        "version = #{request.version}",
                        "AND abnormal_status IN ('OPEN','PROCESSING','PENDING_VERIFY')"
                )
                .doesNotContain(
                        "responsible_user_id", "due_time", "requested_equipment_status",
                        "SET abnormal_status"
                );
    }

    private String[] componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
    }

    private String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private String block(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex + end.length());
    }
}
