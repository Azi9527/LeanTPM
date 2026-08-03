package com.leantpm.mobile;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.notification.NotificationService;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class MobileService {
    private final MobileMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;

    public MobileService(
            MobileMapper mapper,
            DataPermissionService dataPermissionService,
            NotificationService notificationService,
            JdbcTemplate jdbc
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
        this.notificationService = notificationService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MobileDtos.Bootstrap bootstrap() {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        return new MobileDtos.Bootstrap(
                LocalDateTime.now(),
                parameter(
                        current.tenantId(), "mobile.draft-retention-days",
                        7, 1, 30
                ),
                parameter(
                        current.tenantId(), "mobile.max-upload-mb",
                        10, 1, 100
                ),
                new MobileDtos.PhotoPolicy(
                        parameter(current.tenantId(), "mobile.photo-clock-skew-warning-seconds", 300, 0, 86400)
                ),
                new MobileDtos.AndroidVersionPolicy(
                        parameter(current.tenantId(), "mobile.android-min-version-code", 1, 1, Integer.MAX_VALUE),
                        stringParameter(current.tenantId(), "mobile.android-latest-version-name", "1.0.1"),
                        stringParameter(current.tenantId(), "mobile.android-download-url", ""),
                        stringParameter(current.tenantId(), "mobile.android-release-notes", "")
                ),
                mapper.equipmentStatusCount(current.tenantId()),
                safeCount(mapper.inspectionCount(current.tenantId(), current.userId())),
                mapper.inspectionAbnormalCount(current.tenantId(), current.userId()),
                mapper.personalInspectionReport(current.tenantId(), current.userId()),
                safeCount(mapper.maintenanceCount(current.tenantId(), current.userId())),
                notificationService.messages(false, 1, 30).records().stream()
                        .map(message -> new MobileDtos.MessageItem(
                                message.id(), message.messageType(), message.severity(),
                                message.title(), message.content(), message.businessType(),
                                message.businessId(), message.acknowledgeRequired(),
                                message.readTime(), message.acknowledgedTime(),
                                message.occurredTime(), message.routePath()
                        ))
                        .toList()
        );
    }

    @Transactional
    public MobileDtos.PhotoEvidence registerPhotoEvidence(
            MobileDtos.RegisterPhotoEvidenceRequest request
    ) {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        if (request.taskId() <= 0 || request.taskItemId() <= 0
                || request.originalAttachmentId() <= 0 || request.watermarkedAttachmentId() <= 0) {
            throw new BusinessException("MOBILE_EVIDENCE_REFERENCE_INVALID", "照片证据关联标识不正确");
        }
        if (request.originalAttachmentId() == request.watermarkedAttachmentId()) {
            throw new BusinessException("MOBILE_EVIDENCE_FILES_REQUIRED", "原图和水印图必须分别上传");
        }
        assertTaskAccess(request.workflowType(), request.taskId(), request.taskItemId());
        MobileDtos.PhotoEvidence existing = findEvidenceByWatermarkedAttachment(
                current.tenantId(), request.watermarkedAttachmentId()
        );
        if (existing != null) return existing;
        AttachmentHash original = attachment(current.tenantId(), current.userId(), request.originalAttachmentId());
        AttachmentHash watermarked = attachment(current.tenantId(), current.userId(), request.watermarkedAttachmentId());
        int threshold = parameter(
                current.tenantId(), "mobile.photo-clock-skew-warning-seconds", 300, 0, 86400
        );
        jdbc.update("""
                INSERT INTO mobile_photo_evidence
                    (tenant_id, workflow_type, task_id, task_item_id,
                     original_attachment_id, watermarked_attachment_id,
                     captured_device_time, server_reference_time,
                     device_clock_offset_seconds, clock_skew_warning,
                     latitude, longitude, location_accuracy_meters,
                     location_provider, address_text, fault_location_text, watermark_text,
                     original_sha256, watermarked_sha256, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, current.tenantId(), request.workflowType(), request.taskId(), request.taskItemId(),
                request.originalAttachmentId(), request.watermarkedAttachmentId(),
                request.capturedDeviceTime(), request.serverReferenceTime(),
                request.deviceClockOffsetSeconds(),
                Math.abs((long) request.deviceClockOffsetSeconds()) > threshold,
                null, null, null, null, null, request.faultLocationText().trim(),
                request.watermarkText().trim(),
                original.sha256(), watermarked.sha256(), current.userId(), current.userId());
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return evidence(current.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public MobileDtos.PhotoEvidence photoEvidence(long id) {
        var current = SecurityUtils.currentUser();
        MobileDtos.PhotoEvidence evidence = evidence(current.tenantId(), id);
        assertTaskAccess(evidence.workflowType(), evidence.taskId(), evidence.taskItemId());
        return evidence;
    }

    @Transactional(readOnly = true)
    public MobileDtos.EquipmentContext equipment(String token) {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        MobileDtos.EquipmentBase equipment = mapper.equipmentByToken(
                current.tenantId(),
                token.toLowerCase(Locale.ROOT),
                dataPermissionService.current()
        );
        if (equipment == null) {
            throw new BusinessException(
                    "MOBILE_EQUIPMENT_NOT_FOUND",
                    "设备二维码无效、已停用或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return new MobileDtos.EquipmentContext(
                equipment,
                List.copyOf(mapper.activeTasks(
                        current.tenantId(), equipment.equipmentId(), current.userId()
                )),
                List.copyOf(mapper.applicableInspectionSchemes(
                        current.tenantId(), equipment.equipmentId()
                )),
                List.copyOf(mapper.assignees(current.tenantId())),
                List.copyOf(mapper.teams(current.tenantId()))
        );
    }

    private void assertMobileEnabled(long tenantId, long userId) {
        if (!Boolean.TRUE.equals(mapper.mobileEnabled(tenantId, userId))) {
            throw new BusinessException(
                    "MOBILE_ACCESS_DISABLED",
                    "当前账号未启用移动端使用权限",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private MobileDtos.WorkCount safeCount(MobileDtos.WorkCount count) {
        return count == null ? new MobileDtos.WorkCount(0, 0, 0, 0) : count;
    }

    private int parameter(
            long tenantId,
            String key,
            int fallback,
            int minimum,
            int maximum
    ) {
        Integer value = mapper.integerParameter(tenantId, key);
        if (value == null) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    private boolean booleanParameter(long tenantId, String key, boolean fallback) {
        String value = mapper.stringParameter(tenantId, key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private String stringParameter(long tenantId, String key, String fallback) {
        String value = mapper.stringParameter(tenantId, key);
        return value == null ? fallback : value;
    }

    private void assertTaskAccess(String workflowType, long taskId, long taskItemId) {
        var current = SecurityUtils.currentUser();
        var scope = dataPermissionService.current();
        String sql;
        if ("INSPECTION".equals(workflowType)) {
            sql = """
                    SELECT task.organization_id,
                           (task.assignee_user_id = ? OR EXISTS (
                               SELECT 1 FROM inspection_task_assignee assignee
                               WHERE assignee.tenant_id = task.tenant_id
                                 AND assignee.task_id = task.id AND assignee.user_id = ?
                           )) AS assigned
                    FROM inspection_task task
                    JOIN inspection_task_item item
                      ON item.tenant_id = task.tenant_id AND item.task_id = task.id
                     AND item.id = ?
                    WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                    """;
        } else {
            sql = """
                    SELECT task.organization_id,
                           (task.assignee_user_id = ? OR EXISTS (
                               SELECT 1 FROM maintenance_task_collaborator collaborator
                               WHERE collaborator.tenant_id = task.tenant_id
                                 AND collaborator.task_id = task.id AND collaborator.user_id = ?
                           )) AS assigned
                    FROM maintenance_task task
                    JOIN maintenance_task_item item
                      ON item.tenant_id = task.tenant_id AND item.task_id = task.id
                     AND item.id = ?
                    WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                    """;
        }
        List<TaskAccess> rows = jdbc.query(sql, (rs, rowNumber) -> new TaskAccess(
                rs.getLong("organization_id"), rs.getBoolean("assigned")
        ), current.userId(), current.userId(), taskItemId, current.tenantId(), taskId);
        if (rows.isEmpty()) {
            throw new BusinessException("MOBILE_TASK_ITEM_NOT_FOUND", "任务或作业项不存在", HttpStatus.NOT_FOUND);
        }
        TaskAccess access = rows.getFirst();
        if (!access.assigned() && !scope.allData()
                && !scope.organizationIds().contains(access.organizationId())) {
            throw new BusinessException("MOBILE_TASK_FORBIDDEN", "无权为该任务上传照片", HttpStatus.FORBIDDEN);
        }
    }

    private AttachmentHash attachment(long tenantId, long userId, long id) {
        List<AttachmentHash> rows = jdbc.query("""
                SELECT id, sha256 FROM system_attachment
                WHERE tenant_id = ? AND id = ? AND created_by = ?
                  AND status = 1 AND deleted = 0
                """, (rs, rowNumber) -> new AttachmentHash(rs.getLong(1), rs.getString(2)),
                tenantId, id, userId);
        if (rows.isEmpty()) {
            throw new BusinessException("MOBILE_EVIDENCE_ATTACHMENT_INVALID", "照片附件不存在或不属于当前用户");
        }
        return rows.getFirst();
    }

    private MobileDtos.PhotoEvidence findEvidenceByWatermarkedAttachment(long tenantId, long attachmentId) {
        List<MobileDtos.PhotoEvidence> rows = jdbc.query(evidenceSql()
                        + " WHERE evidence.tenant_id = ? AND evidence.watermarked_attachment_id = ? AND evidence.deleted = 0",
                this::mapEvidence, tenantId, attachmentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private MobileDtos.PhotoEvidence evidence(long tenantId, long id) {
        List<MobileDtos.PhotoEvidence> rows = jdbc.query(evidenceSql()
                        + " WHERE evidence.tenant_id = ? AND evidence.id = ? AND evidence.deleted = 0",
                this::mapEvidence, tenantId, id);
        if (rows.isEmpty()) {
            throw new BusinessException("MOBILE_EVIDENCE_NOT_FOUND", "照片证据不存在", HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private String evidenceSql() {
        return """
                SELECT evidence.id, evidence.workflow_type, evidence.task_id, evidence.task_item_id,
                       evidence.original_attachment_id, evidence.watermarked_attachment_id,
                       evidence.captured_device_time, evidence.received_server_time,
                       evidence.device_clock_offset_seconds, evidence.clock_skew_warning,
                       evidence.latitude, evidence.longitude, evidence.location_accuracy_meters,
                       evidence.location_provider, evidence.address_text,
                       evidence.fault_location_text, evidence.watermark_text,
                       evidence.original_sha256, evidence.watermarked_sha256
                FROM mobile_photo_evidence evidence
                """;
    }

    private MobileDtos.PhotoEvidence mapEvidence(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        return new MobileDtos.PhotoEvidence(
                rs.getLong("id"), rs.getString("workflow_type"), rs.getLong("task_id"),
                rs.getLong("task_item_id"), rs.getLong("original_attachment_id"),
                rs.getLong("watermarked_attachment_id"),
                rs.getTimestamp("captured_device_time").toLocalDateTime(),
                rs.getTimestamp("received_server_time").toLocalDateTime(),
                rs.getInt("device_clock_offset_seconds"), rs.getBoolean("clock_skew_warning"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("location_accuracy_meters"), rs.getString("location_provider"),
                rs.getString("address_text"), rs.getString("fault_location_text"),
                rs.getString("watermark_text"),
                rs.getString("original_sha256"), rs.getString("watermarked_sha256")
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record AttachmentHash(long id, String sha256) {
    }

    private record TaskAccess(long organizationId, boolean assigned) {
    }
}
