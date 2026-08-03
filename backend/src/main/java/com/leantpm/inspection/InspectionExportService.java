package com.leantpm.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.attachment.StorageProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class InspectionExportService {
    private static final int EXPORT_TASK_LIMIT = 5_000;

    private final InspectionMapper mapper;
    private final InspectionTaskService taskService;
    private final DataPermissionService dataPermissionService;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;

    public InspectionExportService(
            InspectionMapper mapper,
            InspectionTaskService taskService,
            DataPermissionService dataPermissionService,
            ObjectMapper objectMapper,
            StorageProperties storageProperties
    ) {
        this.mapper = mapper;
        this.taskService = taskService;
        this.dataPermissionService = dataPermissionService;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    @Transactional
    public InspectionDtos.CreateExportJobResult createImageExportJob(
            InspectionDtos.TaskQuery requestedQuery
    ) {
        var current = SecurityUtils.currentUser();
        var scope = dataPermissionService.current();
        var query = taskService.normalizeTaskQuery(requestedQuery);
        long taskCount = mapper.countTasks(current.tenantId(), scope, query);
        if (taskCount > EXPORT_TASK_LIMIT) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_TOO_LARGE",
                    "点检任务超过 " + EXPORT_TASK_LIMIT + " 条，请缩小筛选范围"
            );
        }
        String exportCode = UUID.randomUUID().toString();
        mapper.insertExportJob(
                current.tenantId(), exportCode, json(query), json(scope), current.userId()
        );
        var created = mapper.findExportJobData(current.tenantId(), exportCode);
        if (created == null) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_JOB_CREATE_FAILED", "图片导出任务创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        return new InspectionDtos.CreateExportJobResult(
                created.id(), created.exportCode(), created.jobStatus()
        );
    }

    @Transactional(readOnly = true)
    public InspectionDtos.ExportJobDetail exportJob(long jobId) {
        var current = SecurityUtils.currentUser();
        var job = mapper.findOwnedExportJob(current.tenantId(), jobId, current.userId());
        if (job == null) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_JOB_NOT_FOUND", "导出任务不存在", HttpStatus.NOT_FOUND
            );
        }
        List<InspectionDtos.ExportFileRow> files = "COMPLETED".equals(job.jobStatus())
                ? mapper.findExportFiles(current.tenantId(), jobId) : List.of();
        return new InspectionDtos.ExportJobDetail(job, files);
    }

    @Transactional(readOnly = true)
    public ExportDownload exportFile(long jobId, long fileId) {
        var current = SecurityUtils.currentUser();
        var file = mapper.findOwnedExportFile(
                current.tenantId(), jobId, fileId, current.userId()
        );
        if (file == null) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_FILE_NOT_FOUND", "导出文件不存在", HttpStatus.NOT_FOUND
            );
        }
        Path path = storageRoot.resolve(file.storagePath()).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new BusinessException("INVALID_FILE_PATH", "非法文件路径");
        }
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BusinessException(
                        "INSPECTION_EXPORT_CONTENT_MISSING", "导出文件内容不存在",
                        HttpStatus.NOT_FOUND
                );
            }
            return new ExportDownload(file, resource);
        } catch (IOException exception) {
            throw new BusinessException("INSPECTION_EXPORT_READ_FAILED", "导出文件读取失败");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "INSPECTION_EXPORT_SERIALIZE_FAILED", "导出条件序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public record ExportDownload(InspectionDtos.ExportFileData file, Resource resource) {
    }
}
