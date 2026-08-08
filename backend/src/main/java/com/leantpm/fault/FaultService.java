package com.leantpm.fault;

import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentService;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class FaultService {
    private static final Set<String> CLOSED_REPAIR = Set.of("CLOSED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final DataPermissionService dataPermissionService;
    private final NumberRuleService numberRuleService;
    private final EquipmentService equipmentService;

    public FaultService(
            JdbcTemplate jdbc,
            DataPermissionService dataPermissionService,
            NumberRuleService numberRuleService,
            EquipmentService equipmentService
    ) {
        this.jdbc = jdbc;
        this.dataPermissionService = dataPermissionService;
        this.numberRuleService = numberRuleService;
        this.equipmentService = equipmentService;
    }

    @Transactional(readOnly = true)
    public PageResult<FaultDtos.ReportRow> reports(
            String keyword, String status, int page, int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        QueryScope access = reportScope(scope, current.userId(), "report");
        List<Object> parameters = new ArrayList<>(List.of(current.tenantId()));
        String filters = access.sql();
        parameters.addAll(access.parameters());
        if (keyword != null && !keyword.isBlank()) {
            filters += " AND (report.report_code LIKE ? OR report.fault_title LIKE ? OR equipment.equipment_code LIKE ? OR equipment.equipment_name LIKE ?)";
            String pattern = "%" + keyword.trim() + "%";
            parameters.addAll(List.of(pattern, pattern, pattern, pattern));
        }
        if (status != null && !status.isBlank()) {
            filters += " AND report.report_status = ?";
            parameters.add(status.trim().toUpperCase());
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment_fault_report report
                JOIN equipment ON equipment.tenant_id = report.tenant_id
                  AND equipment.id = report.equipment_id AND equipment.deleted = 0
                WHERE report.tenant_id = ? AND report.deleted = 0
                """ + filters, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(pageSize);
        pageParameters.add((page - 1) * pageSize);
        List<FaultDtos.ReportRow> rows = jdbc.query(reportSelect() + """
                WHERE report.tenant_id = ? AND report.deleted = 0
                """ + filters + " ORDER BY report.fault_time DESC, report.id DESC LIMIT ? OFFSET ?",
                this::mapReport, pageParameters.toArray());
        return PageResult.of(rows, total == null ? 0 : total, page, pageSize);
    }

    @Transactional(readOnly = true)
    public FaultDtos.ReportRow report(long id) {
        var current = SecurityUtils.currentUser();
        List<FaultDtos.ReportRow> rows = jdbc.query(
                reportSelect() + " WHERE report.tenant_id = ? AND report.id = ? AND report.deleted = 0",
                this::mapReport, current.tenantId(), id
        );
        if (rows.isEmpty()) throw notFound("FAULT_REPORT_NOT_FOUND", "报修单不存在");
        FaultDtos.ReportRow row = rows.getFirst();
        if (!dataPermissionService.current().canAccess(row.reporterUserId(), row.organizationId())) {
            throw forbidden();
        }
        return row;
    }

    @Transactional
    public long createReport(FaultDtos.CreateReportRequest request) {
        var current = SecurityUtils.currentUser();
        FaultDtos.EquipmentTarget equipment = equipment(request.equipmentId());
        DataPermission scope = dataPermissionService.current();
        if (!scope.canCreateIn(equipment.organizationId())
                && !(scope.selfData() && canReportEquipment(current.tenantId(), request.equipmentId(), current.userId()))) {
            throw forbidden();
        }
        long id = insertReport(
                current.tenantId(), request.equipmentId(), equipment.organizationId(),
                request.faultTime(), request.faultTitle(), request.faultDescription(),
                request.severity(), "MANUAL", null, current.userId(), "REPORTED"
        );
        linkAttachments(current.tenantId(), id, null, request.attachmentIds(), "REPORT");
        markEquipmentFault(equipment, "报修单触发设备故障状态");
        return id;
    }

    @Transactional
    public void acceptReport(long id, FaultDtos.VersionRequest request) {
        transitionReport(id, request.version(), "REPORTED", "ACCEPTED", null);
    }

    @Transactional
    public void rejectReport(long id, FaultDtos.RejectRequest request) {
        FaultDtos.ReportRow row = report(id);
        int changed = jdbc.update("""
                UPDATE equipment_fault_report
                SET report_status = 'REJECTED', rejected_reason = ?,
                    updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND report_status = 'REPORTED'
                  AND version = ? AND deleted = 0
                """, request.reason(), SecurityUtils.currentUser().userId(),
                SecurityUtils.currentUser().tenantId(), row.id(), request.version());
        if (changed != 1) throw conflict("报修单状态已变化，请刷新后重试");
    }

    @Transactional
    public void cancelReport(long id, FaultDtos.RejectRequest request) {
        FaultDtos.ReportRow row = report(id);
        int changed = jdbc.update("""
                UPDATE equipment_fault_report
                SET report_status = 'CANCELLED', rejected_reason = ?,
                    updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ?
                  AND report_status IN ('REPORTED', 'ACCEPTED')
                  AND version = ? AND deleted = 0
                """, request.reason(), SecurityUtils.currentUser().userId(),
                SecurityUtils.currentUser().tenantId(), row.id(), request.version());
        if (changed != 1) throw conflict("报修单不能取消或状态已变化");
    }

    @Transactional
    public long createRepair(long reportId, FaultDtos.CreateRepairRequest request) {
        FaultDtos.ReportRow report = report(reportId);
        if (report.repairOrderId() != null) return report.repairOrderId();
        if (!Set.of("REPORTED", "ACCEPTED").contains(report.reportStatus())) {
            throw conflict("当前报修单状态不能创建维修工单");
        }
        long repairId = insertRepair(
                report, request.primaryRepairerUserId(), request.collaboratorUserIds(),
                defaultRestore(request.restoreStatusCode()), request.reportVersion()
        );
        return repairId;
    }

    @Transactional
    public long createFromInspectionAbnormal(long abnormalId) {
        return createFromAbnormal("INSPECTION", "inspection_abnormal", "inspection_task", abnormalId);
    }

    @Transactional
    public long createFromMaintenanceAbnormal(long abnormalId) {
        return createFromAbnormal("MAINTENANCE", "maintenance_abnormal", "maintenance_task", abnormalId);
    }

    @Transactional(readOnly = true)
    public PageResult<FaultDtos.RepairRow> repairs(
            String keyword, String status, boolean mineOnly, int page, int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        QueryScope access = repairScope(scope, current.userId(), "repair", mineOnly);
        List<Object> parameters = new ArrayList<>(List.of(current.tenantId()));
        String filters = access.sql();
        parameters.addAll(access.parameters());
        if (keyword != null && !keyword.isBlank()) {
            filters += " AND (repair.repair_code LIKE ? OR report.report_code LIKE ? OR report.fault_title LIKE ? OR equipment.equipment_name LIKE ?)";
            String pattern = "%" + keyword.trim() + "%";
            parameters.addAll(List.of(pattern, pattern, pattern, pattern));
        }
        if (status != null && !status.isBlank()) {
            filters += " AND repair.repair_status = ?";
            parameters.add(status.trim().toUpperCase());
        }
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM equipment_repair_order repair
                JOIN equipment_fault_report report ON report.tenant_id = repair.tenant_id AND report.id = repair.fault_report_id
                JOIN equipment ON equipment.tenant_id = repair.tenant_id AND equipment.id = repair.equipment_id AND equipment.deleted = 0
                WHERE repair.tenant_id = ? AND repair.deleted = 0
                """ + filters, Long.class, parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(pageSize);
        pageParameters.add((page - 1) * pageSize);
        List<FaultDtos.RepairRow> rows = jdbc.query(repairSelect() + """
                WHERE repair.tenant_id = ? AND repair.deleted = 0
                """ + filters + " GROUP BY repair.id ORDER BY repair.created_time DESC, repair.id DESC LIMIT ? OFFSET ?",
                this::mapRepair, pageParameters.toArray());
        return PageResult.of(rows, total == null ? 0 : total, page, pageSize);
    }

    @Transactional(readOnly = true)
    public FaultDtos.RepairRow repair(long id) {
        var current = SecurityUtils.currentUser();
        List<FaultDtos.RepairRow> rows = jdbc.query(
                repairSelect() + " WHERE repair.tenant_id = ? AND repair.id = ? AND repair.deleted = 0 GROUP BY repair.id",
                this::mapRepair, current.tenantId(), id
        );
        if (rows.isEmpty()) throw notFound("REPAIR_ORDER_NOT_FOUND", "维修工单不存在");
        FaultDtos.RepairRow row = rows.getFirst();
        DataPermission scope = dataPermissionService.current();
        boolean collaborator = row.collaboratorUserIds().contains(current.userId());
        if (!scope.canAccess(row.primaryRepairerUserId() == null ? 0 : row.primaryRepairerUserId(), row.organizationId())
                && !collaborator) throw forbidden();
        return row;
    }

    @Transactional
    public void assign(long id, FaultDtos.AssignmentRequest request) {
        FaultDtos.RepairRow row = repair(id);
        validateActiveUsers(request.primaryRepairerUserId(), request.collaboratorUserIds());
        int changed = jdbc.update("""
                UPDATE equipment_repair_order
                SET primary_repairer_user_id = ?, repair_status = 'ASSIGNED',
                    assigned_time = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ?
                  AND repair_status IN ('PENDING_ASSIGNMENT', 'ASSIGNED')
                  AND version = ? AND deleted = 0
                """, request.primaryRepairerUserId(), SecurityUtils.currentUser().userId(),
                SecurityUtils.currentUser().tenantId(), row.id(), request.version());
        if (changed != 1) throw conflict("维修工单无法派工或状态已变化");
        replaceCollaborators(id, request.collaboratorUserIds());
        event(id, "ASSIGN", row.repairStatus(), "ASSIGNED", "维修工单派工");
    }

    @Transactional
    public void start(long id, FaultDtos.ActionRequest request) {
        FaultDtos.RepairRow row = repair(id);
        assertExecutor(row);
        transitionRepair(row, request.version(), Set.of("ASSIGNED"), "IN_PROGRESS", "START", request.remark(), "started_time = CURRENT_TIMESTAMP(3),");
        markEquipmentRepair(equipment(row.equipmentId()), "维修工单开始执行");
    }

    @Transactional
    public void pause(long id, FaultDtos.ActionRequest request) {
        FaultDtos.RepairRow row = repair(id);
        assertExecutor(row);
        transitionRepair(row, request.version(), Set.of("IN_PROGRESS"), "PAUSED", "PAUSE", request.remark(), "paused_time = CURRENT_TIMESTAMP(3),");
    }

    @Transactional
    public void resume(long id, FaultDtos.ActionRequest request) {
        FaultDtos.RepairRow row = repair(id);
        assertExecutor(row);
        transitionRepair(row, request.version(), Set.of("PAUSED"), "IN_PROGRESS", "RESUME", request.remark(), "total_paused_seconds = total_paused_seconds + TIMESTAMPDIFF(SECOND, paused_time, CURRENT_TIMESTAMP(3)), paused_time = NULL,");
    }

    @Transactional
    public void complete(long id, FaultDtos.CompleteRequest request) {
        FaultDtos.RepairRow row = repair(id);
        assertExecutor(row);
        int changed = jdbc.update("""
                UPDATE equipment_repair_order
                SET repair_status = 'PENDING_ACCEPTANCE', completed_time = CURRENT_TIMESTAMP(3),
                    repair_measure = ?, repair_conclusion = ?,
                    effective_work_seconds = GREATEST(0,
                        TIMESTAMPDIFF(SECOND, started_time, CURRENT_TIMESTAMP(3)) - total_paused_seconds),
                    updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND repair_status = 'IN_PROGRESS'
                  AND version = ? AND deleted = 0
                """, request.repairMeasure(), request.repairConclusion(),
                SecurityUtils.currentUser().userId(), SecurityUtils.currentUser().tenantId(),
                row.id(), request.version());
        if (changed != 1) throw conflict("只有维修中的工单可以完工提交");
        linkAttachments(SecurityUtils.currentUser().tenantId(), row.faultReportId(), id, request.attachmentIds(), "REPAIR");
        event(id, "COMPLETE", row.repairStatus(), "PENDING_ACCEPTANCE", "维修完工，等待验收");
    }

    @Transactional
    public void acceptance(long id, FaultDtos.AcceptanceRequest request) {
        FaultDtos.RepairRow row = repair(id);
        if (!"PENDING_ACCEPTANCE".equals(row.repairStatus())) throw conflict("当前工单不在待验收状态");
        String target = request.passed() ? "CLOSED" : "IN_PROGRESS";
        String restore = defaultRestore(request.restoreStatusCode());
        int changed = jdbc.update("""
                UPDATE equipment_repair_order
                SET repair_status = ?, acceptance_result = ?, acceptance_comment = ?,
                    accepted_time = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
                    restore_status_code = ?, updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND repair_status = 'PENDING_ACCEPTANCE'
                  AND version = ? AND deleted = 0
                """, target, request.passed() ? "PASSED" : "REJECTED", request.comment(),
                target, restore, SecurityUtils.currentUser().userId(),
                SecurityUtils.currentUser().tenantId(), id, request.version());
        if (changed != 1) throw conflict("维修验收状态已变化");
        event(id, "ACCEPTANCE", row.repairStatus(), target, request.comment());
        linkAttachments(SecurityUtils.currentUser().tenantId(), row.faultReportId(), id, request.attachmentIds(), "ACCEPTANCE");
        if (request.passed()) {
            jdbc.update("""
                    UPDATE equipment_fault_report
                    SET report_status = 'CLOSED', acceptance_comment = ?,
                        updated_by = ?, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND deleted = 0
                    """, request.comment(), SecurityUtils.currentUser().userId(),
                    SecurityUtils.currentUser().tenantId(), row.faultReportId());
            restoreEquipment(equipment(row.equipmentId()), restore, "维修验收通过，恢复设备状态");
        }
    }

    @Transactional(readOnly = true)
    public List<FaultDtos.MaterialRow> materials(long repairId) {
        repair(repairId);
        return jdbc.query("""
                SELECT id, material_code, material_name, quantity, unit, unit_price,
                       total_amount, remark, version
                FROM equipment_repair_material
                WHERE tenant_id = ? AND repair_order_id = ? AND deleted = 0
                ORDER BY id
                """, (rs, rowNumber) -> new FaultDtos.MaterialRow(
                        rs.getLong("id"), rs.getString("material_code"),
                        rs.getString("material_name"), rs.getBigDecimal("quantity"),
                        rs.getString("unit"), rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("total_amount"), rs.getString("remark"),
                        rs.getInt("version")
                ), SecurityUtils.currentUser().tenantId(), repairId);
    }

    @Transactional
    public long addMaterial(long repairId, FaultDtos.SaveMaterialRequest request) {
        FaultDtos.RepairRow repair = repair(repairId);
        if (CLOSED_REPAIR.contains(repair.repairStatus())) throw conflict("已关闭工单不能维护材料");
        jdbc.update("""
                INSERT INTO equipment_repair_material
                    (tenant_id, repair_order_id, material_code, material_name,
                     quantity, unit, unit_price, remark, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, SecurityUtils.currentUser().tenantId(), repairId, clean(request.materialCode()),
                request.materialName().trim(), request.quantity(), clean(request.unit()),
                request.unitPrice(), clean(request.remark()), SecurityUtils.currentUser().userId(),
                SecurityUtils.currentUser().userId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Transactional
    public void updateMaterial(long repairId, long materialId, FaultDtos.SaveMaterialRequest request) {
        FaultDtos.RepairRow repair = repair(repairId);
        if (CLOSED_REPAIR.contains(repair.repairStatus())) throw conflict("已关闭工单不能维护材料");
        if (request.version() == null) throw new BusinessException("MATERIAL_VERSION_REQUIRED", "修改材料时必须提供版本号");
        int changed = jdbc.update("""
                UPDATE equipment_repair_material
                SET material_code = ?, material_name = ?, quantity = ?, unit = ?,
                    unit_price = ?, remark = ?, updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND repair_order_id = ? AND id = ?
                  AND version = ? AND deleted = 0
                """, clean(request.materialCode()), request.materialName().trim(), request.quantity(),
                clean(request.unit()), request.unitPrice(), clean(request.remark()),
                SecurityUtils.currentUser().userId(), SecurityUtils.currentUser().tenantId(),
                repairId, materialId, request.version());
        if (changed != 1) throw conflict("材料不存在或已被其他用户修改");
    }

    @Transactional
    public void deleteMaterial(long repairId, long materialId, int version) {
        FaultDtos.RepairRow repair = repair(repairId);
        if (CLOSED_REPAIR.contains(repair.repairStatus())) throw conflict("已关闭工单不能维护材料");
        int changed = jdbc.update("""
                UPDATE equipment_repair_material
                SET deleted = 1, updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND repair_order_id = ? AND id = ?
                  AND version = ? AND deleted = 0
                """, SecurityUtils.currentUser().userId(), SecurityUtils.currentUser().tenantId(),
                repairId, materialId, version);
        if (changed != 1) throw conflict("材料不存在或已被其他用户修改");
    }

    @Transactional(readOnly = true)
    public List<FaultDtos.EventRow> events(long repairId) {
        repair(repairId);
        return jdbc.query("""
                SELECT event.id, event.event_type, event.from_status, event.to_status,
                       event.event_remark, user.real_name AS operator_name, event.event_time
                FROM equipment_repair_event event
                LEFT JOIN system_user user ON user.tenant_id = event.tenant_id AND user.id = event.operator_id
                WHERE event.tenant_id = ? AND event.repair_order_id = ?
                ORDER BY event.event_time, event.id
                """, (rs, rowNumber) -> new FaultDtos.EventRow(
                        rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("event_remark"), rs.getString("operator_name"),
                        rs.getTimestamp("event_time").toLocalDateTime()
                ), SecurityUtils.currentUser().tenantId(), repairId);
    }

    @Transactional(readOnly = true)
    public List<FaultDtos.AttachmentRow> attachments(long reportId) {
        report(reportId);
        return jdbc.query("""
                SELECT relation.attachment_id, attachment.original_name,
                       attachment.content_type, relation.attachment_stage,
                       relation.created_time
                FROM equipment_fault_attachment relation
                JOIN system_attachment attachment
                  ON attachment.tenant_id = relation.tenant_id
                 AND attachment.id = relation.attachment_id
                 AND attachment.deleted = 0
                WHERE relation.tenant_id = ? AND relation.fault_report_id = ?
                ORDER BY relation.created_time, relation.id
                """, (rs, rowNumber) -> new FaultDtos.AttachmentRow(
                        rs.getLong("attachment_id"), rs.getString("original_name"),
                        rs.getString("content_type"), rs.getString("attachment_stage"),
                        rs.getTimestamp("created_time").toLocalDateTime()
                ), SecurityUtils.currentUser().tenantId(), reportId);
    }

    @Transactional(readOnly = true)
    public FaultDtos.Statistics statistics() {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        QueryScope access = repairScope(scope, current.userId(), "repair", false);
        List<Object> parameters = new ArrayList<>(List.of(current.tenantId()));
        parameters.addAll(access.parameters());
        return jdbc.queryForObject("""
                SELECT
                    SUM(report.report_status IN ('REPORTED','ACCEPTED')) AS open_reports,
                    SUM(repair.repair_status IN ('ASSIGNED','IN_PROGRESS','PAUSED')) AS active_repairs,
                    SUM(repair.repair_status = 'PENDING_ACCEPTANCE') AS pending_acceptance,
                    SUM(repair.repair_status = 'CLOSED') AS closed_repairs,
                    COALESCE(SUM(material.total_amount), 0) AS material_cost,
                    COALESCE(AVG(CASE WHEN repair.repair_status = 'CLOSED' THEN repair.effective_work_seconds / 60 END), 0) AS average_minutes
                FROM equipment_repair_order repair
                JOIN equipment_fault_report report ON report.tenant_id = repair.tenant_id AND report.id = repair.fault_report_id
                LEFT JOIN (
                    SELECT tenant_id, repair_order_id, SUM(total_amount) AS total_amount
                    FROM equipment_repair_material
                    WHERE deleted = 0
                    GROUP BY tenant_id, repair_order_id
                ) material ON material.tenant_id = repair.tenant_id AND material.repair_order_id = repair.id
                WHERE repair.tenant_id = ? AND repair.deleted = 0
                """ + access.sql(), (rs, rowNumber) -> new FaultDtos.Statistics(
                        rs.getLong("open_reports"), rs.getLong("active_repairs"),
                        rs.getLong("pending_acceptance"), rs.getLong("closed_repairs"),
                        rs.getBigDecimal("material_cost"), rs.getBigDecimal("average_minutes")
                ), parameters.toArray());
    }

    private long createFromAbnormal(String source, String abnormalTable, String taskTable, long abnormalId) {
        var current = SecurityUtils.currentUser();
        List<AbnormalSource> rows = jdbc.query("""
                SELECT abnormal.id, abnormal.equipment_id, task.organization_id,
                       abnormal.abnormal_title, abnormal.abnormal_description,
                       abnormal.severity, abnormal.created_time, abnormal.repair_order_id,
                       abnormal.version
                FROM %s abnormal
                JOIN %s task ON task.tenant_id = abnormal.tenant_id AND task.id = abnormal.task_id
                WHERE abnormal.tenant_id = ? AND abnormal.id = ? AND abnormal.deleted = 0
                """.formatted(abnormalTable, taskTable), (rs, rowNumber) -> new AbnormalSource(
                        rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime(),
                        rs.getObject(8, Long.class), rs.getInt(9)
                ), current.tenantId(), abnormalId);
        if (rows.isEmpty()) throw notFound("ABNORMAL_NOT_FOUND", "异常记录不存在");
        AbnormalSource abnormal = rows.getFirst();
        if (!dataPermissionService.current().canAccess(current.userId(), abnormal.organizationId())) throw forbidden();
        if (abnormal.repairOrderId() != null) return abnormal.repairOrderId();
        FaultDtos.EquipmentTarget equipment = equipment(abnormal.equipmentId());
        long reportId = insertReport(
                current.tenantId(), abnormal.equipmentId(), abnormal.organizationId(),
                abnormal.faultTime(), abnormal.title(), abnormal.description(),
                abnormal.severity(), source, abnormal.id(), current.userId(), "CONVERTED"
        );
        FaultDtos.ReportRow report = report(reportId);
        long repairId = insertRepair(report, null, List.of(), "IDLE", report.version());
        int changed = jdbc.update("""
                UPDATE %s SET repair_order_id = ?, updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND repair_order_id IS NULL
                  AND version = ? AND deleted = 0
                """.formatted(abnormalTable), repairId, current.userId(), current.tenantId(),
                abnormal.id(), abnormal.version());
        if (changed != 1) throw conflict("异常已由其他请求转为维修工单");
        markEquipmentFault(equipment, source + "异常转维修工单");
        return repairId;
    }

    private long insertReport(
            long tenantId, long equipmentId, long organizationId, LocalDateTime faultTime,
            String title, String description, String severity, String source,
            Long sourceId, long reporterId, String status
    ) {
        String code = numberRuleService.generate(tenantId, reporterId, "FAULT_REPORT").businessNumber();
        jdbc.update("""
                INSERT INTO equipment_fault_report
                    (tenant_id, report_code, equipment_id, organization_id, fault_time,
                     fault_title, fault_description, severity, source_type,
                     source_business_id, reporter_user_id, report_status,
                     created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, code, equipmentId, organizationId, faultTime,
                title.trim(), description.trim(), severity, source, sourceId,
                reporterId, status, reporterId, reporterId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRepair(
            FaultDtos.ReportRow report, Long primaryId, List<Long> collaboratorIds,
            String restoreStatus, int reportVersion
    ) {
        var current = SecurityUtils.currentUser();
        if (primaryId != null) validateActiveUsers(primaryId, collaboratorIds);
        String code = numberRuleService.generate(current.tenantId(), current.userId(), "REPAIR_ORDER").businessNumber();
        String initialStatus = primaryId == null ? "PENDING_ASSIGNMENT" : "ASSIGNED";
        jdbc.update("""
                INSERT INTO equipment_repair_order
                    (tenant_id, repair_code, fault_report_id, equipment_id,
                     organization_id, repair_status, primary_repairer_user_id,
                     assigned_time, restore_status_code, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP(3) END, ?, ?, ?)
                """, current.tenantId(), code, report.id(), report.equipmentId(),
                report.organizationId(), initialStatus, primaryId, primaryId,
                restoreStatus, current.userId(), current.userId());
        long repairId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        replaceCollaborators(repairId, collaboratorIds);
        int changed = jdbc.update("""
                UPDATE equipment_fault_report
                SET report_status = 'CONVERTED', updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ?
                  AND report_status IN ('REPORTED','ACCEPTED','CONVERTED')
                  AND version = ? AND deleted = 0
                """, current.userId(), current.tenantId(), report.id(), reportVersion);
        if (changed != 1) throw conflict("报修单状态已变化，不能创建工单");
        event(repairId, "CREATE", null, initialStatus, "由报修单创建维修工单");
        return repairId;
    }

    private void transitionReport(long id, int version, String from, String to, String remark) {
        FaultDtos.ReportRow row = report(id);
        int changed = jdbc.update("""
                UPDATE equipment_fault_report
                SET report_status = ?, acceptance_comment = ?, updated_by = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND report_status = ?
                  AND version = ? AND deleted = 0
                """, to, remark, SecurityUtils.currentUser().userId(),
                SecurityUtils.currentUser().tenantId(), row.id(), from, version);
        if (changed != 1) throw conflict("报修单状态已变化，请刷新后重试");
    }

    private void transitionRepair(
            FaultDtos.RepairRow row, int version, Set<String> fromStatuses,
            String toStatus, String eventType, String remark, String extraSet
    ) {
        String placeholders = String.join(",", java.util.Collections.nCopies(fromStatuses.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(toStatus);
        parameters.add(SecurityUtils.currentUser().userId());
        parameters.add(SecurityUtils.currentUser().tenantId());
        parameters.add(row.id());
        parameters.addAll(fromStatuses);
        parameters.add(version);
        int changed = jdbc.update("UPDATE equipment_repair_order SET repair_status = ?, " + extraSet
                + " updated_by = ?, version = version + 1 WHERE tenant_id = ? AND id = ? AND repair_status IN ("
                + placeholders + ") AND version = ? AND deleted = 0", parameters.toArray());
        if (changed != 1) throw conflict("维修工单状态不允许该操作或已变化");
        event(row.id(), eventType, row.repairStatus(), toStatus, remark);
    }

    private void event(long repairId, String type, String from, String to, String remark) {
        jdbc.update("""
                INSERT INTO equipment_repair_event
                    (tenant_id, repair_order_id, event_type, from_status,
                     to_status, event_remark, operator_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, SecurityUtils.currentUser().tenantId(), repairId, type, from, to,
                clean(remark), SecurityUtils.currentUser().userId());
    }

    private void replaceCollaborators(long repairId, List<Long> collaboratorIds) {
        jdbc.update("DELETE FROM equipment_repair_collaborator WHERE tenant_id = ? AND repair_order_id = ?",
                SecurityUtils.currentUser().tenantId(), repairId);
        if (collaboratorIds == null) return;
        for (Long userId : new LinkedHashSet<>(collaboratorIds)) {
            jdbc.update("""
                    INSERT INTO equipment_repair_collaborator
                        (tenant_id, repair_order_id, user_id, created_by)
                    VALUES (?, ?, ?, ?)
                    """, SecurityUtils.currentUser().tenantId(), repairId, userId,
                    SecurityUtils.currentUser().userId());
        }
    }

    private void validateActiveUsers(Long primaryId, List<Long> collaborators) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (primaryId != null) ids.add(primaryId);
        if (collaborators != null) ids.addAll(collaborators);
        if (primaryId != null && collaborators != null && collaborators.contains(primaryId)) {
            throw new BusinessException("REPAIR_ASSIGNMENT_DUPLICATE", "主维修人不能同时作为协作人");
        }
        if (ids.isEmpty()) return;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_user
                WHERE tenant_id = ? AND id IN (%s) AND status = 1 AND deleted = 0
                """.formatted(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))),
                Long.class, prepend(SecurityUtils.currentUser().tenantId(), new ArrayList<>(ids)));
        if (count == null || count != ids.size()) throw new BusinessException("REPAIR_USER_INVALID", "维修人员不存在或已停用");
    }

    private void assertExecutor(FaultDtos.RepairRow row) {
        long userId = SecurityUtils.currentUser().userId();
        if ((row.primaryRepairerUserId() == null || row.primaryRepairerUserId() != userId)
                && !row.collaboratorUserIds().contains(userId)
                && !dataPermissionService.current().allData()) {
            throw forbidden();
        }
    }

    private FaultDtos.EquipmentTarget equipment(long equipmentId) {
        List<FaultDtos.EquipmentTarget> rows = jdbc.query("""
                SELECT equipment.id, equipment.organization_id,
                       current_status.status_code, current_status.version
                FROM equipment
                JOIN equipment_current_status current_status
                  ON current_status.tenant_id = equipment.tenant_id
                 AND current_status.equipment_id = equipment.id
                WHERE equipment.tenant_id = ? AND equipment.id = ?
                  AND equipment.status = 1 AND equipment.deleted = 0
                """, (rs, rowNumber) -> new FaultDtos.EquipmentTarget(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)
                ), SecurityUtils.currentUser().tenantId(), equipmentId);
        if (rows.isEmpty()) throw notFound("EQUIPMENT_NOT_FOUND", "设备不存在或已停用");
        return rows.getFirst();
    }

    private boolean canReportEquipment(long tenantId, long equipmentId, long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT 1 FROM equipment_responsible_person
                    WHERE tenant_id = ? AND equipment_id = ? AND user_id = ? AND status = 1 AND deleted = 0
                    UNION ALL
                    SELECT 1 FROM inspection_task
                    WHERE tenant_id = ? AND equipment_id = ? AND assignee_user_id = ? AND deleted = 0
                    UNION ALL
                    SELECT 1 FROM maintenance_task
                    WHERE tenant_id = ? AND equipment_id = ? AND assignee_user_id = ? AND deleted = 0
                ) access_rows
                """, Long.class, tenantId, equipmentId, userId, tenantId, equipmentId, userId,
                tenantId, equipmentId, userId);
        return count != null && count > 0;
    }

    private void markEquipmentFault(FaultDtos.EquipmentTarget equipment, String reason) {
        if ("STOPPED".equals(equipment.statusCode())) return;
        equipmentService.changeStatusFromBusiness(equipment.id(), new EquipmentDtos.ChangeStatusRequest(
                "STOPPED", reason, "SYSTEM", equipment.statusVersion()
        ));
    }

    private void markEquipmentRepair(FaultDtos.EquipmentTarget equipment, String reason) {
        if ("STOPPED".equals(equipment.statusCode())) return;
        equipmentService.changeStatusFromBusiness(equipment.id(), new EquipmentDtos.ChangeStatusRequest(
                "STOPPED", reason, "SYSTEM", equipment.statusVersion()
        ));
    }

    private void restoreEquipment(FaultDtos.EquipmentTarget equipment, String status, String reason) {
        if (status.equals(equipment.statusCode())) return;
        equipmentService.changeStatusFromBusiness(equipment.id(), new EquipmentDtos.ChangeStatusRequest(
                status, reason, "SYSTEM", equipment.statusVersion()
        ));
    }

    private void linkAttachments(
            long tenantId, long reportId, Long repairId, List<Long> ids, String stage
    ) {
        if (ids == null) return;
        for (Long attachmentId : new LinkedHashSet<>(ids)) {
            int changed = jdbc.update("""
                    INSERT IGNORE INTO equipment_fault_attachment
                        (tenant_id, fault_report_id, repair_order_id,
                         attachment_id, attachment_stage, created_by)
                    SELECT ?, ?, ?, attachment.id, ?, ?
                    FROM system_attachment attachment
                    WHERE attachment.tenant_id = ? AND attachment.id = ?
                      AND attachment.status = 1 AND attachment.deleted = 0
                    """, tenantId, reportId, repairId, stage, SecurityUtils.currentUser().userId(),
                    tenantId, attachmentId);
            if (changed != 1) throw new BusinessException("FAULT_ATTACHMENT_INVALID", "附件不存在、已失效或重复关联");
        }
    }

    private String reportSelect() {
        return """
                SELECT report.id, report.report_code, report.equipment_id,
                       equipment.equipment_code, equipment.equipment_name,
                       report.organization_id, organization.organization_name,
                       report.fault_time, report.fault_title, report.fault_description,
                       report.severity, report.source_type, report.source_business_id,
                       report.reporter_user_id, reporter.real_name AS reporter_name,
                       report.report_status, report.rejected_reason, report.created_time,
                       report.version, repair.id AS repair_order_id, repair.repair_code
                FROM equipment_fault_report report
                JOIN equipment ON equipment.tenant_id = report.tenant_id AND equipment.id = report.equipment_id AND equipment.deleted = 0
                JOIN organization ON organization.tenant_id = report.tenant_id AND organization.id = report.organization_id AND organization.deleted = 0
                JOIN system_user reporter ON reporter.tenant_id = report.tenant_id AND reporter.id = report.reporter_user_id
                LEFT JOIN equipment_repair_order repair ON repair.tenant_id = report.tenant_id AND repair.fault_report_id = report.id AND repair.deleted = 0
                """;
    }

    private FaultDtos.ReportRow mapReport(ResultSet rs, int rowNumber) throws SQLException {
        return new FaultDtos.ReportRow(
                rs.getLong("id"), rs.getString("report_code"), rs.getLong("equipment_id"),
                rs.getString("equipment_code"), rs.getString("equipment_name"),
                rs.getLong("organization_id"), rs.getString("organization_name"),
                rs.getTimestamp("fault_time").toLocalDateTime(), rs.getString("fault_title"),
                rs.getString("fault_description"), rs.getString("severity"),
                rs.getString("source_type"), rs.getObject("source_business_id", Long.class),
                rs.getLong("reporter_user_id"), rs.getString("reporter_name"),
                rs.getString("report_status"), rs.getString("rejected_reason"),
                rs.getTimestamp("created_time").toLocalDateTime(), rs.getInt("version"),
                rs.getObject("repair_order_id", Long.class), rs.getString("repair_code")
        );
    }

    private String repairSelect() {
        return """
                SELECT repair.id, repair.repair_code, repair.fault_report_id,
                       report.report_code, repair.equipment_id, equipment.equipment_code,
                       equipment.equipment_name, repair.organization_id,
                       organization.organization_name, report.fault_title, report.severity,
                       repair.repair_status, repair.primary_repairer_user_id,
                       primary_user.real_name AS primary_repairer_name,
                       GROUP_CONCAT(DISTINCT collaborator.user_id ORDER BY collaborator.user_id) AS collaborator_ids,
                       repair.assigned_time, repair.started_time, repair.paused_time,
                       repair.completed_time, repair.accepted_time,
                       repair.total_paused_seconds, repair.effective_work_seconds,
                       repair.repair_measure, repair.repair_conclusion,
                       repair.acceptance_result, repair.acceptance_comment,
                       repair.restore_status_code, repair.created_time, repair.version
                FROM equipment_repair_order repair
                JOIN equipment_fault_report report ON report.tenant_id = repair.tenant_id AND report.id = repair.fault_report_id
                JOIN equipment ON equipment.tenant_id = repair.tenant_id AND equipment.id = repair.equipment_id AND equipment.deleted = 0
                JOIN organization ON organization.tenant_id = repair.tenant_id AND organization.id = repair.organization_id AND organization.deleted = 0
                LEFT JOIN system_user primary_user ON primary_user.tenant_id = repair.tenant_id AND primary_user.id = repair.primary_repairer_user_id
                LEFT JOIN equipment_repair_collaborator collaborator ON collaborator.tenant_id = repair.tenant_id AND collaborator.repair_order_id = repair.id
                """;
    }

    private FaultDtos.RepairRow mapRepair(ResultSet rs, int rowNumber) throws SQLException {
        return new FaultDtos.RepairRow(
                rs.getLong("id"), rs.getString("repair_code"), rs.getLong("fault_report_id"),
                rs.getString("report_code"), rs.getLong("equipment_id"),
                rs.getString("equipment_code"), rs.getString("equipment_name"),
                rs.getLong("organization_id"), rs.getString("organization_name"),
                rs.getString("fault_title"), rs.getString("severity"),
                rs.getString("repair_status"), rs.getObject("primary_repairer_user_id", Long.class),
                rs.getString("primary_repairer_name"), longList(rs.getString("collaborator_ids")),
                time(rs, "assigned_time"), time(rs, "started_time"), time(rs, "paused_time"),
                time(rs, "completed_time"), time(rs, "accepted_time"),
                rs.getLong("total_paused_seconds"), rs.getLong("effective_work_seconds"),
                rs.getString("repair_measure"), rs.getString("repair_conclusion"),
                rs.getString("acceptance_result"), rs.getString("acceptance_comment"),
                rs.getString("restore_status_code"), rs.getTimestamp("created_time").toLocalDateTime(),
                rs.getInt("version")
        );
    }

    private QueryScope reportScope(DataPermission scope, long userId, String alias) {
        if (scope.allData()) return new QueryScope("", List.of());
        List<Object> parameters = new ArrayList<>();
        List<String> options = new ArrayList<>();
        if (!scope.organizationIds().isEmpty()) {
            options.add(alias + ".organization_id IN (" + String.join(",", java.util.Collections.nCopies(scope.organizationIds().size(), "?")) + ")");
            parameters.addAll(scope.organizationIds());
        }
        if (scope.selfData()) {
            options.add(alias + ".reporter_user_id = ?");
            parameters.add(userId);
        }
        return new QueryScope(" AND (" + (options.isEmpty() ? "1 = 0" : String.join(" OR ", options)) + ")", parameters);
    }

    private QueryScope repairScope(DataPermission scope, long userId, String alias, boolean mineOnly) {
        List<Object> parameters = new ArrayList<>();
        List<String> options = new ArrayList<>();
        if (!mineOnly && scope.allData()) return new QueryScope("", List.of());
        if (!mineOnly && !scope.organizationIds().isEmpty()) {
            options.add(alias + ".organization_id IN (" + String.join(",", java.util.Collections.nCopies(scope.organizationIds().size(), "?")) + ")");
            parameters.addAll(scope.organizationIds());
        }
        options.add(alias + ".primary_repairer_user_id = ?");
        parameters.add(userId);
        options.add("EXISTS (SELECT 1 FROM equipment_repair_collaborator c WHERE c.tenant_id = " + alias + ".tenant_id AND c.repair_order_id = " + alias + ".id AND c.user_id = ?)");
        parameters.add(userId);
        return new QueryScope(" AND (" + String.join(" OR ", options) + ")", parameters);
    }

    private List<Long> longList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(Long::valueOf).toList();
    }

    private LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private Object[] prepend(long first, List<Long> values) {
        List<Object> result = new ArrayList<>();
        result.add(first);
        result.addAll(values);
        return result.toArray();
    }

    private String defaultRestore(String status) {
        if (status == null || status.isBlank()) return "IDLE";
        String normalized = status.trim().toUpperCase();
        return Set.of("IDLE", "RUNNING", "STOPPED").contains(normalized) ? normalized : "IDLE";
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException notFound(String code, String message) {
        return new BusinessException(code, message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String message) {
        return new BusinessException("FAULT_STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private BusinessException forbidden() {
        return new BusinessException("DATA_SCOPE_FORBIDDEN", "无权访问或操作该故障维修数据", HttpStatus.FORBIDDEN);
    }

    private record QueryScope(String sql, List<Object> parameters) {
    }

    private record AbnormalSource(
            long id, long equipmentId, long organizationId, String title,
            String description, String severity, LocalDateTime faultTime,
            Long repairOrderId, int version
    ) {
    }
}
