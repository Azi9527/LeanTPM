package com.leantpm.oee;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermissionService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OeeImportService {
    private static final List<String> HEADERS = List.of(
            "设备编码", "生产日期", "班次编码", "标准节拍(秒)",
            "计划工作分钟", "计划停机分钟", "计划数量", "实际产量",
            "良品数量", "不良品数量"
    );

    private final OeeMapper mapper;
    private final OeeService oeeService;
    private final DataPermissionService dataPermissionService;

    public OeeImportService(
            OeeMapper mapper,
            OeeService oeeService,
            DataPermissionService dataPermissionService
    ) {
        this.mapper = mapper;
        this.oeeService = oeeService;
        this.dataPermissionService = dataPermissionService;
    }

    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("OEE数据");
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(HEADERS.get(index));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, index < 3 ? 18 * 256 : 16 * 256);
            }
            Row example = sheet.createRow(1);
            example.createCell(0).setCellValue("EQ-DEMO-001");
            example.createCell(1).setCellValue("2026-07-30");
            example.createCell(2).setCellValue("DAY");
            example.createCell(3).setCellValue("60");
            example.createCell(4).setCellValue("660");
            example.createCell(5).setCellValue("30");
            example.createCell(6).setCellValue("600");
            example.createCell(7).setCellValue("570");
            example.createCell(8).setCellValue("565");
            example.createCell(9).setCellValue("5");
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                workbook.write(output);
                return output.toByteArray();
            }
        } catch (IOException exception) {
            throw new BusinessException(
                    "OEE_TEMPLATE_FAILED", "OEE导入模板生成失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public OeeDtos.ImportResult importWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("OEE_IMPORT_FILE_EMPTY", "请选择Excel文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null
                || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(
                    "OEE_IMPORT_FILE_TYPE_INVALID", "仅支持 .xlsx 文件"
            );
        }
        var current = SecurityUtils.currentUser();
        int maxRows = importMaxRows(current.tenantId());
        List<String> errors = new ArrayList<>();
        int totalRows = 0;
        int successRows = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0
                    ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new BusinessException("OEE_IMPORT_SHEET_EMPTY", "Excel工作表为空");
            }
            Map<String, Integer> columns = headerColumns(sheet.getRow(0));
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int lastRow = sheet.getLastRowNum();
            for (int rowIndex = 1; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || blankRow(row, formatter)) {
                    continue;
                }
                totalRows++;
                if (totalRows > maxRows) {
                    throw new BusinessException(
                            "OEE_IMPORT_TOO_MANY_ROWS",
                            "单次导入不能超过 " + maxRows + " 行"
                    );
                }
                try {
                    importRow(current.tenantId(), rowIndex + 1, row, columns, formatter);
                    successRows++;
                } catch (RuntimeException exception) {
                    errors.add(
                            "第" + (rowIndex + 1) + "行：" + rootMessage(exception)
                    );
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException(
                    "OEE_IMPORT_READ_FAILED", "Excel文件无法读取或格式损坏"
            );
        }
        return new OeeDtos.ImportResult(
                totalRows, successRows, totalRows - successRows, List.copyOf(errors)
        );
    }

    private void importRow(
            long tenantId,
            int rowNumber,
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter
    ) {
        String equipmentCode = requiredText(
                row, columns, "设备编码", formatter, rowNumber
        ).toUpperCase(Locale.ROOT);
        String shiftCode = requiredText(
                row, columns, "班次编码", formatter, rowNumber
        ).toUpperCase(Locale.ROOT);
        OeeDtos.EquipmentRef equipment = mapper.findEquipmentByCode(
                tenantId, equipmentCode, dataPermissionService.current()
        );
        if (equipment == null || equipment.status() != 1 || !equipment.oeeEnabled()) {
            throw new BusinessException(
                    "OEE_IMPORT_EQUIPMENT_INVALID", "设备不存在、未启用OEE或无权访问"
            );
        }
        Long shiftId = mapper.findShiftIdByCode(tenantId, shiftCode);
        if (shiftId == null || mapper.findShift(tenantId, shiftId).status() != 1) {
            throw new BusinessException(
                    "OEE_IMPORT_SHIFT_INVALID", "班次不存在或已停用"
            );
        }
        LocalDate productionDate = date(
                row, columns, "生产日期", formatter, rowNumber
        );
        OeeDtos.OeeRecordRow existing = mapper.findRecordByKey(
                tenantId, equipment.id(), productionDate, shiftId
        );
        OeeDtos.SaveOeeRecordRequest request = new OeeDtos.SaveOeeRecordRequest(
                equipment.id(),
                productionDate,
                shiftId,
                decimal(row, columns, "标准节拍(秒)", formatter, rowNumber),
                decimal(row, columns, "计划工作分钟", formatter, rowNumber),
                decimal(row, columns, "计划停机分钟", formatter, rowNumber),
                decimal(row, columns, "计划数量", formatter, rowNumber),
                decimal(row, columns, "实际产量", formatter, rowNumber),
                decimal(row, columns, "良品数量", formatter, rowNumber),
                decimal(row, columns, "不良品数量", formatter, rowNumber),
                "EXCEL",
                existing == null ? null : existing.version()
        );
        if (existing == null) {
            oeeService.createImportedRecord(request);
        } else {
            oeeService.updateImportedRecord(existing.id(), request);
        }
    }

    private Map<String, Integer> headerColumns(Row header) {
        if (header == null) {
            throw new BusinessException("OEE_IMPORT_HEADER_MISSING", "Excel缺少表头");
        }
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : header) {
            columns.put(formatter.formatCellValue(cell).trim(), cell.getColumnIndex());
        }
        List<String> missing = HEADERS.stream()
                .filter(name -> !columns.containsKey(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    "OEE_IMPORT_HEADER_INVALID",
                    "Excel缺少列：" + String.join("、", missing)
            );
        }
        return columns;
    }

    private String requiredText(
            Row row,
            Map<String, Integer> columns,
            String header,
            DataFormatter formatter,
            int rowNumber
    ) {
        Cell cell = row.getCell(
                columns.get(header), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );
        String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
        if (value.isEmpty()) {
            throw new BusinessException(
                    "OEE_IMPORT_REQUIRED",
                    "第" + rowNumber + "行“" + header + "”不能为空"
            );
        }
        return value;
    }

    private BigDecimal decimal(
            Row row,
            Map<String, Integer> columns,
            String header,
            DataFormatter formatter,
            int rowNumber
    ) {
        String value = requiredText(row, columns, header, formatter, rowNumber)
                .replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    "OEE_IMPORT_NUMBER_INVALID", "“" + header + "”必须为有效数字"
            );
        }
    }

    private LocalDate date(
            Row row,
            Map<String, Integer> columns,
            String header,
            DataFormatter formatter,
            int rowNumber
    ) {
        Cell cell = row.getCell(
                columns.get(header), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
        );
        if (cell != null
                && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = requiredText(row, columns, header, formatter, rowNumber);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(
                    "OEE_IMPORT_DATE_INVALID", "生产日期格式必须为 yyyy-MM-dd"
            );
        }
    }

    private boolean blankRow(Row row, DataFormatter formatter) {
        for (int index = 0; index < HEADERS.size(); index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && !formatter.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int importMaxRows(long tenantId) {
        String value = mapper.findParameterValue(tenantId, "oee.import.max-rows");
        try {
            return value == null ? 2000 : Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 2000;
        }
    }

    private String rootMessage(Throwable throwable) {
        if (throwable instanceof BusinessException) {
            return throwable.getMessage();
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
