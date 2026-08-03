package com.leantpm.inspection;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionImageWorkbookWriterTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMA"
                    + "ASsJTYQAAAAASUVORK5CYII="
    );

    @TempDir
    Path storageRoot;

    @Test
    void embedsOnlyWatermarkedImageAndMarksFailures() throws Exception {
        Files.createDirectories(storageRoot.resolve("evidence"));
        Files.write(storageRoot.resolve("evidence/watermarked.png"), PNG);
        List<InspectionDtos.TaskAttachmentExportRow> attachments = List.of(
                attachment(1L, "watermarked.png", "evidence/watermarked.png", true),
                attachment(2L, "original.png", "evidence/original.png", false),
                attachment(3L, "missing.png", "evidence/missing.png", true)
        );
        var output = new ByteArrayOutputStream();

        new InspectionImageWorkbookWriter().write(
                output, List.of(), List.of(), List.of(), attachments, storageRoot
        );

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getAllPictures()).hasSize(1);
            var sheet = workbook.getSheet("图片明细");
            assertThat(sheet.getRow(1).getCell(9).getStringCellValue())
                    .isEqualTo("已嵌入（水印图）");
            assertThat(sheet.getRow(2).getCell(9).getStringCellValue())
                    .isEqualTo("未嵌入：不是系统水印图");
            assertThat(sheet.getRow(3).getCell(9).getStringCellValue())
                    .isEqualTo("嵌入失败：图片文件不存在");
        }
    }

    private InspectionDtos.TaskAttachmentExportRow attachment(
            long id,
            String name,
            String storagePath,
            boolean watermarked
    ) {
        return new InspectionDtos.TaskAttachmentExportRow(
                "DJ-001", "EQ-001", "测试设备", "温度检查", "YC-001",
                id, name, "image/png", "png", (long) PNG.length,
                "RESULT_PHOTO", storagePath, watermarked, LocalDateTime.now()
        );
    }
}
