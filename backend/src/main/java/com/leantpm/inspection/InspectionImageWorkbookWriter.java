package com.leantpm.inspection;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Writes an auditable export workbook. Only verified watermarked evidence is embedded. */
@Component
public class InspectionImageWorkbookWriter {
    public void write(
            OutputStream output,
            List<InspectionDtos.TaskRow> tasks,
            List<InspectionDtos.TaskResultExportRow> results,
            List<InspectionDtos.TaskAbnormalExportRow> abnormalities,
            List<InspectionDtos.TaskAttachmentExportRow> attachments,
            Path storageRoot
    ) throws IOException {
        try (var workbook = new XSSFWorkbook()) {
            CellStyle header = headerStyle(workbook);
            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.getCreationHelper()
                    .createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            CellStyle dateOnly = workbook.createCellStyle();
            dateOnly.setDataFormat(workbook.getCreationHelper()
                    .createDataFormat().getFormat("yyyy-mm-dd"));
            writeSummary(workbook, tasks, header, dateTime, dateOnly);
            writeResults(workbook, results, header, dateTime, dateOnly);
            writeAbnormalities(workbook, abnormalities, header, dateTime);
            writeImages(workbook, attachments, storageRoot, header, dateTime);
            workbook.write(output);
        }
    }

    private void writeSummary(
            XSSFWorkbook workbook,
            List<InspectionDtos.TaskRow> tasks,
            CellStyle header,
            CellStyle dateTime,
            CellStyle dateOnly
    ) {
        Sheet sheet = workbook.createSheet("任务汇总");
        String[] headers = {"任务编号", "计划日期", "截止时间", "完成时间", "状态",
                "设备编号", "设备名称", "组织", "位置", "班组", "执行人", "点检方案",
                "项目数", "已完成", "异常数"};
        writeHeader(sheet, headers, header);
        int rowIndex = 1;
        for (var task : tasks) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            text(row, column++, task.taskCode());
            date(row, column++, task.plannedDate(), dateOnly);
            date(row, column++, task.dueTime(), dateTime);
            date(row, column++, task.completedTime(), dateTime);
            text(row, column++, task.taskStatus());
            text(row, column++, task.equipmentCode());
            text(row, column++, task.equipmentName());
            text(row, column++, task.organizationName());
            text(row, column++, task.locationName());
            text(row, column++, task.teamCode());
            text(row, column++, task.assigneeName());
            text(row, column++, task.schemeNameSnapshot());
            number(row, column++, task.itemCount());
            number(row, column++, task.completedItemCount());
            number(row, column, task.abnormalItemCount());
        }
        finishSheet(sheet, headers.length);
    }

    private void writeResults(
            XSSFWorkbook workbook,
            List<InspectionDtos.TaskResultExportRow> results,
            CellStyle header,
            CellStyle dateTime,
            CellStyle dateOnly
    ) {
        Sheet sheet = workbook.createSheet("逐项结果");
        String[] headers = {"任务编号", "计划日期", "截止时间", "完成时间", "任务状态",
                "设备编号", "设备名称", "组织", "位置", "班组", "执行人", "点检方案",
                "项目编码", "项目名称", "部位", "点检标准", "单位", "结果状态", "结果编码",
                "数值结果", "文本结果", "选择结果", "多选结果", "是否异常", "异常说明",
                "实际执行人", "执行时间"};
        writeHeader(sheet, headers, header);
        int rowIndex = 1;
        for (var result : results) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            text(row, column++, result.taskCode());
            date(row, column++, result.plannedDate(), dateOnly);
            date(row, column++, result.dueTime(), dateTime);
            date(row, column++, result.completedTime(), dateTime);
            text(row, column++, result.taskStatus());
            text(row, column++, result.equipmentCode());
            text(row, column++, result.equipmentName());
            text(row, column++, result.organizationName());
            text(row, column++, result.locationName());
            text(row, column++, result.teamCode());
            text(row, column++, result.assigneeName());
            text(row, column++, result.schemeName());
            text(row, column++, result.itemCode());
            text(row, column++, result.itemName());
            text(row, column++, result.inspectionPart());
            text(row, column++, result.inspectionStandard());
            text(row, column++, result.unit());
            text(row, column++, result.resultStatus());
            text(row, column++, result.resultCode());
            decimal(row, column++, result.numericValue());
            text(row, column++, result.textValue());
            text(row, column++, result.selectedValue());
            text(row, column++, result.selectedValuesJson());
            text(row, column++, Boolean.TRUE.equals(result.abnormalFlag()) ? "是" : "否");
            text(row, column++, result.abnormalDescription());
            text(row, column++, result.executedByName());
            date(row, column, result.executedTime(), dateTime);
        }
        finishSheet(sheet, headers.length);
    }

    private void writeAbnormalities(
            XSSFWorkbook workbook,
            List<InspectionDtos.TaskAbnormalExportRow> abnormalities,
            CellStyle header,
            CellStyle dateTime
    ) {
        Sheet sheet = workbook.createSheet("异常记录");
        String[] headers = {"异常编号", "任务编号", "设备编号", "设备名称", "点检项目",
                "异常标题", "异常说明", "严重度", "状态", "责任人", "处理期限", "临时措施",
                "最终结果", "关闭人", "关闭时间", "验证人", "验证时间", "验证意见", "创建时间"};
        writeHeader(sheet, headers, header);
        int rowIndex = 1;
        for (var abnormal : abnormalities) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            text(row, column++, abnormal.abnormalCode());
            text(row, column++, abnormal.taskCode());
            text(row, column++, abnormal.equipmentCode());
            text(row, column++, abnormal.equipmentName());
            text(row, column++, abnormal.itemName());
            text(row, column++, abnormal.abnormalTitle());
            text(row, column++, abnormal.abnormalDescription());
            text(row, column++, abnormal.severity());
            text(row, column++, abnormal.abnormalStatus());
            text(row, column++, abnormal.responsibleUserName());
            date(row, column++, abnormal.dueTime(), dateTime);
            text(row, column++, abnormal.temporaryAction());
            text(row, column++, abnormal.finalResult());
            text(row, column++, abnormal.closedByName());
            date(row, column++, abnormal.closedTime(), dateTime);
            text(row, column++, abnormal.verifiedByName());
            date(row, column++, abnormal.verifiedTime(), dateTime);
            text(row, column++, abnormal.verificationComment());
            date(row, column, abnormal.createdTime(), dateTime);
        }
        finishSheet(sheet, headers.length);
    }

    private void writeImages(
            XSSFWorkbook workbook,
            List<InspectionDtos.TaskAttachmentExportRow> attachments,
            Path storageRoot,
            CellStyle header,
            CellStyle dateTime
    ) {
        Sheet sheet = workbook.createSheet("图片明细");
        String[] headers = {"任务编号", "设备编号", "设备名称", "点检项目", "异常编号",
                "附件ID", "文件名", "附件类型", "上传时间", "嵌入状态", "水印图片"};
        writeHeader(sheet, headers, header);
        var drawing = sheet.createDrawingPatriarch();
        int rowIndex = 1;
        for (var attachment : attachments) {
            Row row = sheet.createRow(rowIndex);
            row.setHeightInPoints(120);
            int column = 0;
            text(row, column++, attachment.taskCode());
            text(row, column++, attachment.equipmentCode());
            text(row, column++, attachment.equipmentName());
            text(row, column++, attachment.itemName());
            text(row, column++, attachment.abnormalCode());
            number(row, column++, attachment.attachmentId());
            text(row, column++, attachment.originalName());
            text(row, column++, attachment.attachmentType());
            date(row, column++, attachment.createdTime(), dateTime);
            String status = embedImage(workbook, drawing, attachment, storageRoot, rowIndex);
            text(row, column, status);
            rowIndex++;
        }
        finishSheet(sheet, headers.length);
        sheet.setColumnWidth(10, 32 * 256);
    }

    private String embedImage(
            XSSFWorkbook workbook,
            org.apache.poi.ss.usermodel.Drawing<?> drawing,
            InspectionDtos.TaskAttachmentExportRow attachment,
            Path storageRoot,
            int rowIndex
    ) {
        if (!Boolean.TRUE.equals(attachment.watermarkedFlag())) {
            return "未嵌入：不是系统水印图";
        }
        String contentType = attachment.contentType() == null
                ? "" : attachment.contentType().toLowerCase();
        int pictureType;
        if (contentType.equals("image/jpeg") || contentType.equals("image/jpg")) {
            pictureType = Workbook.PICTURE_TYPE_JPEG;
        } else if (contentType.equals("image/png")) {
            pictureType = Workbook.PICTURE_TYPE_PNG;
        } else {
            return "未嵌入：Excel 不支持该图片格式";
        }
        try {
            Path imagePath = storageRoot.resolve(attachment.storagePath()).normalize();
            if (!imagePath.startsWith(storageRoot) || !Files.isRegularFile(imagePath)) {
                return "嵌入失败：图片文件不存在";
            }
            byte[] imageBytes = Files.readAllBytes(imagePath);
            int pictureIndex = workbook.addPicture(imageBytes, pictureType);
            XSSFClientAnchor anchor = new XSSFClientAnchor(
                    0, 0, 0, 0, 10, rowIndex, 11, rowIndex + 1
            );
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            drawing.createPicture(anchor, pictureIndex);
            return "已嵌入（水印图）";
        } catch (Exception exception) {
            return "嵌入失败：" + safeError(exception.getMessage());
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers[index]);
            cell.setCellStyle(style);
        }
    }

    private void finishSheet(Sheet sheet, int columns) {
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, Math.max(0, sheet.getLastRowNum()), 0, columns - 1
        ));
        for (int index = 0; index < columns; index++) {
            if (sheet.getLastRowNum() <= 5_000) {
                sheet.autoSizeColumn(index);
                sheet.setColumnWidth(index, Math.min(sheet.getColumnWidth(index) + 512, 12_000));
            } else {
                sheet.setColumnWidth(index, 5_000);
            }
        }
    }

    private void text(Row row, int column, String value) {
        row.createCell(column).setCellValue(safeExcel(value));
    }

    private void number(Row row, int column, Number value) {
        if (value != null) row.createCell(column).setCellValue(value.doubleValue());
    }

    private void decimal(Row row, int column, BigDecimal value) {
        if (value != null) row.createCell(column).setCellValue(value.doubleValue());
    }

    private void date(Row row, int column, Object value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(column);
        if (value instanceof LocalDate localDate) cell.setCellValue(localDate);
        else if (value instanceof LocalDateTime localDateTime) cell.setCellValue(localDateTime);
        if (style != null) cell.setCellStyle(style);
    }

    private String safeExcel(String value) {
        String cleaned = value == null ? "" : value;
        return !cleaned.isEmpty() && "=+-@".indexOf(cleaned.charAt(0)) >= 0
                ? "'" + cleaned : cleaned;
    }

    private String safeError(String value) {
        if (value == null || value.isBlank()) return "读取异常";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
