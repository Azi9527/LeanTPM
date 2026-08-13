package com.leantpm.inspection;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionStatisticsWorkbookTest {

    @Test
    void separatesUniqueTasksFromInspectionItemDetails() throws Exception {
        InspectionTaskService service = new InspectionTaskService(
                null, null, null, null, null, null, null, null
        );
        var firstItem = row("DJ-001", "ITEM-01", "油位");
        var secondItem = row("DJ-001", "ITEM-02", "温度");
        var thirdItem = row("DJ-002", "ITEM-03", "振动");

        Method method = InspectionTaskService.class.getDeclaredMethod(
                "statisticsWorkbook", List.class
        );
        method.setAccessible(true);
        byte[] content = (byte[]) method.invoke(
                service, List.of(firstItem, secondItem, thirdItem)
        );

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            var taskSheet = workbook.getSheet("任务清单");
            var itemSheet = workbook.getSheet("点检项目明细");
            assertThat(taskSheet.getLastRowNum()).isEqualTo(2);
            assertThat(itemSheet.getLastRowNum()).isEqualTo(3);
            assertThat(taskSheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(itemSheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(taskSheet.getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(itemSheet.getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(taskSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("DJ-001");
            assertThat(taskSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("DJ-002");
            assertThat(itemSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("ITEM-01");
            assertThat(itemSheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("ITEM-02");
            assertThat(itemSheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("ITEM-03");
        }
    }

    @Test
    void keepsBothHeaderOnlySheetsWhenNoTasksMatch() throws Exception {
        InspectionTaskService service = new InspectionTaskService(
                null, null, null, null, null, null, null, null
        );
        Method method = InspectionTaskService.class.getDeclaredMethod(
                "statisticsWorkbook", List.class
        );
        method.setAccessible(true);

        byte[] content = (byte[]) method.invoke(service, List.of());

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("任务清单");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("点检项目明细");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(workbook.getSheetAt(1).getLastRowNum()).isZero();
        }
    }

    private InspectionDtos.StatisticsTaskExportRow row(
            String taskCode,
            String itemCode,
            String itemName
    ) {
        return new InspectionDtos.StatisticsTaskExportRow(
                taskCode, "PLAN", "ON_TIME_COMPLETED", 0L,
                "装配车间", "EQ-01", "装配机器人", "日常点检",
                LocalDate.of(2026, 8, 13),
                LocalDateTime.of(2026, 8, 13, 7, 0),
                LocalDateTime.of(2026, 8, 13, 8, 0),
                LocalDateTime.of(2026, 8, 13, 7, 30),
                LocalDateTime.of(2026, 8, 13, 7, 30),
                "张三", "COMPLETED", itemCode, itemName, "传动部",
                "运行正常", "NORMAL", "NORMAL", BigDecimal.ONE,
                null, null, false, null, "张三",
                LocalDateTime.of(2026, 8, 13, 7, 30)
        );
    }
}
