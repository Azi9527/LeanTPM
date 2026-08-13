package com.leantpm.inspection;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionStatisticsManagementContractTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void statisticsQueryTracksSourceTimelinessAndOrganizationDescendants() throws Exception {
        String dto = read("src/main/java/com/leantpm/inspection/InspectionDtos.java");
        String mapper = read("src/main/resources/mapper/inspection/InspectionMapper.xml");

        assertThat(dto).contains("record StatisticsQuery(");
        assertThat(dto).contains("String sourceType", "String timelinessStatus");
        assertThat(mapper).contains("WITH RECURSIVE selected_organization_tree AS");
        assertThat(mapper).contains("task.source_type = #{query.sourceType}");
        assertThat(mapper).contains("query.timelinessStatus == 'ON_TIME_COMPLETED'");
        assertThat(mapper).contains("query.timelinessStatus == 'LATE_COMPLETED'");
        assertThat(mapper).contains("query.timelinessStatus == 'OVERDUE_INCOMPLETE'");
        assertThat(mapper).contains("COALESCE(task.submitted_time, task.completed_time)");
        assertThat(mapper).contains("<include refid=\"equipmentScope\"");
    }

    @Test
    void statisticsExposeDedicatedTaskListAndFilteredDetailExport() throws Exception {
        String controller = read("src/main/java/com/leantpm/inspection/InspectionController.java");
        String service = read("src/main/java/com/leantpm/inspection/InspectionTaskService.java");

        assertThat(controller).contains("@GetMapping(\"/statistics/tasks\")");
        assertThat(controller).contains("@GetMapping(\"/statistics/export\")");
        assertThat(controller).contains("inspection:statistics:view");
        assertThat(service).contains("statisticsTasks(", "exportStatisticsDetails(");
        assertThat(service).contains("任务来源", "完成时效", "逾期分钟");
        assertThat(service).contains("createSheet(\"任务清单\")");
        assertThat(service).contains("createSheet(\"点检项目明细\")");
    }
}
