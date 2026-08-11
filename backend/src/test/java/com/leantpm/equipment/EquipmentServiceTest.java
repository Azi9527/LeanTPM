package com.leantpm.equipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.foundation.service.ParameterService;
import com.leantpm.masterdata.MasterDataMapper;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipmentServiceTest {
    private final EquipmentMapper mapper = mock(EquipmentMapper.class);
    private final MasterDataMapper masterDataMapper = mock(MasterDataMapper.class);
    private final DataPermissionService dataPermissionService = mock(DataPermissionService.class);
    private final NumberRuleService numberRuleService = mock(NumberRuleService.class);
    private final ParameterService parameterService = mock(ParameterService.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final EquipmentService service = new EquipmentService(
            mapper,
            masterDataMapper,
            dataPermissionService,
            numberRuleService,
            parameterService,
            changeLogService,
            new ObjectMapper()
    );

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(
                7L, 1L, "tester", "测试用户", false,
                Set.of("ADMIN"), Set.of("equipment:status:update"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
        when(dataPermissionService.current()).thenReturn(DataPermission.all(7L));
        when(mapper.findEquipment(eq(1L), eq(100L), any())).thenReturn(equipment());
        when(mapper.countStatusCode(eq(1L), any())).thenReturn(1);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsIllegalStatusTransitionBeforeWritingHistory() {
        when(mapper.findCurrentStatus(1L, 100L)).thenReturn(
                new EquipmentMapper.CurrentStatus(
                        1L, "SCRAPPED", LocalDateTime.now().minusHours(1),
                        null, "MANUAL", 3
                )
        );

        assertThatThrownBy(() -> service.changeStatus(
                100L,
                new EquipmentDtos.ChangeStatusRequest("RUNNING", "非法跳转", "MANUAL", 3)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("STATUS_TRANSITION_INVALID");

        verify(mapper, never()).updateCurrentStatus(
                anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong()
        );
    }

    @Test
    void recordsLegalStatusTransitionWithOptimisticVersion() {
        EquipmentMapper.CurrentStatus current = new EquipmentMapper.CurrentStatus(
                1L, "RUNNING", LocalDateTime.now().minusHours(1),
                null, "MANUAL", 3
        );
        EquipmentMapper.CurrentStatus updated = new EquipmentMapper.CurrentStatus(
                1L, "STOPPED", LocalDateTime.now(), "异常停机", "MANUAL", 4
        );
        when(mapper.findCurrentStatus(1L, 100L)).thenReturn(current, updated);
        when(mapper.updateCurrentStatus(
                eq(1L), eq(100L), eq("STOPPED"), any(), eq("异常停机"),
                eq("MANUAL"), eq(3), eq(7L)
        )).thenReturn(1);

        service.changeStatus(
                100L,
                new EquipmentDtos.ChangeStatusRequest(
                        "STOPPED", "异常停机", "MANUAL", 3
                )
        );

        verify(mapper).closeOpenStatusHistory(eq(1L), eq(100L), any());
        verify(mapper).insertStatusHistory(
                eq(1L), eq(100L), eq("RUNNING"), eq("STOPPED"), any(),
                eq("异常停机"), eq("MANUAL"), eq(7L)
        );
        verify(changeLogService).record(
                eq("EQUIPMENT_STATUS"), eq(100L), eq("UPDATE"), eq(current), eq(updated)
        );
    }

    @Test
    void rendersCustomerApprovedPremiumBlueEquipmentLabel() {
        BufferedImage qrCode = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = qrCode.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, qrCode.getWidth(), qrCode.getHeight());
        graphics.dispose();

        BufferedImage label = EquipmentService.withPremiumEquipmentLabel(
                qrCode,
                "大宝山设备管理系统",
                "循环泵站一号",
                "VIZ-PUMP-01"
        );

        assertThat(label.getWidth()).isEqualTo(240);
        assertThat(label.getHeight()).isEqualTo(320);
        assertThat(label.getRGB(8, 8) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        assertThat(label.getRGB(8, 150) & 0xFFFFFF).isNotEqualTo(0xFFFFFF);
        assertThat(label.getRGB(120, 302) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void keepsStyledQrDecodableWhenCenterLogoIsApplied() throws Exception {
        String content = "https://equipment.example/m/e/scan-safe-token";
        BufferedImage logo = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var graphics = logo.createGraphics();
        graphics.setColor(new Color(0, 160, 136));
        graphics.fillRoundRect(0, 0, 64, 64, 12, 12);
        graphics.dispose();

        BufferedImage qrCode = EquipmentService.renderStyledQr(content, 480, logo);
        int[] pixels = qrCode.getRGB(
                0, 0, qrCode.getWidth(), qrCode.getHeight(), null, 0, qrCode.getWidth()
        );
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(qrCode.getWidth(), qrCode.getHeight(), pixels)
        ));

        assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(content);
        Color center = new Color(qrCode.getRGB(240, 240), true);
        assertThat(center.getGreen()).isGreaterThan(center.getRed());
    }

    @Test
    void normalizesNewChineseImportTermsWhileKeepingLegacyHeadersAndCodes() {
        assertThat(EquipmentService.canonicalImportHeader("*分类编码")).isEqualTo("设备分类");
        assertThat(EquipmentService.canonicalImportHeader("负责人账号")).isEqualTo("主负责人");
        assertThat(EquipmentService.normalizeCategoryCode("生产设备")).isEqualTo("PRODUCTION");
        assertThat(EquipmentService.normalizeCategoryCode("PUMP")).isEqualTo("PUMP");
        assertThat(EquipmentService.normalizeLifecycleStage("在役")).isEqualTo("IN_SERVICE");
        assertThat(EquipmentService.normalizeLifecycleStage("IN_SERVICE")).isEqualTo("IN_SERVICE");
    }

    @Test
    void reportsTheExactImportFieldBeforeAnOversizedValueCanReachTheDatabase() {
        EquipmentDtos.SaveEquipmentRequest request = new EquipmentDtos.SaveEquipmentRequest(
                "EQ-1", "测试设备", 1L, "M".repeat(101), null, null, null, null,
                null, null, 1L, null, null, null, "IN_SERVICE",
                false, false, true, true, null, List.of(), List.of(), null
        );

        assertThatThrownBy(() -> EquipmentService.validateImportRequest(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(EquipmentService.importErrorField(
                        (BusinessException) error)).isEqualTo("型号"));
    }

    @Test
    void validatesEveryFieldBeforeWritingAnyEquipment() throws Exception {
        MockMultipartFile workbook = importWorkbook(
                List.of("设备编码", "设备名称", "设备分类", "所属组织", "型号",
                        "生产日期", "投产日期", "关键设备"),
                List.of("EQ-INVALID", "", "不存在分类", "不存在组织", "M".repeat(101),
                        "2026-02-30", "不是日期", "不确定")
        );

        EquipmentDtos.ImportResult result = service.importWorkbook(workbook);

        assertThat(result.importedRows()).isZero();
        assertThat(result.errors())
                .extracting(EquipmentDtos.ImportError::field)
                .contains("设备名称", "设备分类", "所属组织", "型号", "生产日期", "投产日期", "关键设备");
        verify(mapper, never()).insertEquipment(anyLong(), any(), any(), anyLong());
    }

    @Test
    void rejectsTheWholeWorkbookWhenAValidRowPrecedesAnInvalidRow() throws Exception {
        when(mapper.findCategoryByCode(1L, "PRODUCTION"))
                .thenReturn(new EquipmentMapper.LookupRow(10L, "生产设备", 1));
        when(mapper.findOrganizationByCode(1L, "WORKSHOP-A"))
                .thenReturn(new EquipmentMapper.LookupRow(20L, "一车间", 1));
        when(mapper.findEquipmentIdByCode(1L, "EQ-VALID")).thenReturn(100L);
        MockMultipartFile workbook = importWorkbookRows(
                List.of("设备编码", "设备名称", "设备分类", "所属组织", "投产日期"),
                List.of(
                        List.of("EQ-VALID", "合法设备", "生产设备", "WORKSHOP-A", "2026-08-11"),
                        List.of("EQ-INVALID", "错误设备", "不存在分类", "WORKSHOP-A", "2026-08-11")
                )
        );

        EquipmentDtos.ImportResult result = service.importWorkbook(workbook);

        assertThat(result.importedRows()).isZero();
        assertThat(result.errors())
                .extracting(EquipmentDtos.ImportError::rowNumber, EquipmentDtos.ImportError::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "设备分类"));
        verify(mapper, never()).insertEquipment(anyLong(), any(), any(), anyLong());
        org.mockito.Mockito.verifyNoInteractions(changeLogService);
    }

    @Test
    void reportsEveryWorkbookDuplicateCodeAndWritesNothing() throws Exception {
        when(mapper.findCategoryByCode(1L, "PRODUCTION"))
                .thenReturn(new EquipmentMapper.LookupRow(10L, "生产设备", 1));
        when(mapper.findOrganizationByCode(1L, "WORKSHOP-A"))
                .thenReturn(new EquipmentMapper.LookupRow(20L, "一车间", 1));
        MockMultipartFile workbook = importWorkbookRows(
                List.of("设备编码", "设备名称", "设备分类", "所属组织"),
                List.of(
                        List.of("EQ-DUPLICATE", "设备一", "生产设备", "WORKSHOP-A"),
                        List.of("EQ-DUPLICATE", "设备二", "生产设备", "WORKSHOP-A")
                )
        );

        EquipmentDtos.ImportResult result = service.importWorkbook(workbook);

        assertThat(result.importedRows()).isZero();
        assertThat(result.errors())
                .extracting(EquipmentDtos.ImportError::rowNumber, EquipmentDtos.ImportError::field)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, "设备编码"),
                        org.assertj.core.groups.Tuple.tuple(3, "设备编码")
                );
        verify(mapper, never()).insertEquipment(anyLong(), any(), any(), anyLong());
    }

    @Test
    void reportsAnExistingDatabaseCodeDuringPrevalidationAndWritesNothing() throws Exception {
        when(mapper.findCategoryByCode(1L, "PRODUCTION"))
                .thenReturn(new EquipmentMapper.LookupRow(10L, "生产设备", 1));
        when(mapper.findOrganizationByCode(1L, "WORKSHOP-A"))
                .thenReturn(new EquipmentMapper.LookupRow(20L, "一车间", 1));
        when(mapper.countEquipmentCode(1L, "EQ-EXISTS", null)).thenReturn(1);
        MockMultipartFile workbook = importWorkbook(
                List.of("设备编码", "设备名称", "设备分类", "所属组织"),
                List.of("EQ-EXISTS", "已存在设备", "生产设备", "WORKSHOP-A")
        );

        EquipmentDtos.ImportResult result = service.importWorkbook(workbook);

        assertThat(result.importedRows()).isZero();
        assertThat(result.errors())
                .extracting(EquipmentDtos.ImportError::rowNumber, EquipmentDtos.ImportError::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "设备编码"));
        verify(mapper, never()).insertEquipment(anyLong(), any(), any(), anyLong());
        org.mockito.Mockito.verifyNoInteractions(changeLogService);
    }

    @Test
    void acceptsSlashSeparatedAndNativeExcelDatesDuringWholeWorkbookValidation() throws Exception {
        when(mapper.findCategoryByCode(1L, "PRODUCTION"))
                .thenReturn(new EquipmentMapper.LookupRow(10L, "生产设备", 1));
        when(mapper.findOrganizationByCode(1L, "WORKSHOP-A"))
                .thenReturn(new EquipmentMapper.LookupRow(20L, "一车间", 1));
        MockMultipartFile slashDate = importWorkbook(
                List.of("设备编码", "设备名称", "设备分类", "所属组织", "投产日期", "关键设备"),
                List.of("EQ-SLASH", "斜杠日期设备", "生产设备", "WORKSHOP-A", "2026/08/11", "不确定")
        );
        MockMultipartFile nativeDate = nativeDateWorkbook();

        EquipmentDtos.ImportResult slashResult = service.importWorkbook(slashDate);
        EquipmentDtos.ImportResult nativeResult = service.importWorkbook(nativeDate);

        assertThat(slashResult.errors())
                .extracting(EquipmentDtos.ImportError::field)
                .containsExactly("关键设备");
        assertThat(nativeResult.errors())
                .extracting(EquipmentDtos.ImportError::field)
                .containsExactly("关键设备");
        verify(mapper, never()).insertEquipment(anyLong(), any(), any(), anyLong());
    }

    @Test
    void parsesEveryDocumentedTextDateToTheExactSameDay() {
        LocalDate expected = LocalDate.of(2026, 8, 11);

        assertThat(EquipmentService.parseImportDate("2026-08-11", "投产日期"))
                .isEqualTo(expected);
        assertThat(EquipmentService.parseImportDate("2026/8/11", "投产日期"))
                .isEqualTo(expected);
        assertThat(EquipmentService.parseImportDate("2026.08.11", "投产日期"))
                .isEqualTo(expected);
        assertThat(EquipmentService.parseImportDate("2026年8月11日", "投产日期"))
                .isEqualTo(expected);
        assertThatThrownBy(() -> EquipmentService.parseImportDate("2026/02/30", "投产日期"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("IMPORT_DATE_INVALID");
    }

    @Test
    void rejectsAnUnformattedNumericDateCellWithItsExactField() throws Exception {
        when(mapper.findCategoryByCode(1L, "PRODUCTION"))
                .thenReturn(new EquipmentMapper.LookupRow(10L, "生产设备", 1));
        when(mapper.findOrganizationByCode(1L, "WORKSHOP-A"))
                .thenReturn(new EquipmentMapper.LookupRow(20L, "一车间", 1));

        EquipmentDtos.ImportResult result = service.importWorkbook(numericDateWorkbook());

        assertThat(result.importedRows()).isZero();
        assertThat(result.errors())
                .extracting(EquipmentDtos.ImportError::rowNumber, EquipmentDtos.ImportError::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "投产日期"));
        verify(mapper, never()).insertEquipment(anyLong(), any(), any(), anyLong());
    }

    private MockMultipartFile importWorkbook(List<String> headers, List<String> values)
            throws IOException {
        return importWorkbookRows(headers, List.of(values));
    }

    private MockMultipartFile importWorkbookRows(List<String> headers, List<List<String>> rows)
            throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("设备台账");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    row.createCell(columnIndex).setCellValue(values.get(columnIndex));
                }
            }
            workbook.write(output);
            return new MockMultipartFile(
                    "file", "设备台账.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray()
            );
        }
    }

    private MockMultipartFile nativeDateWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("设备台账");
            var header = sheet.createRow(0);
            List<String> headers = List.of(
                    "设备编码", "设备名称", "设备分类", "所属组织", "投产日期", "关键设备"
            );
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("EQ-NATIVE-DATE");
            row.createCell(1).setCellValue("原生日期设备");
            row.createCell(2).setCellValue("生产设备");
            row.createCell(3).setCellValue("WORKSHOP-A");
            row.createCell(4).setCellValue(LocalDate.of(2026, 8, 11));
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy/m/d"));
            row.getCell(4).setCellStyle(dateStyle);
            row.createCell(5).setCellValue("不确定");
            workbook.write(output);
            return new MockMultipartFile(
                    "file", "设备台账-原生日期.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray()
            );
        }
    }

    private MockMultipartFile numericDateWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("设备台账");
            var header = sheet.createRow(0);
            List<String> headers = List.of(
                    "设备编码", "设备名称", "设备分类", "所属组织", "投产日期"
            );
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("EQ-NUMERIC-DATE");
            row.createCell(1).setCellValue("普通数字日期设备");
            row.createCell(2).setCellValue("生产设备");
            row.createCell(3).setCellValue("WORKSHOP-A");
            row.createCell(4).setCellValue(45515);
            workbook.write(output);
            return new MockMultipartFile(
                    "file", "设备台账-普通数字日期.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray()
            );
        }
    }

    private EquipmentDtos.EquipmentRow equipment() {
        LocalDateTime now = LocalDateTime.now();
        return new EquipmentDtos.EquipmentRow(
                100L,
                "EQ-100",
                "测试设备",
                10L,
                "PUMP",
                "泵",
                "M1",
                null,
                null,
                null,
                null,
                null,
                null,
                20L,
                "WORKSHOP-A",
                "一车间",
                30L,
                "SITE-A",
                "A工位",
                7L,
                "tester",
                "测试用户",
                null,
                "IN_SERVICE",
                false,
                false,
                true,
                1,
                null,
                "RUNNING",
                now.minusHours(1),
                3600L,
                3,
                null,
                null,
                now.minusDays(1),
                now,
                2
        );
    }
}
