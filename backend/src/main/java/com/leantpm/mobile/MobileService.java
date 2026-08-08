package com.leantpm.mobile;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.inspection.InspectionDtos;
import com.leantpm.inspection.InspectionTaskService;
import com.leantpm.notification.NotificationService;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import static com.leantpm.foundation.service.PhotoWatermarkSettingsService.DEFAULT_TEMPLATE;

@Service
public class MobileService {
    private final MobileMapper mapper;
    private final DataPermissionService dataPermissionService;
    private final NotificationService notificationService;
    private final InspectionTaskService inspectionTaskService;
    private final JdbcTemplate jdbc;

    public MobileService(
            MobileMapper mapper,
            DataPermissionService dataPermissionService,
            NotificationService notificationService,
            InspectionTaskService inspectionTaskService,
            JdbcTemplate jdbc
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
        this.notificationService = notificationService;
        this.inspectionTaskService = inspectionTaskService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MobileDtos.Bootstrap bootstrap() {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        DataPermission scope = dataPermissionService.current();
        LocalDate today = LocalDate.now();
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
                        parameter(current.tenantId(), "mobile.photo-clock-skew-warning-seconds", 300, 0, 86400),
                        booleanParameter(current.tenantId(), "mobile.photo-watermark-enabled", true),
                        booleanParameter(current.tenantId(), "mobile.photo-save-original", true),
                        booleanParameter(current.tenantId(), "mobile.photo-save-watermarked", true),
                        stringParameter(current.tenantId(), "mobile.photo-watermark-template", DEFAULT_TEMPLATE),
                        stringParameter(current.tenantId(), "mobile.photo-watermark-position", "BOTTOM"),
                        parameter(current.tenantId(), "mobile.photo-watermark-background-opacity", 74, 0, 100),
                        stringParameter(current.tenantId(), "mobile.photo-watermark-font-color", "#ffffff"),
                        stringParameter(current.tenantId(), "mobile.photo-watermark-background-color", "#031922")
                ),
                new MobileDtos.AndroidVersionPolicy(
                        parameter(current.tenantId(), "mobile.android-min-version-code", 1, 1, Integer.MAX_VALUE),
                        stringParameter(current.tenantId(), "mobile.android-latest-version-name", "1.0.1"),
                        stringParameter(current.tenantId(), "mobile.android-download-url", ""),
                        stringParameter(current.tenantId(), "mobile.android-release-notes", "")
                ),
                mapper.equipmentStatusCount(current.tenantId(), scope),
                safeCount(mapper.inspectionCount(current.tenantId(), current.userId())),
                mapper.inspectionAbnormalCount(current.tenantId(), current.userId()),
                mapper.personalInspectionReport(
                        current.tenantId(), current.userId(),
                        today.withDayOfMonth(1), today,
                        today.withDayOfMonth(1).atStartOfDay(),
                        today.plusDays(1).atStartOfDay()
                ),
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

    @Transactional(readOnly = true)
    public MobileDtos.PersonalInspectionReport personalInspectionReport(
            LocalDate requestedStartDate,
            LocalDate requestedEndDate
    ) {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        LocalDate today = LocalDate.now();
        LocalDate startDate = requestedStartDate == null
                ? today.withDayOfMonth(1) : requestedStartDate;
        LocalDate endDate = requestedEndDate == null ? today : requestedEndDate;
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("MOBILE_REPORT_DATE_RANGE_INVALID", "报表结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > 366) {
            throw new BusinessException("MOBILE_REPORT_DATE_RANGE_TOO_LARGE", "个人报表单次查询范围不能超过 366 天");
        }
        return mapper.personalInspectionReport(
                current.tenantId(), current.userId(),
                startDate, endDate,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay()
        );
    }

    @Transactional
    public MobileDtos.PhotoEvidence registerPhotoEvidence(
            MobileDtos.RegisterPhotoEvidenceRequest request
    ) {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        if (request.taskId() <= 0 || (request.taskItemId() != null && request.taskItemId() <= 0)) {
            throw new BusinessException("MOBILE_EVIDENCE_REFERENCE_INVALID", "照片证据关联标识不正确");
        }
        assertTaskAccess(request.workflowType(), request.taskId(), request.taskItemId());
        boolean schemeRequiresWatermark = "INSPECTION".equals(request.workflowType())
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT COALESCE((
                            SELECT version.submission_photo_required_flag
                            FROM inspection_task task
                            JOIN inspection_scheme_version version
                              ON version.tenant_id = task.tenant_id
                             AND version.id = task.scheme_version_id
                            WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                            LIMIT 1
                        ), 0)
                        """, Boolean.class, current.tenantId(), request.taskId()));
        boolean watermarkEnabled = schemeRequiresWatermark
                || booleanParameter(current.tenantId(), "mobile.photo-watermark-enabled", true);
        boolean saveOriginal = booleanParameter(current.tenantId(), "mobile.photo-save-original", true);
        boolean saveWatermarked = schemeRequiresWatermark || (watermarkEnabled
                && booleanParameter(current.tenantId(), "mobile.photo-save-watermarked", true));
        Long originalAttachmentId = saveOriginal ? positive(request.originalAttachmentId()) : null;
        Long watermarkedAttachmentId = saveWatermarked ? positive(request.watermarkedAttachmentId()) : null;
        if ((saveOriginal && originalAttachmentId == null)
                || (saveWatermarked && watermarkedAttachmentId == null)
                || (originalAttachmentId == null && watermarkedAttachmentId == null)) {
            throw new BusinessException("MOBILE_EVIDENCE_FILES_REQUIRED", "请按当前系统配置上传并保留现场照片");
        }
        if (originalAttachmentId != null && originalAttachmentId.equals(watermarkedAttachmentId)) {
            throw new BusinessException("MOBILE_EVIDENCE_FILES_REQUIRED", "原图和水印图必须分别上传");
        }
        String watermarkText = request.watermarkText() == null ? "" : request.watermarkText().trim();
        if (saveWatermarked && watermarkText.isBlank()) {
            throw new BusinessException("MOBILE_EVIDENCE_WATERMARK_REQUIRED", "水印图必须包含可核验的水印文字");
        }
        Long retainedAttachmentId = watermarkedAttachmentId != null ? watermarkedAttachmentId : originalAttachmentId;
        MobileDtos.PhotoEvidence existing = findEvidenceByAttachment(current.tenantId(), retainedAttachmentId);
        if (existing != null) return existing;
        AttachmentHash original = originalAttachmentId == null
                ? null : attachment(current.tenantId(), current.userId(), originalAttachmentId);
        AttachmentHash watermarked = watermarkedAttachmentId == null
                ? null : attachment(current.tenantId(), current.userId(), watermarkedAttachmentId);
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
                originalAttachmentId, watermarkedAttachmentId,
                request.capturedDeviceTime(), request.serverReferenceTime(),
                request.deviceClockOffsetSeconds(),
                Math.abs((long) request.deviceClockOffsetSeconds()) > threshold,
                null, null, null, null, null, request.faultLocationText().trim(),
                watermarkText,
                original == null ? null : original.sha256(),
                watermarked == null ? null : watermarked.sha256(), current.userId(), current.userId());
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
        String normalizedToken = token.toLowerCase(Locale.ROOT);
        MobileDtos.EquipmentAccessProbe probe = mapper.equipmentAccessProbe(
                current.tenantId(), normalizedToken
        );
        if (probe == null || probe.equipmentId() <= 0) {
            throw new BusinessException(
                    "MOBILE_BARCODE_INVALID",
                    "未找到该设备二维码。二维码可能无效、内容不完整，或不属于当前企业，请重新扫描设备当前有效标签。",
                    HttpStatus.NOT_FOUND
            );
        }
        String equipmentLabel = equipmentLabel(probe);
        if (!probe.barcodeActive()) {
            throw new BusinessException(
                    "MOBILE_BARCODE_EXPIRED",
                    equipmentLabel + " 的二维码已解绑或已重新生成，请扫描设备上的最新标签。",
                    HttpStatus.GONE
            );
        }
        if (probe.equipmentDeleted()) {
            throw new BusinessException(
                    "MOBILE_EQUIPMENT_ARCHIVED",
                    equipmentLabel + " 已删除或归档，当前二维码不再有效，请联系设备管理员。",
                    HttpStatus.GONE
            );
        }
        if (probe.equipmentStatus() == null || probe.equipmentStatus() != 1) {
            throw new BusinessException(
                    "MOBILE_EQUIPMENT_DISABLED",
                    equipmentLabel + " 已停用，暂不可查看或登记点检，请联系设备管理员启用。",
                    HttpStatus.CONFLICT
            );
        }
        if (probe.organizationDeleted()
                || probe.organizationStatus() == null
                || probe.organizationStatus() != 1) {
            throw new BusinessException(
                    "MOBILE_EQUIPMENT_ORGANIZATION_DISABLED",
                    equipmentLabel + " 所属组织「" + organizationLabel(probe)
                            + "」已停用或归档，请联系系统管理员维护设备归属。",
                    HttpStatus.CONFLICT
            );
        }
        MobileDtos.EquipmentBase equipment = mapper.equipmentByToken(
                current.tenantId(),
                normalizedToken,
                dataPermissionService.current()
        );
        if (equipment == null) {
            throw new BusinessException(
                    "MOBILE_EQUIPMENT_DATA_SCOPE_DENIED",
                    equipmentLabel + " 归属「" + organizationLabel(probe)
                            + "」，当前账号的数据范围不包含该组织或其下级，"
                            + "请联系本组织管理员调整数据权限。",
                    HttpStatus.FORBIDDEN
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
                List.copyOf(mapper.todayInspections(
                        current.tenantId(), equipment.equipmentId()
                )),
                List.copyOf(mapper.assignees(current.tenantId())),
                List.copyOf(mapper.teams(current.tenantId()))
        );
    }

    private String equipmentLabel(MobileDtos.EquipmentAccessProbe probe) {
        String name = probe.equipmentName() == null || probe.equipmentName().isBlank()
                ? "设备" : probe.equipmentName().trim();
        String code = probe.equipmentCode() == null || probe.equipmentCode().isBlank()
                ? "" : "（" + probe.equipmentCode().trim() + "）";
        return name + code;
    }

    private String organizationLabel(MobileDtos.EquipmentAccessProbe probe) {
        return probe.organizationName() == null || probe.organizationName().isBlank()
                ? "未设置组织" : probe.organizationName().trim();
    }

    @Transactional
    public long createDirectInspectionReport(
            String token,
            MobileDtos.DirectInspectionReportRequest request,
            String idempotencyKey
    ) {
        var current = SecurityUtils.currentUser();
        MobileDtos.EquipmentContext context = equipment(token);
        MobileDtos.ApplicableInspectionScheme scheme = context.inspectionSchemes().stream()
                .filter(item -> item.schemeVersionId() == request.schemeVersionId())
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "MOBILE_INSPECTION_SCHEME_NOT_APPLICABLE",
                        "所选点检模板未启用、未发布或当前不可用",
                        HttpStatus.CONFLICT
                ));
        MobileDtos.TodayInspectionRecord todayRecord = context.todayInspections().stream()
                .filter(item -> item.schemeVersionId() == request.schemeVersionId())
                .findFirst()
                .orElse(null);
        if (todayRecord != null && !Boolean.TRUE.equals(request.allowRepeat())) {
            throw new BusinessException(
                    "MOBILE_INSPECTION_TODAY_EXISTS",
                    "该设备今天已存在同方案点检记录：" + todayRecord.taskCode(),
                    HttpStatus.CONFLICT
            );
        }
        String teamCode = context.assignees().stream()
                .filter(item -> item.userId() == current.userId())
                .map(MobileDtos.AssigneeOption::teamCode)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueTime = now.toLocalDate().atTime(23, 59, 59);
        if (!dueTime.isAfter(now)) dueTime = now.plusHours(1);
        String remark = request.remark() == null || request.remark().isBlank()
                ? "设备扫码直接点检报告" : request.remark().trim();
        return inspectionTaskService.createMobileSelfTask(
                new InspectionDtos.ManualTaskRequest(
                        context.equipment().equipmentId(),
                        scheme.schemeVersionId(),
                        now.toLocalDate(),
                        now,
                        dueTime,
                        List.of(current.userId()),
                        teamCode,
                        false,
                        remark
                ),
                idempotencyKey
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

    private void assertTaskAccess(String workflowType, long taskId, Long taskItemId) {
        var current = SecurityUtils.currentUser();
        var scope = dataPermissionService.current();
        String sql;
        if ("INSPECTION".equals(workflowType)) {
            sql = taskItemId == null ? """
                    SELECT task.organization_id,
                           (task.assignee_user_id = ? OR EXISTS (
                               SELECT 1 FROM inspection_task_assignee assignee
                               WHERE assignee.tenant_id = task.tenant_id
                                 AND assignee.task_id = task.id AND assignee.user_id = ?
                                 AND assignee.deleted = 0
                           )) AS assigned
                    FROM inspection_task task
                    WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                    """ : """
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
            sql = taskItemId == null ? """
                    SELECT task.organization_id,
                           (task.assignee_user_id = ? OR EXISTS (
                               SELECT 1 FROM maintenance_task_collaborator collaborator
                               WHERE collaborator.tenant_id = task.tenant_id
                                 AND collaborator.task_id = task.id AND collaborator.user_id = ?
                           )) AS assigned
                    FROM maintenance_task task
                    WHERE task.tenant_id = ? AND task.id = ? AND task.deleted = 0
                    """ : """
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
        Object[] parameters = taskItemId == null
                ? new Object[]{current.userId(), current.userId(), current.tenantId(), taskId}
                : new Object[]{current.userId(), current.userId(), taskItemId, current.tenantId(), taskId};
        List<TaskAccess> rows = jdbc.query(sql, (rs, rowNumber) -> new TaskAccess(
                rs.getLong("organization_id"), rs.getBoolean("assigned")
        ), parameters);
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

    private MobileDtos.PhotoEvidence findEvidenceByAttachment(long tenantId, long attachmentId) {
        List<MobileDtos.PhotoEvidence> rows = jdbc.query(evidenceSql()
                        + " WHERE evidence.tenant_id = ? AND (evidence.watermarked_attachment_id = ? OR evidence.original_attachment_id = ?) AND evidence.deleted = 0",
                this::mapEvidence, tenantId, attachmentId, attachmentId);
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
                rs.getObject("task_item_id", Long.class),
                rs.getObject("original_attachment_id", Long.class),
                rs.getObject("watermarked_attachment_id", Long.class),
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

    private Long positive(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private record AttachmentHash(long id, String sha256) {
    }

    private record TaskAccess(long organizationId, boolean assigned) {
    }
}
