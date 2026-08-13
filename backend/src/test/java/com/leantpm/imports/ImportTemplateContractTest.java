package com.leantpm.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.equipment.EquipmentMapper;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.foundation.service.ParameterService;
import com.leantpm.inspection.InspectionCalendarMapper;
import com.leantpm.inspection.InspectionCatalogService;
import com.leantpm.inspection.InspectionImportService;
import com.leantpm.inspection.InspectionMapper;
import com.leantpm.masterdata.MasterDataMapper;
import com.leantpm.oee.OeeImportService;
import com.leantpm.oee.OeeMapper;
import com.leantpm.oee.OeeService;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import com.leantpm.system.mapper.SystemMapper;
import com.leantpm.system.service.SystemService;
import com.leantpm.system.service.UserImportService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ImportTemplateContractTest {
    @Test
    void everyImportTemplateMarksRequiredColumns() throws Exception {
        try (var equipment = workbook(equipmentService().importTemplate());
             var users = workbook(userImportService().template());
             var oee = workbook(oeeImportService().template());
             var inspection = workbook(inspectionImportService().template())) {
            assertHeaders(equipment.getSheetAt(0),
                    "*设备名称", "*设备分类", "*所属组织");
            assertHeaders(users.getSheet("用户导入"),
                    "*账号", "*姓名", "*组织编码", "*角色编码列表", "*处理策略");
            assertThat(headers(oee.getSheet("OEE数据")))
                    .allMatch(header -> header.startsWith("*"));
            assertHeaders(inspection.getSheet("点检项目"),
                    "*项目编码", "*项目名称", "*点检内容", "*点检标准", "*结果类型");
            assertThat(inspection.getSheet("填写规范")).isNotNull();
        }
    }

    @Test
    void equipmentTemplateUsesChineseBusinessTermsAndExamples() throws Exception {
        try (var workbook = workbook(equipmentService().importTemplate())) {
            Sheet sheet = workbook.getSheet("设备导入模板");
            List<String> headers = headers(sheet);
            assertThat(headers).contains(
                    "*设备分类", "*所属组织", "物理位置", "主负责人", "生命周期"
            );
            assertThat(value(sheet, headers, "设备分类")).isEqualTo("生产设备");
            assertThat(value(sheet, headers, "生命周期")).isEqualTo("在役");
            assertThat(workbook.getSheet("填写规范")).isNotNull();
            assertThat(workbook.getSheet("设备分类参考")).isNotNull();
        }
    }

    @Test
    void inspectionTemplateUsesChineseExamplesAndNewDefaults() throws Exception {
        try (var workbook = workbook(inspectionImportService().template())) {
            Sheet sheet = workbook.getSheet("点检项目");
            List<String> headers = headers(sheet);
            assertThat(value(sheet, headers, "项目分类")).isEqualTo("操作");
            assertThat(value(sheet, headers, "结果类型")).isEqualTo("数值");
            assertThat(value(sheet, headers, "异常等级")).isEqualTo("中");
            assertThat(value(sheet, headers, "标准分钟")).isEqualTo("2");
            assertThat(sheetValues(workbook.getSheet("填写规范"))).contains("每小时");
        }
    }

    private XSSFWorkbook workbook(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private void assertHeaders(Sheet sheet, String... expected) {
        assertThat(headers(sheet)).contains(expected);
    }

    private List<String> headers(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        return java.util.stream.IntStream.range(0, sheet.getRow(0).getLastCellNum())
                .mapToObj(index -> formatter.formatCellValue(sheet.getRow(0).getCell(index)).trim())
                .toList();
    }

    private String value(Sheet sheet, List<String> displayedHeaders, String canonicalHeader) {
        int index = java.util.stream.IntStream.range(0, displayedHeaders.size())
                .filter(candidate -> displayedHeaders.get(candidate)
                        .replaceFirst("^\\*", "").equals(canonicalHeader))
                .findFirst().orElseThrow();
        return new DataFormatter(Locale.SIMPLIFIED_CHINESE)
                .formatCellValue(sheet.getRow(1).getCell(index)).trim();
    }

    private String sheetValues(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        StringBuilder content = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> content
                .append(formatter.formatCellValue(cell))
                .append('\n')));
        return content.toString();
    }

    private EquipmentService equipmentService() {
        return new EquipmentService(
                mock(EquipmentMapper.class), mock(MasterDataMapper.class),
                mock(DataPermissionService.class), mock(NumberRuleService.class),
                mock(ParameterService.class), mock(ChangeLogService.class), new ObjectMapper()
        );
    }

    private UserImportService userImportService() {
        return new UserImportService(
                mock(JdbcTemplate.class), new ObjectMapper(), mock(SystemMapper.class),
                mock(SystemService.class), mock(DataPermissionService.class),
                mock(ChangeLogService.class)
        );
    }

    private OeeImportService oeeImportService() {
        return new OeeImportService(
                mock(OeeMapper.class), mock(OeeService.class),
                mock(DataPermissionService.class)
        );
    }

    private InspectionImportService inspectionImportService() {
        return new InspectionImportService(
                mock(JdbcTemplate.class), new ObjectMapper(), mock(InspectionMapper.class),
                mock(InspectionCalendarMapper.class), mock(EquipmentMapper.class),
                mock(MasterDataMapper.class), mock(InspectionCatalogService.class),
                mock(DataPermissionService.class), mock(ChangeLogService.class)
        );
    }
}
