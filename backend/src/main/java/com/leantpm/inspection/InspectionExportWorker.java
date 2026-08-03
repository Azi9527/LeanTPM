package com.leantpm.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.system.attachment.StorageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class InspectionExportWorker {
    static final int MAX_IMAGES_PER_WORKBOOK = 1_000;
    static final long MAX_IMAGE_BYTES_PER_WORKBOOK = 200L * 1024L * 1024L;
    private static final int EXPORT_TASK_LIMIT = 5_000;
    private static final int EXPORT_RESULT_LIMIT = 100_000;
    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final InspectionMapper mapper;
    private final ObjectMapper objectMapper;
    private final InspectionImageWorkbookWriter writer;
    private final Path storageRoot;

    public InspectionExportWorker(
            InspectionMapper mapper,
            ObjectMapper objectMapper,
            InspectionImageWorkbookWriter writer,
            StorageProperties storageProperties
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.writer = writer;
        this.storageRoot = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString = "${leantpm.inspection.export-poll-ms:2000}")
    public void processPendingJobs() {
        for (var job : mapper.findPendingExportJobs(2)) {
            if (mapper.claimExportJob(job.id()) == 1) {
                processClaimedJob(job);
            }
        }
    }

    private void processClaimedJob(InspectionDtos.ExportJobData job) {
        List<Path> createdFiles = new ArrayList<>();
        try {
            var query = objectMapper.readValue(job.queryJson(), InspectionDtos.TaskQuery.class);
            var scope = objectMapper.readValue(job.dataScopeJson(), DataPermission.class);
            var tasks = mapper.findTasks(
                    job.tenantId(), scope, query, 0, EXPORT_TASK_LIMIT + 1
            );
            var results = mapper.findTaskResultExportRows(
                    job.tenantId(), scope, query, EXPORT_RESULT_LIMIT + 1
            );
            var abnormalities = mapper.findTaskAbnormalExportRows(
                    job.tenantId(), scope, query, EXPORT_RESULT_LIMIT + 1
            );
            var attachments = mapper.findTaskAttachmentExportRows(
                    job.tenantId(), scope, query, EXPORT_RESULT_LIMIT + 1
            );
            validateLimits(tasks.size(), results.size(), abnormalities.size(), attachments.size());

            List<List<InspectionDtos.TaskAttachmentExportRow>> parts = partition(attachments);
            Path directory = storageRoot.resolve(Path.of(
                    "exports", String.valueOf(LocalDate.now().getYear()),
                    "%02d".formatted(LocalDate.now().getMonthValue())
            )).normalize();
            if (!directory.startsWith(storageRoot)) {
                throw new IllegalStateException("导出目录越界");
            }
            Files.createDirectories(directory);

            int totalParts = parts.size();
            int partNumber = 0;
            int embeddedImageCount = 0;
            long estimatedImageBytes = attachments.stream()
                    .filter(this::embeddable)
                    .mapToLong(row -> row.fileSize() == null ? 0L : row.fileSize())
                    .sum();
            for (var part : parts) {
                partNumber++;
                String fileName = totalParts == 1
                        ? "LeanTPM-点检结果-含水印图片.xlsx"
                        : "LeanTPM-点检结果-含水印图片-%03d-of-%03d.xlsx"
                                .formatted(partNumber, totalParts);
                String diskName = job.exportCode() + "-part-%03d.xlsx".formatted(partNumber);
                Path target = directory.resolve(diskName).normalize();
                Path temporary = directory.resolve(diskName + ".tmp").normalize();
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    writer.write(output, tasks, results, abnormalities, part, storageRoot);
                }
                moveCompleted(temporary, target);
                createdFiles.add(target);
                int partImageCount = (int) part.stream().filter(this::embeddable).count();
                embeddedImageCount += partImageCount;
                mapper.insertExportFile(
                        job.tenantId(), job.id(), partNumber, fileName,
                        storageRoot.relativize(target).toString().replace('\\', '/'),
                        CONTENT_TYPE, Files.size(target), sha256(target), partImageCount
                );
            }
            mapper.completeExportJob(
                    job.id(), tasks.size(), results.size(), embeddedImageCount,
                    estimatedImageBytes, totalParts
            );
        } catch (Exception exception) {
            for (Path file : createdFiles) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // The failed job remains explicit; orphan cleanup can retry later.
                }
            }
            mapper.failExportJob(job.id(), errorMessage(exception));
        }
    }

    private List<List<InspectionDtos.TaskAttachmentExportRow>> partition(
            List<InspectionDtos.TaskAttachmentExportRow> attachments
    ) {
        List<List<InspectionDtos.TaskAttachmentExportRow>> parts = new ArrayList<>();
        List<InspectionDtos.TaskAttachmentExportRow> current = new ArrayList<>();
        int imageCount = 0;
        long imageBytes = 0;
        for (var attachment : attachments) {
            boolean image = embeddable(attachment);
            long bytes = image && attachment.fileSize() != null ? attachment.fileSize() : 0L;
            if (!current.isEmpty() && image
                    && (imageCount >= MAX_IMAGES_PER_WORKBOOK
                    || imageBytes + bytes > MAX_IMAGE_BYTES_PER_WORKBOOK)) {
                parts.add(List.copyOf(current));
                current.clear();
                imageCount = 0;
                imageBytes = 0;
            }
            current.add(attachment);
            if (image) {
                imageCount++;
                imageBytes += bytes;
            }
        }
        if (!current.isEmpty() || parts.isEmpty()) parts.add(List.copyOf(current));
        return parts;
    }

    private boolean embeddable(InspectionDtos.TaskAttachmentExportRow row) {
        String type = row.contentType() == null ? "" : row.contentType().toLowerCase();
        return Boolean.TRUE.equals(row.watermarkedFlag())
                && (type.equals("image/jpeg") || type.equals("image/jpg")
                || type.equals("image/png"));
    }

    private void validateLimits(int tasks, int results, int abnormalities, int attachments) {
        if (tasks > EXPORT_TASK_LIMIT) throw new IllegalStateException("点检任务超过导出上限");
        if (results > EXPORT_RESULT_LIMIT) throw new IllegalStateException("点检结果超过导出上限");
        if (abnormalities > EXPORT_RESULT_LIMIT || attachments > EXPORT_RESULT_LIMIT) {
            throw new IllegalStateException("点检异常或附件超过导出上限");
        }
    }

    private void moveCompleted(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 900 ? message.substring(0, 900) : message;
    }
}
