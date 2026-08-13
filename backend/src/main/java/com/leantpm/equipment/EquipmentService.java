package com.leantpm.equipment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.excel.ImportWorkbookSupport;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.common.query.TableQuery;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.foundation.service.ParameterService;
import com.leantpm.masterdata.MasterDataDtos;
import com.leantpm.masterdata.MasterDataMapper;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class EquipmentService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String DEFAULT_BARCODE_BASE_URL = "http://localhost:15173/m/e";
    private static final String BARCODE_CENTER_LOGO_KEY = "equipment.barcode.center-logo-url";
    private static final String DEFAULT_SYSTEM_NAME = "大宝山设备管理系统";
    private static final String DEFAULT_CENTER_LOGO = "DEFAULT";
    private static final Color LABEL_DEEP_BLUE = new Color(0x003D91);
    private static final Color LABEL_TEAL = new Color(0x00A99D);
    private static final List<String> IMPORT_HEADERS = List.of(
            "设备编码", "设备名称", "设备分类", "所属组织", "物理位置",
            "型号", "规格", "品牌", "制造商", "出厂编号", "生产日期", "投产日期",
            "主负责人", "资产编号", "生命周期", "关键设备", "特种设备",
            "OEE启用", "启用"
    );
    private static final Set<String> IMPORT_REQUIRED_HEADERS = Set.of(
            "设备名称", "设备分类", "所属组织"
    );
    private static final Map<String, String> IMPORT_HEADER_ALIASES = Map.of(
            "分类编码", "设备分类",
            "组织编码", "所属组织",
            "位置编码", "物理位置",
            "负责人账号", "主负责人",
            "生命周期阶段", "生命周期"
    );
    private static final Map<String, String> IMPORT_CATEGORY_CODES = Map.of(
            "生产设备", "PRODUCTION",
            "环保设备", "ENVIRONMENTAL_EQUIPMENT",
            "辅助设备", "AUXILIARY_EQUIPMENT",
            "运输设备", "TRANSPORT_EQUIPMENT",
            "其它设备", "OTHER_EQUIPMENT"
    );
    private static final Map<String, String> IMPORT_LIFECYCLE_CODES = Map.ofEntries(
            Map.entry("规划", "PLANNING"),
            Map.entry("安装", "INSTALLATION"),
            Map.entry("调试", "COMMISSIONING"),
            Map.entry("在役", "IN_SERVICE"),
            Map.entry("闲置", "IDLE"),
            Map.entry("封存", "SEALED"),
            Map.entry("报废", "SCRAPPED")
    );
    private static final Set<String> IMPORT_LIFECYCLE_VALUES = Set.of(
            "PLANNING", "INSTALLATION", "COMMISSIONING", "IN_SERVICE",
            "IDLE", "SEALED", "SCRAPPED"
    );
    private static final Pattern IMPORT_EQUIPMENT_CODE_PATTERN =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}._#-]*$");
    private static final List<DateTimeFormatter> IMPORT_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu.M.d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu年M月d日").withResolverStyle(ResolverStyle.STRICT)
    );
    /**
     * 设备运行状态只描述“能否生产”，点检、保养、故障和维修继续由各自工单记录。
     * 因此所有作业停机统一落到 STOPPED，不再把业务过程混入设备状态字典。
     */
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            "IDLE", Set.of("RUNNING", "STOPPED", "SCRAPPED"),
            "RUNNING", Set.of("IDLE", "STOPPED", "SCRAPPED"),
            "STOPPED", Set.of("IDLE", "RUNNING", "SCRAPPED"),
            "SCRAPPED", Set.of()
    );

    private final EquipmentMapper mapper;
    private final MasterDataMapper masterDataMapper;
    private final DataPermissionService dataPermissionService;
    private final NumberRuleService numberRuleService;
    private final ParameterService parameterService;
    private final ChangeLogService changeLogService;
    private final ObjectMapper objectMapper;

    public EquipmentService(
            EquipmentMapper mapper,
            MasterDataMapper masterDataMapper,
            DataPermissionService dataPermissionService,
            NumberRuleService numberRuleService,
            ParameterService parameterService,
            ChangeLogService changeLogService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.masterDataMapper = masterDataMapper;
        this.dataPermissionService = dataPermissionService;
        this.numberRuleService = numberRuleService;
        this.parameterService = parameterService;
        this.changeLogService = changeLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<EquipmentDtos.EquipmentRow> page(
            String keyword,
            Long categoryId,
            Long organizationId,
            Long locationId,
            String currentStatusCode,
            String lifecycleStage,
            Integer status,
            TableQuery tableQuery,
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int offset = (page - 1) * pageSize;
        String normalizedStatus = upper(currentStatusCode);
        String normalizedStage = upper(lifecycleStage);
        return PageResult.of(
                mapper.findEquipmentPage(
                        current.tenantId(), scope, clean(keyword), categoryId,
                        organizationId, locationId, normalizedStatus, normalizedStage,
                        status, tableQuery, offset, pageSize
                ),
                mapper.countEquipment(
                        current.tenantId(), scope, clean(keyword), categoryId,
                        organizationId, locationId, normalizedStatus, normalizedStage, status,
                        tableQuery
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Long> statusSummary(
            String keyword,
            Long organizationId,
            TableQuery tableQuery
    ) {
        var current = SecurityUtils.currentUser();
        Map<String, Long> summary = new LinkedHashMap<>();
        STATUS_TRANSITIONS.keySet().stream().sorted().forEach(code -> summary.put(code, 0L));
        mapper.summarizeEquipmentStatus(
                current.tenantId(), dataPermissionService.current(), clean(keyword),
                null, organizationId, null, null, null, null, tableQuery
        ).forEach(row -> {
            String code = upper(row.statusCode());
            if (code != null) {
                summary.merge(code, row.equipmentCount(), Long::sum);
            }
        });
        return summary;
    }

    public PageResult<EquipmentDtos.EquipmentRow> page(
            String keyword,
            Long categoryId,
            Long organizationId,
            Long locationId,
            String currentStatusCode,
            String lifecycleStage,
            Integer status,
            int page,
            int pageSize
    ) {
        return page(
                keyword, categoryId, organizationId, locationId, currentStatusCode,
                lifecycleStage, status, TableQuery.empty(), page, pageSize
        );
    }

    @Transactional(readOnly = true)
    public EquipmentDtos.EquipmentDetail detail(long id) {
        var current = SecurityUtils.currentUser();
        DataPermission readScope = dataPermissionService.current();
        EquipmentDtos.EquipmentRow equipment = requireAccessible(
                current.tenantId(), id, readScope
        );
        return detail(current.tenantId(), equipment, readScope);
    }

    @Transactional
    public long create(EquipmentDtos.SaveEquipmentRequest request) {
        var current = SecurityUtils.currentUser();
        return create(current.tenantId(), current.userId(), request, dataPermissionService.current());
    }

    private long create(
            long tenantId,
            long operatorId,
            EquipmentDtos.SaveEquipmentRequest request,
            DataPermission scope
    ) {
        EquipmentDtos.SaveEquipmentRequest normalized = normalize(request);
        validateReferences(tenantId, normalized, scope);
        List<ValidatedAttribute> attributes = validateAttributes(
                tenantId, normalized.categoryId(), normalized.attributes()
        );
        List<EquipmentDtos.SaveResponsiblePersonRequest> responsiblePersons =
                validateResponsiblePersons(tenantId, normalized);
        String code = normalized.equipmentCode();
        if (code == null) {
            code = numberRuleService.generate(tenantId, operatorId, "EQUIPMENT").businessNumber();
        }
        if (mapper.countEquipmentCode(tenantId, code, null) > 0) {
            throw new BusinessException(
                    "EQUIPMENT_CODE_EXISTS", "设备编码已存在", HttpStatus.CONFLICT
            );
        }

        mapper.insertEquipment(tenantId, code, normalized, operatorId);
        Long id = mapper.findEquipmentIdByCode(tenantId, code);
        if (id == null) {
            throw new BusinessException(
                    "EQUIPMENT_CREATE_FAILED", "设备创建失败", HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        replaceAttributes(tenantId, id, attributes, operatorId);
        replaceResponsiblePersons(tenantId, id, responsiblePersons, operatorId);
        String initialStatus = "IDLE";
        LocalDateTime now = LocalDateTime.now();
        mapper.insertInitialStatus(tenantId, id, initialStatus, now, operatorId);
        mapper.insertStatusHistory(
                tenantId, id, null, initialStatus, now, null, "SYSTEM", operatorId
        );
        EquipmentDtos.EquipmentRow created = mapper.findEquipment(tenantId, id, DataPermission.all(operatorId));
        changeLogService.record("EQUIPMENT", id, "CREATE", null, created);
        return id;
    }

    @Transactional
    public void update(long id, EquipmentDtos.SaveEquipmentRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        EquipmentDtos.EquipmentRow existing = requireAccessible(current.tenantId(), id, scope);
        EquipmentDtos.SaveEquipmentRequest normalized = normalize(request);
        if (normalized.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少数据版本");
        }
        if (normalized.equipmentCode() != null
                && !existing.equipmentCode().equals(normalized.equipmentCode())) {
            throw new BusinessException("IMMUTABLE_CODE", "设备编码创建后不可修改");
        }
        if ((existing.status() == 1) != Boolean.TRUE.equals(normalized.enabled())
                && !current.permissions().contains("equipment:ledger:status")) {
            throw new BusinessException(
                    "EQUIPMENT_STATUS_FORBIDDEN",
                    "无权启用或停用设备",
                    HttpStatus.FORBIDDEN
            );
        }
        validateReferences(current.tenantId(), normalized, scope);
        List<ValidatedAttribute> attributes = validateAttributes(
                current.tenantId(), normalized.categoryId(), normalized.attributes()
        );
        List<EquipmentDtos.SaveResponsiblePersonRequest> responsiblePersons =
                validateResponsiblePersons(current.tenantId(), normalized);
        if (mapper.updateEquipment(
                current.tenantId(), id, normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        replaceAttributes(current.tenantId(), id, attributes, current.userId());
        replaceResponsiblePersons(
                current.tenantId(), id, responsiblePersons, current.userId()
        );
        synchronizeEnableStatus(
                current.tenantId(),
                id,
                existing.status() == 1,
                Boolean.TRUE.equals(normalized.enabled()),
                current.userId()
        );
        EquipmentDtos.EquipmentRow updated = mapper.findEquipment(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        changeLogService.record("EQUIPMENT", id, "UPDATE", existing, updated);
    }

    @Transactional
    public long copy(long id, EquipmentDtos.CopyEquipmentRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        EquipmentDtos.EquipmentRow source = requireAccessible(current.tenantId(), id, scope);
        List<EquipmentDtos.AttributeValueRow> attributes = mapper.findAttributeValues(
                current.tenantId(), id, source.categoryId()
        );
        List<EquipmentDtos.ResponsiblePersonRow> people =
                mapper.findResponsiblePersons(current.tenantId(), id);
        EquipmentDtos.SaveEquipmentRequest copied = new EquipmentDtos.SaveEquipmentRequest(
                cleanUpper(request.equipmentCode()),
                request.equipmentName().trim(),
                source.categoryId(),
                source.model(),
                source.specification(),
                source.brand(),
                source.manufacturer(),
                null,
                source.productionDate(),
                null,
                source.organizationId(),
                source.locationId(),
                source.primaryResponsibleUserId(),
                null,
                "PLANNING",
                source.criticalFlag(),
                source.specialFlag(),
                source.oeeEnabled(),
                true,
                source.description(),
                attributes.stream()
                        .map(value -> new EquipmentDtos.SaveAttributeValueRequest(
                                value.definitionId(), value.value()
                        ))
                        .toList(),
                people.stream()
                        .map(person -> new EquipmentDtos.SaveResponsiblePersonRequest(
                                person.userId(), person.responsibilityType(),
                                person.startDate(), person.endDate()
                        ))
                        .toList(),
                null
        );
        long copiedId = create(current.tenantId(), current.userId(), copied, scope);
        changeLogService.record(
                "EQUIPMENT", copiedId, "COPY",
                Map.of("sourceEquipmentId", id),
                Map.of("copiedEquipmentId", copiedId)
        );
        return copiedId;
    }

    @Transactional
    public void transfer(long id, EquipmentDtos.TransferRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        EquipmentDtos.EquipmentRow existing = requireAccessible(current.tenantId(), id, scope);
        validateOrganizationLocation(
                current.tenantId(), request.organizationId(), request.locationId(), scope
        );
        validateUser(current.tenantId(), request.primaryResponsibleUserId());
        if (existing.organizationId() == request.organizationId()
                && Objects.equals(existing.locationId(), request.locationId())
                && Objects.equals(
                        existing.primaryResponsibleUserId(), request.primaryResponsibleUserId()
                )) {
            throw new BusinessException("TRANSFER_NO_CHANGE", "调拨目标与当前归属完全相同");
        }
        if (mapper.updateEquipmentTransfer(
                current.tenantId(), id, request, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.insertTransfer(current.tenantId(), existing, request, current.userId());
        syncPrimaryResponsiblePerson(
                current.tenantId(), id, request.primaryResponsibleUserId(), current.userId()
        );
        EquipmentDtos.EquipmentRow updated = mapper.findEquipment(
                current.tenantId(), id, DataPermission.all(current.userId())
        );
        changeLogService.record("EQUIPMENT", id, "TRANSFER", existing, updated);
    }

    @Transactional
    public void delete(long id, int version) {
        var current = SecurityUtils.currentUser();
        EquipmentDtos.EquipmentRow existing = requireAccessible(
                current.tenantId(), id, dataPermissionService.current()
        );
        if (mapper.countOperationalHistory(current.tenantId(), id) > 1) {
            throw new BusinessException(
                    "EQUIPMENT_HAS_BUSINESS_RECORDS",
                    "设备已有状态变更或调拨等业务记录，只能停用，不能删除",
                    HttpStatus.CONFLICT
            );
        }
        if (mapper.softDeleteEquipment(
                current.tenantId(), id, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.invalidateActiveBarcode(
                current.tenantId(), id, "设备删除自动解绑", current.userId()
        );
        changeLogService.record("EQUIPMENT", id, "DELETE", existing, null);
    }

    @Transactional
    public void changeStatus(long id, EquipmentDtos.ChangeStatusRequest request) {
        var current = SecurityUtils.currentUser();
        changeStatus(
                id, request, current, dataPermissionService.current()
        );
    }

    /**
     * 供点检、维保等已完成自身任务数据权限校验的领域服务调用。
     * 该入口不暴露为控制器接口，仍执行设备状态机、来源类型和乐观锁校验。
     */
    @Transactional
    public void changeStatusFromBusiness(
            long id,
            EquipmentDtos.ChangeStatusRequest request
    ) {
        var current = SecurityUtils.currentUser();
        String source = upper(request.sourceType());
        if (!Set.of("INSPECTION", "MAINTENANCE", "IOT", "SYSTEM").contains(source)) {
            throw new BusinessException(
                    "EQUIPMENT_STATUS_SOURCE_INVALID", "业务状态联动来源不正确"
            );
        }
        changeStatus(id, request, current, DataPermission.all(current.userId()));
    }

    private void changeStatus(
            long id,
            EquipmentDtos.ChangeStatusRequest request,
            com.leantpm.security.CurrentUser current,
            DataPermission scope
    ) {
        EquipmentDtos.EquipmentRow equipment =
                requireAccessible(current.tenantId(), id, scope);
        String next = normalizeOperatingStatus(upper(request.statusCode()));
        if (equipment.status() != 1) {
            throw new BusinessException(
                    "EQUIPMENT_DISABLED", "设备已停用，请先在台账中启用设备"
            );
        }
        if (mapper.countStatusCode(current.tenantId(), next) == 0) {
            throw new BusinessException("EQUIPMENT_STATUS_INVALID", "设备状态不存在或已停用");
        }
        EquipmentMapper.CurrentStatus currentStatus =
                mapper.findCurrentStatus(current.tenantId(), id);
        if (currentStatus == null) {
            throw new BusinessException(
                    "CURRENT_STATUS_NOT_FOUND", "设备当前状态不存在", HttpStatus.NOT_FOUND
            );
        }
        if (currentStatus.statusCode().equals(next)) {
            throw new BusinessException("STATUS_NO_CHANGE", "目标状态与当前状态相同");
        }
        if (!STATUS_TRANSITIONS.getOrDefault(currentStatus.statusCode(), Set.of()).contains(next)) {
            throw new BusinessException(
                    "STATUS_TRANSITION_INVALID",
                    "不允许从 " + currentStatus.statusCode() + " 切换到 " + next,
                    HttpStatus.CONFLICT
            );
        }
        String sourceType = upper(request.sourceType());
        if (sourceType == null) {
            sourceType = "MANUAL";
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateCurrentStatus(
                current.tenantId(), id, next, now, clean(request.reason()), sourceType,
                request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.closeOpenStatusHistory(current.tenantId(), id, now);
        mapper.insertStatusHistory(
                current.tenantId(), id, currentStatus.statusCode(), next, now,
                clean(request.reason()), sourceType, current.userId()
        );
        changeLogService.record(
                "EQUIPMENT_STATUS", id, "UPDATE",
                currentStatus,
                mapper.findCurrentStatus(current.tenantId(), id)
        );
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.StatusHistoryRow> statusHistory(long id) {
        var current = SecurityUtils.currentUser();
        requireAccessible(current.tenantId(), id, dataPermissionService.current());
        return mapper.findStatusHistory(current.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.BarcodeRow> barcodes(Long equipmentId, boolean activeOnly) {
        return barcodes(equipmentId, null, activeOnly);
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.BarcodeRow> barcodes(
            Long equipmentId,
            Long organizationId,
            boolean activeOnly
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        if (equipmentId != null) {
            EquipmentDtos.EquipmentRow equipment = requireAccessible(
                    current.tenantId(), equipmentId, scope
            );
            if (organizationId != null && equipment.organizationId() != organizationId) {
                return List.of();
            }
        }
        return mapper.findBarcodes(
                current.tenantId(), scope, equipmentId, organizationId, activeOnly
        );
    }

    @Transactional
    public EquipmentDtos.BarcodeRow generateBarcode(
            long equipmentId,
            EquipmentDtos.GenerateBarcodeRequest request,
            boolean regenerate
    ) {
        var current = SecurityUtils.currentUser();
        EquipmentDtos.EquipmentRow equipment = requireAccessible(
                current.tenantId(), equipmentId, dataPermissionService.current()
        );
        EquipmentDtos.BarcodeRow active =
                mapper.findActiveBarcode(current.tenantId(), equipmentId);
        if (active != null && !regenerate) {
            throw new BusinessException(
                    "ACTIVE_BARCODE_EXISTS", "设备已有有效条码，请使用重新生成",
                    HttpStatus.CONFLICT
            );
        }
        if (active != null) {
            mapper.invalidateActiveBarcode(
                    current.tenantId(), equipmentId,
                    clean(request.reason()) == null ? "重新生成" : clean(request.reason()),
                    current.userId()
            );
        }
        String token = randomToken();
        String barcodeType = upper(request.barcodeType());
        if (barcodeType == null) {
            barcodeType = "QR";
        }
        mapper.insertBarcode(
                current.tenantId(), equipmentId, token, barcodeType, current.userId()
        );
        EquipmentDtos.BarcodeRow created =
                mapper.findActiveBarcode(current.tenantId(), equipmentId);
        changeLogService.record(
                "EQUIPMENT_BARCODE", created.id(),
                regenerate ? "REGENERATE" : "CREATE",
                active,
                Map.of(
                        "barcodeId", created.id(),
                        "equipmentId", equipment.id(),
                        "barcodeType", created.barcodeType()
                )
        );
        return created;
    }

    @Transactional
    public EquipmentDtos.BulkBarcodeResult generateMissingBarcodes(
            EquipmentDtos.GenerateBarcodeRequest request
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        List<EquipmentDtos.BarcodeRow> existing = mapper.findBarcodes(
                current.tenantId(), scope, null, null, true
        );
        List<Long> missingIds = mapper.findActiveEquipmentIdsWithoutBarcode(
                current.tenantId(), scope
        );
        String barcodeType = upper(request.barcodeType());
        if (barcodeType == null) barcodeType = "QR";
        for (long equipmentId : missingIds) {
            mapper.insertBarcode(
                    current.tenantId(), equipmentId, randomToken(), barcodeType,
                    current.userId()
            );
        }
        changeLogService.record(
                "EQUIPMENT_BARCODE", 0, "BULK_CREATE", null,
                Map.of("generatedCount", missingIds.size(), "barcodeType", barcodeType)
        );
        return new EquipmentDtos.BulkBarcodeResult(
                existing.size() + missingIds.size(), missingIds.size(), existing.size()
        );
    }

    @Transactional
    public void unbindBarcode(long equipmentId, String reason) {
        var current = SecurityUtils.currentUser();
        requireAccessible(current.tenantId(), equipmentId, dataPermissionService.current());
        EquipmentDtos.BarcodeRow active =
                mapper.findActiveBarcode(current.tenantId(), equipmentId);
        if (active == null) {
            throw new BusinessException(
                    "ACTIVE_BARCODE_NOT_FOUND", "设备没有有效条码", HttpStatus.NOT_FOUND
            );
        }
        mapper.invalidateActiveBarcode(
                current.tenantId(), equipmentId,
                clean(reason) == null ? "手动解绑" : clean(reason),
                current.userId()
        );
        changeLogService.record("EQUIPMENT_BARCODE", active.id(), "UNBIND", active, null);
    }

    @Transactional(readOnly = true)
    public byte[] barcodeImage(long barcodeId, int width, int height) {
        var current = SecurityUtils.currentUser();
        EquipmentDtos.BarcodeRow barcode = mapper.findBarcode(current.tenantId(), barcodeId);
        if (barcode == null || !Boolean.TRUE.equals(barcode.active())) {
            throw new BusinessException(
                    "BARCODE_NOT_FOUND", "有效条码不存在", HttpStatus.NOT_FOUND
            );
        }
        requireAccessible(
                current.tenantId(), barcode.equipmentId(), dataPermissionService.current()
        );
        String baseUrl = parameterService.getString(
                current.tenantId(),
                "equipment.barcode.public-base-url",
                DEFAULT_BARCODE_BASE_URL
        );
        String content = stripTrailingSlash(baseUrl) + "/" + barcode.accessToken();
        BarcodeFormat format = "CODE128".equals(barcode.barcodeType())
                ? BarcodeFormat.CODE_128
                : BarcodeFormat.QR_CODE;
        try {
            BufferedImage rendered;
            if (format == BarcodeFormat.QR_CODE) {
                String logoSetting = parameterService.getString(
                        current.tenantId(), BARCODE_CENTER_LOGO_KEY, DEFAULT_CENTER_LOGO
                );
                BufferedImage qrCode = renderStyledQr(
                        content,
                        width,
                        configuredCenterLogo(logoSetting)
                );
                rendered = withPremiumEquipmentLabel(
                        qrCode,
                        DEFAULT_SYSTEM_NAME,
                        barcode.equipmentName(),
                        barcode.equipmentCode()
                );
            } else {
                BitMatrix matrix = new MultiFormatWriter().encode(
                        content,
                        format,
                        width,
                        height,
                        Map.of(EncodeHintType.MARGIN, 1)
                );
                rendered = new BufferedImage(
                        matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB
                );
                for (int x = 0; x < matrix.getWidth(); x++) {
                    for (int y = 0; y < matrix.getHeight(); y++) {
                        rendered.setRGB(
                                x, y, matrix.get(x, y) ? 0xFF111827 : 0xFFFFFFFF
                        );
                    }
                }
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ImageIO.write(rendered, "png", output);
                return output.toByteArray();
            }
        } catch (WriterException | IOException exception) {
            throw new BusinessException(
                    "BARCODE_RENDER_FAILED", "条码图片生成失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    static BufferedImage renderStyledQr(
            String content,
            int size,
            BufferedImage centerLogo
    ) throws WriterException {
        var encoded = Encoder.encode(
                content,
                ErrorCorrectionLevel.H,
                Map.of(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name())
        );
        ByteMatrix modules = encoded.getMatrix();
        int moduleCount = modules.getWidth();
        int quietModules = 4;
        int moduleSize = Math.max(1, size / (moduleCount + quietModules * 2));
        int renderedSize = moduleSize * (moduleCount + quietModules * 2);
        int offset = Math.max(0, (size - renderedSize) / 2);
        int gridStart = offset + quietModules * moduleSize;

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            enableQualityRendering(graphics);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(LABEL_DEEP_BLUE);

            int dotInset = Math.max(0, moduleSize / 6);
            int dotSize = Math.max(1, moduleSize - dotInset * 2);
            for (int y = 0; y < moduleCount; y++) {
                for (int x = 0; x < moduleCount; x++) {
                    if (modules.get(x, y) != 1 || isFinderModule(x, y, moduleCount)) {
                        continue;
                    }
                    graphics.fillOval(
                            gridStart + x * moduleSize + dotInset,
                            gridStart + y * moduleSize + dotInset,
                            dotSize,
                            dotSize
                    );
                }
            }

            drawFinderPattern(graphics, gridStart, gridStart, moduleSize);
            drawFinderPattern(
                    graphics,
                    gridStart + (moduleCount - 7) * moduleSize,
                    gridStart,
                    moduleSize
            );
            drawFinderPattern(
                    graphics,
                    gridStart,
                    gridStart + (moduleCount - 7) * moduleSize,
                    moduleSize
            );

            int plateSize = moduleSize * 7;
            int plateX = (size - plateSize) / 2;
            int plateY = (size - plateSize) / 2;
            int arc = Math.max(8, moduleSize * 2);
            graphics.setColor(Color.WHITE);
            graphics.fillRoundRect(plateX, plateY, plateSize, plateSize, arc, arc);
            drawContainedImage(
                    graphics,
                    centerLogo == null ? defaultCenterLogo() : centerLogo,
                    plateX + moduleSize,
                    plateY + moduleSize,
                    plateSize - moduleSize * 2,
                    plateSize - moduleSize * 2
            );
        } finally {
            graphics.dispose();
        }
        return image;
    }

    static BufferedImage withPremiumEquipmentLabel(
            BufferedImage qrCode,
            String systemName,
            String equipmentName,
            String equipmentCode
    ) {
        int width = qrCode.getWidth();
        int height = width * 4 / 3;
        double scale = width / 600.0;
        BufferedImage label = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = label.createGraphics();
        try {
            enableQualityRendering(graphics);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);

            Path2D.Double blueBody = new Path2D.Double();
            blueBody.moveTo(0, scaled(118, scale));
            blueBody.curveTo(
                    scaled(135, scale), scaled(210, scale),
                    scaled(330, scale), scaled(225, scale),
                    width, scaled(145, scale)
            );
            blueBody.lineTo(width, height);
            blueBody.lineTo(0, height);
            blueBody.closePath();
            graphics.setPaint(new GradientPaint(
                    0, scaled(180, scale), new Color(0x0877E3),
                    width, height, new Color(0x00327F)
            ));
            graphics.fill(blueBody);
            drawWaveAccents(graphics, width, scale);
            drawCircuitAccents(graphics, width, height, scale);

            drawCenteredText(
                    graphics,
                    cleanLabel(systemName, DEFAULT_SYSTEM_NAME),
                    width / 2,
                    scaled(88, scale),
                    scaled(510, scale),
                    new Font("Microsoft YaHei", Font.BOLD, titleFontSize(scale)),
                    LABEL_DEEP_BLUE
            );

            int cardX = scaled(91, scale);
            int cardY = scaled(174, scale);
            int cardSize = scaled(418, scale);
            int cardArc = scaled(34, scale);
            graphics.setColor(new Color(255, 255, 255, 245));
            graphics.fillRoundRect(cardX, cardY, cardSize, cardSize, cardArc, cardArc);
            graphics.setColor(new Color(0x55B8FF));
            graphics.setStroke(new BasicStroke(Math.max(1f, scaled(2, scale))));
            graphics.drawRoundRect(cardX, cardY, cardSize, cardSize, cardArc, cardArc);
            int qrPadding = scaled(25, scale);
            graphics.drawImage(
                    qrCode,
                    cardX + qrPadding,
                    cardY + qrPadding,
                    cardSize - qrPadding * 2,
                    cardSize - qrPadding * 2,
                    null
            );

            drawInfoPanel(
                    graphics,
                    scaled(73, scale), scaled(606, scale),
                    scaled(454, scale), scaled(48, scale),
                    equipmentNameText(equipmentName),
                    false,
                    scale
            );
            drawInfoPanel(
                    graphics,
                    scaled(73, scale), scaled(662, scale),
                    scaled(454, scale), scaled(48, scale),
                    equipmentCodeText(equipmentCode),
                    true,
                    scale
            );
            drawCallToAction(graphics, scale);
        } finally {
            graphics.dispose();
        }
        return label;
    }

    private static BufferedImage configuredCenterLogo(String setting) {
        if (setting == null || setting.isBlank() || DEFAULT_CENTER_LOGO.equals(setting)) {
            return defaultCenterLogo();
        }
        try {
            int comma = setting.indexOf(',');
            if (comma < 0) {
                return defaultCenterLogo();
            }
            String header = setting.substring(0, comma).toLowerCase(Locale.ROOT);
            if (!"data:image/png;base64".equals(header)
                    && !"data:image/jpeg;base64".equals(header)) {
                return defaultCenterLogo();
            }
            byte[] bytes = Base64.getDecoder().decode(setting.substring(comma + 1));
            if (bytes.length > 512 * 1024) {
                return defaultCenterLogo();
            }
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    return defaultCenterLogo();
                }
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 2048 || height > 2048
                            || (long) width * height > 4_000_000L) {
                        return defaultCenterLogo();
                    }
                    BufferedImage decoded = reader.read(0);
                    return decoded == null ? defaultCenterLogo() : decoded;
                } finally {
                    reader.dispose();
                }
            }
        } catch (IllegalArgumentException | IOException exception) {
            return defaultCenterLogo();
        }
    }

    private static BufferedImage defaultCenterLogo() {
        int size = 180;
        BufferedImage logo = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = logo.createGraphics();
        try {
            enableQualityRendering(graphics);
            Path2D.Double shield = new Path2D.Double();
            shield.moveTo(size * 0.5, size * 0.06);
            shield.lineTo(size * 0.86, size * 0.22);
            shield.lineTo(size * 0.82, size * 0.68);
            shield.curveTo(
                    size * 0.78, size * 0.82,
                    size * 0.63, size * 0.92,
                    size * 0.5, size * 0.98
            );
            shield.curveTo(
                    size * 0.37, size * 0.92,
                    size * 0.22, size * 0.82,
                    size * 0.18, size * 0.68
            );
            shield.lineTo(size * 0.14, size * 0.22);
            shield.closePath();
            graphics.setPaint(new GradientPaint(
                    0, 0, new Color(0x12BFA6), size, size, new Color(0x007D74)
            ));
            graphics.fill(shield);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke(8f));
            graphics.draw(shield);
            graphics.setFont(new Font("Arial", Font.BOLD, 68));
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(
                    "LT",
                    (size - metrics.stringWidth("LT")) / 2,
                    size / 2 + metrics.getAscent() / 2 - 4
            );
        } finally {
            graphics.dispose();
        }
        return logo;
    }

    private static void drawFinderPattern(
            Graphics2D graphics,
            int x,
            int y,
            int moduleSize
    ) {
        int outer = moduleSize * 7;
        int arc = Math.max(4, moduleSize * 2);
        graphics.setColor(LABEL_DEEP_BLUE);
        graphics.fillRoundRect(x, y, outer, outer, arc, arc);
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(
                x + moduleSize,
                y + moduleSize,
                moduleSize * 5,
                moduleSize * 5,
                arc,
                arc
        );
        graphics.setColor(LABEL_DEEP_BLUE);
        graphics.fillRoundRect(
                x + moduleSize * 2,
                y + moduleSize * 2,
                moduleSize * 3,
                moduleSize * 3,
                Math.max(3, moduleSize),
                Math.max(3, moduleSize)
        );
    }

    private static boolean isFinderModule(int x, int y, int moduleCount) {
        return (x < 7 && y < 7)
                || (x >= moduleCount - 7 && y < 7)
                || (x < 7 && y >= moduleCount - 7);
    }

    private static void drawContainedImage(
            Graphics2D graphics,
            BufferedImage image,
            int x,
            int y,
            int width,
            int height
    ) {
        double ratio = Math.min(
                (double) width / image.getWidth(),
                (double) height / image.getHeight()
        );
        int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * ratio));
        graphics.drawImage(
                image,
                x + (width - drawWidth) / 2,
                y + (height - drawHeight) / 2,
                drawWidth,
                drawHeight,
                null
        );
    }

    private static void drawWaveAccents(Graphics2D graphics, int width, double scale) {
        graphics.setStroke(new BasicStroke(Math.max(1f, scaled(2, scale))));
        for (int index = 0; index < 4; index++) {
            int shift = scaled(index * 8, scale);
            Path2D.Double line = new Path2D.Double();
            line.moveTo(0, scaled(108, scale) + shift);
            line.curveTo(
                    scaled(160, scale), scaled(215, scale) + shift,
                    scaled(370, scale), scaled(185, scale) + shift,
                    width, scaled(128, scale) + shift
            );
            graphics.setColor(new Color(120, 194, 255, 135 - index * 20));
            graphics.draw(line);
        }
    }

    private static void drawCircuitAccents(
            Graphics2D graphics,
            int width,
            int height,
            double scale
    ) {
        graphics.setColor(new Color(73, 166, 255, 80));
        graphics.setStroke(new BasicStroke(Math.max(1f, scaled(1, scale))));
        int baseY = height - scaled(18, scale);
        for (int side : new int[]{1, -1}) {
            for (int index = 0; index < 5; index++) {
                int distance = scaled(14 + index * 24, scale);
                int x = side > 0 ? distance : width - distance;
                int rise = scaled(34 + index * 8, scale);
                graphics.drawLine(x, baseY, x, baseY - rise);
                graphics.drawLine(
                        x, baseY - rise,
                        x + side * scaled(20, scale), baseY - rise - scaled(20, scale)
                );
                graphics.fillOval(
                        x - scaled(3, scale), baseY - scaled(4, scale),
                        scaled(7, scale), scaled(7, scale)
                );
            }
        }
    }

    private static void drawInfoPanel(
            Graphics2D graphics,
            int x,
            int y,
            int width,
            int height,
            String text,
            boolean gearIcon,
            double scale
    ) {
        int arc = scaled(18, scale);
        graphics.setColor(new Color(0, 63, 151, 190));
        graphics.fillRoundRect(x, y, width, height, arc, arc);
        graphics.setColor(new Color(255, 255, 255, 215));
        graphics.setStroke(new BasicStroke(Math.max(1f, scaled(2, scale))));
        graphics.drawRoundRect(x, y, width, height, arc, arc);
        int dividerX = x + scaled(78, scale);
        graphics.drawLine(dividerX, y + scaled(5, scale), dividerX, y + height - scaled(5, scale));
        drawPanelIcon(
                graphics,
                x + scaled(39, scale),
                y + height / 2,
                scaled(25, scale),
                gearIcon
        );
        drawCenteredText(
                graphics,
                text,
                dividerX + (x + width - dividerX) / 2,
                y + (height + infoFontSize(scale)) / 2 - scaled(4, scale),
                width - scaled(100, scale),
                new Font("Microsoft YaHei", Font.BOLD, infoFontSize(scale)),
                Color.WHITE
        );
    }

    private static void drawPanelIcon(
            Graphics2D graphics,
            int centerX,
            int centerY,
            int size,
            boolean gearIcon
    ) {
        graphics.setColor(Color.WHITE);
        graphics.setStroke(new BasicStroke(Math.max(1.5f, size / 8f)));
        if (gearIcon) {
            int outerRadius = size / 2;
            int innerRadius = Math.max(2, size / 5);
            graphics.drawOval(
                    centerX - outerRadius,
                    centerY - outerRadius,
                    outerRadius * 2,
                    outerRadius * 2
            );
            graphics.drawOval(
                    centerX - innerRadius,
                    centerY - innerRadius,
                    innerRadius * 2,
                    innerRadius * 2
            );
            for (int index = 0; index < 8; index++) {
                double angle = Math.PI * index / 4.0;
                graphics.drawLine(
                        centerX + (int) Math.round(Math.cos(angle) * outerRadius),
                        centerY + (int) Math.round(Math.sin(angle) * outerRadius),
                        centerX + (int) Math.round(Math.cos(angle) * (outerRadius + size / 5.0)),
                        centerY + (int) Math.round(Math.sin(angle) * (outerRadius + size / 5.0))
                );
            }
            return;
        }

        int half = size / 2;
        graphics.drawRoundRect(
                centerX - half,
                centerY - half / 2,
                size,
                half,
                Math.max(2, size / 5),
                Math.max(2, size / 5)
        );
        graphics.drawLine(centerX - half, centerY + half / 2, centerX - half, centerY + half);
        graphics.drawLine(centerX + half, centerY + half / 2, centerX + half, centerY + half);
        graphics.drawLine(centerX - half - size / 6, centerY + half,
                centerX + half + size / 6, centerY + half);
        graphics.drawLine(centerX, centerY - half / 2, centerX, centerY - half);
        graphics.drawLine(centerX, centerY - half, centerX + size / 3, centerY - half);
    }

    private static void drawCallToAction(Graphics2D graphics, double scale) {
        int x = scaled(73, scale);
        int y = scaled(718, scale);
        int width = scaled(454, scale);
        int height = scaled(66, scale);
        int arc = scaled(22, scale);
        graphics.setColor(Color.WHITE);
        graphics.fillRoundRect(x, y, width, height, arc, arc);
        int iconPanelWidth = scaled(82, scale);
        graphics.setColor(LABEL_TEAL);
        graphics.fillRoundRect(x, y, iconPanelWidth, height, arc, arc);
        graphics.fillRect(x + scaled(42, scale), y, scaled(50, scale), height);
        graphics.setColor(Color.WHITE);
        graphics.setStroke(new BasicStroke(Math.max(1.5f, scaled(4, scale))));
        int iconX = x + scaled(29, scale);
        int iconY = y + scaled(20, scale);
        int iconSize = scaled(25, scale);
        int corner = scaled(8, scale);
        graphics.drawLine(iconX, iconY + corner, iconX, iconY);
        graphics.drawLine(iconX, iconY, iconX + corner, iconY);
        graphics.drawLine(iconX + iconSize - corner, iconY, iconX + iconSize, iconY);
        graphics.drawLine(iconX + iconSize, iconY, iconX + iconSize, iconY + corner);
        graphics.drawLine(iconX, iconY + iconSize - corner, iconX, iconY + iconSize);
        graphics.drawLine(iconX, iconY + iconSize, iconX + corner, iconY + iconSize);
        graphics.drawLine(iconX + iconSize - corner, iconY + iconSize,
                iconX + iconSize, iconY + iconSize);
        graphics.drawLine(iconX + iconSize, iconY + iconSize,
                iconX + iconSize, iconY + iconSize - corner);
        drawCenteredText(
                graphics,
                "扫码查看设备档案",
                x + iconPanelWidth + (width - iconPanelWidth) / 2,
                y + scaled(46, scale),
                width - iconPanelWidth - scaled(20, scale),
                new Font("Microsoft YaHei", Font.BOLD, actionFontSize(scale)),
                LABEL_DEEP_BLUE
        );
    }

    static String equipmentNameText(String equipmentName) {
        return "设备名称：" + cleanLabel(equipmentName, "未命名设备");
    }

    static String equipmentCodeText(String equipmentCode) {
        return "设备编码：" + cleanLabel(equipmentCode, "—");
    }

    static int titleFontSize(double scale) {
        return scaled(48, scale);
    }

    static int infoFontSize(double scale) {
        return scaled(30, scale);
    }

    static int actionFontSize(double scale) {
        return scaled(34, scale);
    }

    private static void drawCenteredText(
            Graphics2D graphics,
            String text,
            int centerX,
            int baseline,
            int maxWidth,
            Font font,
            Color color
    ) {
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        while (metrics.stringWidth(text) > maxWidth && font.getSize() > 11) {
            font = font.deriveFont((float) font.getSize() - 1);
            graphics.setFont(font);
            metrics = graphics.getFontMetrics();
        }
        graphics.setColor(color);
        graphics.drawString(text, centerX - metrics.stringWidth(text) / 2, baseline);
    }

    private static String fitText(Graphics2D graphics, String text, int maxWidth) {
        String fitted = text;
        FontMetrics metrics = graphics.getFontMetrics();
        while (fitted.length() > 1 && metrics.stringWidth(fitted) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted.equals(text) ? fitted : fitted + "…";
    }

    private static String cleanLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int scaled(int value, double scale) {
        return Math.max(1, (int) Math.round(value * scale));
    }

    private static void enableQualityRendering(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );
    }

    @Transactional(readOnly = true)
    public byte[] barcodeArchive(List<Long> barcodeIds, int width, int height) {
        var current = SecurityUtils.currentUser();
        List<EquipmentDtos.BarcodeRow> rows = mapper.findBarcodes(
                current.tenantId(), dataPermissionService.current(), null, null, true
        ).stream().filter(row -> barcodeIds == null || barcodeIds.isEmpty()
                        || barcodeIds.contains(row.id()))
                .toList();
        if (rows.isEmpty()) {
            throw new BusinessException("BARCODE_ARCHIVE_EMPTY", "没有可下载的有效二维码");
        }
        if (rows.size() > 1000) {
            throw new BusinessException("BARCODE_ARCHIVE_TOO_LARGE", "一次最多下载 1000 个二维码");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (EquipmentDtos.BarcodeRow row : rows) {
                if (!"QR".equals(row.barcodeType())) continue;
                String filename = (row.equipmentCode() + "-" + row.equipmentName())
                        .replaceAll("[\\\\/:*?\"<>|]", "_") + ".png";
                zip.putNextEntry(new ZipEntry(filename));
                zip.write(barcodeImage(row.id(), width, height));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException("BARCODE_ARCHIVE_FAILED", "二维码压缩包生成失败");
        }
    }

    @Transactional(readOnly = true)
    public EquipmentDtos.PublicEquipmentView publicView(String accessToken) {
        EquipmentDtos.PublicEquipmentView view =
                mapper.findPublicEquipment(accessToken.toLowerCase(Locale.ROOT));
        if (view == null) {
            throw new BusinessException(
                    "BARCODE_NOT_FOUND", "二维码无效或已解绑", HttpStatus.NOT_FOUND
            );
        }
        return view;
    }

    @Transactional(readOnly = true)
    public byte[] exportWorkbook(
            String keyword,
            Long categoryId,
            Long organizationId,
            Long locationId,
            String currentStatusCode,
            String lifecycleStage,
            Integer status
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        List<EquipmentDtos.EquipmentRow> rows = mapper.findEquipmentPage(
                current.tenantId(), scope, clean(keyword), categoryId, organizationId,
                locationId, upper(currentStatusCode), upper(lifecycleStage), status,
                TableQuery.empty(), 0, 100_000
        );
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("设备台账");
            writeHeader(sheet, IMPORT_HEADERS);
            int index = 1;
            for (EquipmentDtos.EquipmentRow equipment : rows) {
                Row row = sheet.createRow(index++);
                List<String> values = List.of(
                        safe(equipment.equipmentCode()),
                        safe(equipment.equipmentName()),
                        safe(equipment.categoryCode()),
                        safe(equipment.organizationCode()),
                        safe(equipment.locationCode()),
                        safe(equipment.model()),
                        safe(equipment.specification()),
                        safe(equipment.brand()),
                        safe(equipment.manufacturer()),
                        safe(equipment.factorySerialNumber()),
                        equipment.productionDate() == null ? "" : equipment.productionDate().toString(),
                        equipment.commissioningDate() == null
                                ? "" : equipment.commissioningDate().toString(),
                        safe(equipment.primaryResponsibleUsername()),
                        safe(equipment.assetNumber()),
                        safe(equipment.lifecycleStage()),
                        yesNo(equipment.criticalFlag()),
                        yesNo(equipment.specialFlag()),
                        yesNo(equipment.oeeEnabled()),
                        equipment.status() == 1 ? "是" : "否"
                );
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
            }
            autoSize(sheet, IMPORT_HEADERS.size());
            return workbookBytes(workbook);
        } catch (IOException exception) {
            throw new BusinessException(
                    "EQUIPMENT_EXPORT_FAILED", "设备台账导出失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    @Transactional(readOnly = true)
    public byte[] importTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("设备导入模板");
            writeHeader(sheet, ImportWorkbookSupport.displayHeaders(
                    IMPORT_HEADERS, IMPORT_REQUIRED_HEADERS
            ));
            Row example = sheet.createRow(1);
            List<String> values = List.of(
                    "", "示例设备", "生产设备", "请填写所属组织编码", "", "M-100",
                    "示例规格", "示例品牌", "示例制造商", "SN-001",
                    "2026-01-01", "2026-02-01", "", "ASSET-001",
                    "在役", "否", "否", "是", "是"
            );
            for (int column = 0; column < values.size(); column++) {
                example.createCell(column).setCellValue(values.get(column));
            }
            autoSize(sheet, IMPORT_HEADERS.size());
            writeImportGuide(workbook);
            writeEquipmentCategoryReference(workbook);
            return workbookBytes(workbook);
        } catch (IOException exception) {
            throw new BusinessException(
                    "EQUIPMENT_TEMPLATE_FAILED", "设备导入模板生成失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    @Transactional
    public EquipmentDtos.ImportResult importWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("IMPORT_FILE_EMPTY", "请选择要导入的 Excel 文件");
        }
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        int maxRows = importMaxRows(current.tenantId());
        List<EquipmentDtos.ImportError> errors = new ArrayList<>();
        List<ImportCandidate> candidates = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new BusinessException("IMPORT_FILE_INVALID", "Excel 文件没有数据");
            }
            Map<String, Integer> columns = headerColumns(sheet.getRow(0));
            for (String required : IMPORT_REQUIRED_HEADERS) {
                if (!columns.containsKey(required)) {
                    throw new BusinessException(
                            "IMPORT_HEADER_INVALID", "缺少必填列：" + required
                    );
                }
            }
            int physicalDataRows = 0;
            DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
                Row row = sheet.getRow(rowNumber);
                if (row != null && !rowIsBlank(row, formatter)) {
                    physicalDataRows++;
                }
            }
            if (physicalDataRows > maxRows) {
                throw new BusinessException(
                        "IMPORT_ROW_LIMIT", "单次最多导入 " + maxRows + " 行设备"
                );
            }
            for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
                Row row = sheet.getRow(rowNumber);
                if (row == null || rowIsBlank(row, formatter)) {
                    continue;
                }
                candidates.add(validateImportRow(
                        current.tenantId(), row, columns, formatter, scope, errors
                ));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("IMPORT_FILE_INVALID", "无法读取 Excel 文件");
        }
        validateImportCodes(current.tenantId(), candidates, errors);
        errors.sort(java.util.Comparator.comparingInt(EquipmentDtos.ImportError::rowNumber));
        int importedRows = 0;
        if (errors.isEmpty()) {
            for (ImportCandidate candidate : candidates) {
                create(current.tenantId(), current.userId(), candidate.request(), scope);
                importedRows++;
            }
            changeLogService.record(
                    "EQUIPMENT_IMPORT", LocalDateTime.now(), "IMPORT", null,
                    Map.of(
                            "fileName", safe(file.getOriginalFilename()),
                            "totalRows", candidates.size(),
                            "importedRows", importedRows,
                            "errorRows", 0
                    )
            );
        }
        int totalRows = candidates.size();
        return new EquipmentDtos.ImportResult(totalRows, importedRows, errors);
    }

    private void validateImportCodes(
            long tenantId,
            List<ImportCandidate> candidates,
            List<EquipmentDtos.ImportError> errors
    ) {
        Map<String, List<ImportCandidate>> byCode = new LinkedHashMap<>();
        for (ImportCandidate candidate : candidates) {
            String code = candidate.request().equipmentCode();
            if (code == null || code.length() > 64
                    || !IMPORT_EQUIPMENT_CODE_PATTERN.matcher(code).matches()) {
                continue;
            }
            byCode.computeIfAbsent(code, ignored -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<String, List<ImportCandidate>> entry : byCode.entrySet()) {
            if (entry.getValue().size() > 1) {
                for (ImportCandidate candidate : entry.getValue()) {
                    errors.add(new EquipmentDtos.ImportError(
                            candidate.rowNumber(), "设备编码", entry.getKey(),
                            "设备编码在当前 Excel 中重复"
                    ));
                }
                continue;
            }
            if (mapper.countEquipmentCode(tenantId, entry.getKey(), null) > 0) {
                ImportCandidate candidate = entry.getValue().getFirst();
                errors.add(new EquipmentDtos.ImportError(
                        candidate.rowNumber(), "设备编码", entry.getKey(), "设备编码已存在"
                ));
            }
        }
    }

    private EquipmentDtos.EquipmentDetail detail(
            long tenantId,
            EquipmentDtos.EquipmentRow equipment,
            DataPermission scope
    ) {
        return new EquipmentDtos.EquipmentDetail(
                equipment,
                mapper.findAttributeValues(tenantId, equipment.id(), equipment.categoryId()),
                mapper.findResponsiblePersons(tenantId, equipment.id()),
                mapper.findBarcodes(tenantId, scope, equipment.id(), null, false),
                mapper.findStatusHistory(tenantId, equipment.id()),
                mapper.findTransfers(tenantId, equipment.id()),
                mapper.findDocuments(tenantId, equipment.id()),
                changeLogService.listResource("EQUIPMENT", equipment.id(), 100)
        );
    }

    private void validateReferences(
            long tenantId,
            EquipmentDtos.SaveEquipmentRequest request,
            DataPermission scope
    ) {
        MasterDataDtos.EquipmentCategoryRow category =
                masterDataMapper.findCategory(tenantId, request.categoryId());
        if (category == null || category.status() != 1) {
            throw new BusinessException("EQUIPMENT_CATEGORY_INVALID", "设备分类不存在或已停用");
        }
        validateOrganizationLocation(
                tenantId, request.organizationId(), request.locationId(), scope
        );
        validateUser(tenantId, request.primaryResponsibleUserId());
        if (request.productionDate() != null
                && request.commissioningDate() != null
                && request.productionDate().isAfter(request.commissioningDate())) {
            throw new BusinessException("EQUIPMENT_DATE_INVALID", "生产日期不能晚于投产日期");
        }
    }

    private void validateOrganizationLocation(
            long tenantId,
            long organizationId,
            Long locationId,
            DataPermission scope
    ) {
        MasterDataDtos.OrganizationRow organization =
                masterDataMapper.findOrganization(tenantId, organizationId);
        if (organization == null || organization.status() != 1) {
            throw new BusinessException("ORGANIZATION_INVALID", "所属组织不存在或已停用");
        }
        if (!scope.canCreateIn(organizationId)) {
            throw dataScopeDenied();
        }
        if (locationId == null) {
            return;
        }
        MasterDataDtos.LocationRow location = masterDataMapper.findLocation(tenantId, locationId);
        if (location == null || location.status() != 1) {
            throw new BusinessException("LOCATION_INVALID", "物理位置不存在或已停用");
        }
        if (location.organizationId() != organizationId) {
            throw new BusinessException(
                    "LOCATION_ORGANIZATION_MISMATCH", "物理位置不属于所选组织"
            );
        }
    }

    private List<ValidatedAttribute> validateAttributes(
            long tenantId,
            long categoryId,
            List<EquipmentDtos.SaveAttributeValueRequest> requests
    ) {
        LinkedHashMap<String, MasterDataDtos.AttributeDefinitionRow> closestByCode =
                new LinkedHashMap<>();
        for (MasterDataDtos.AttributeDefinitionRow definition
                : masterDataMapper.findCategoryAttributes(tenantId, categoryId, true)) {
            if (definition.status() == 1) {
                closestByCode.putIfAbsent(definition.attributeCode(), definition);
            }
        }
        Map<Long, MasterDataDtos.AttributeDefinitionRow> byId = new LinkedHashMap<>();
        closestByCode.values().forEach(definition -> byId.put(definition.id(), definition));
        Map<Long, String> supplied = new LinkedHashMap<>();
        if (requests != null) {
            for (EquipmentDtos.SaveAttributeValueRequest request : requests) {
                if (supplied.put(request.definitionId(), clean(request.value())) != null) {
                    throw new BusinessException(
                            "ATTRIBUTE_VALUE_DUPLICATE", "同一扩展属性不能重复提交"
                    );
                }
                if (!byId.containsKey(request.definitionId())) {
                    throw new BusinessException(
                            "ATTRIBUTE_NOT_APPLICABLE", "提交了不属于当前设备分类的属性"
                    );
                }
            }
        }
        List<ValidatedAttribute> result = new ArrayList<>();
        for (MasterDataDtos.AttributeDefinitionRow definition : byId.values()) {
            String value = supplied.containsKey(definition.id())
                    ? supplied.get(definition.id())
                    : clean(definition.defaultValue());
            if (value == null) {
                if (Boolean.TRUE.equals(definition.requiredFlag())) {
                    throw new BusinessException(
                            "ATTRIBUTE_VALUE_REQUIRED",
                            "属性“" + definition.attributeName() + "”为必填项"
                    );
                }
                continue;
            }
            validateAttributeValue(definition, value);
            result.add(new ValidatedAttribute(definition.id(), definition.dataType(), value));
        }
        return result;
    }

    private void validateAttributeValue(
            MasterDataDtos.AttributeDefinitionRow definition,
            String value
    ) {
        try {
            BigDecimal numeric = switch (definition.dataType()) {
                case "INTEGER" -> new BigDecimal(Long.parseLong(value));
                case "DECIMAL" -> new BigDecimal(value);
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException();
                    }
                    yield null;
                }
                case "DATE" -> {
                    LocalDate.parse(value);
                    yield null;
                }
                case "ENUM" -> {
                    List<String> options = objectMapper.readValue(
                            definition.enumOptionsJson(), new TypeReference<>() {
                            }
                    );
                    if (!options.contains(value)) {
                        throw new IllegalArgumentException();
                    }
                    yield null;
                }
                case "STRING" -> null;
                default -> throw new IllegalArgumentException();
            };
            if (numeric != null
                    && definition.minimumValue() != null
                    && numeric.compareTo(definition.minimumValue()) < 0) {
                throw new IllegalArgumentException();
            }
            if (numeric != null
                    && definition.maximumValue() != null
                    && numeric.compareTo(definition.maximumValue()) > 0) {
                throw new IllegalArgumentException();
            }
            if (definition.validationPattern() != null
                    && !Pattern.compile(definition.validationPattern()).matcher(value).matches()) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new BusinessException(
                    "ATTRIBUTE_VALUE_INVALID",
                    "属性“" + definition.attributeName() + "”的值不符合定义"
            );
        }
    }

    private List<EquipmentDtos.SaveResponsiblePersonRequest> validateResponsiblePersons(
            long tenantId,
            EquipmentDtos.SaveEquipmentRequest request
    ) {
        List<EquipmentDtos.SaveResponsiblePersonRequest> values =
                new ArrayList<>(request.responsiblePersons() == null
                        ? List.of() : request.responsiblePersons());
        if (request.primaryResponsibleUserId() != null
                && values.stream().noneMatch(value ->
                        value.userId().equals(request.primaryResponsibleUserId())
                                && "PRIMARY".equals(value.responsibilityType()))) {
            values.add(new EquipmentDtos.SaveResponsiblePersonRequest(
                    request.primaryResponsibleUserId(), "PRIMARY", null, null
            ));
        }
        Set<String> unique = new LinkedHashSet<>();
        for (EquipmentDtos.SaveResponsiblePersonRequest value : values) {
            validateUser(tenantId, value.userId());
            if (value.startDate() != null
                    && value.endDate() != null
                    && value.startDate().isAfter(value.endDate())) {
                throw new BusinessException(
                        "RESPONSIBILITY_DATE_INVALID", "责任人的开始日期不能晚于结束日期"
                );
            }
            if (!unique.add(value.userId() + ":" + value.responsibilityType())) {
                throw new BusinessException(
                        "RESPONSIBLE_PERSON_DUPLICATE", "责任人及责任类型不能重复"
                );
            }
        }
        return values;
    }

    private void validateUser(long tenantId, Long userId) {
        if (userId != null && masterDataMapper.countActiveUser(tenantId, userId) == 0) {
            throw new BusinessException("RESPONSIBLE_USER_INVALID", "责任人不存在或已停用");
        }
    }

    private void replaceAttributes(
            long tenantId,
            long equipmentId,
            List<ValidatedAttribute> attributes,
            long operatorId
    ) {
        mapper.deleteAttributeValues(tenantId, equipmentId, operatorId);
        attributes.forEach(attribute -> mapper.insertAttributeValue(
                tenantId, equipmentId, attribute.definitionId(),
                attribute.dataType(), attribute.value(), operatorId
        ));
    }

    private void replaceResponsiblePersons(
            long tenantId,
            long equipmentId,
            List<EquipmentDtos.SaveResponsiblePersonRequest> people,
            long operatorId
    ) {
        mapper.deleteResponsiblePersons(tenantId, equipmentId, operatorId);
        people.forEach(person ->
                mapper.insertResponsiblePerson(tenantId, equipmentId, person, operatorId)
        );
    }

    private void syncPrimaryResponsiblePerson(
            long tenantId,
            long equipmentId,
            Long primaryUserId,
            long operatorId
    ) {
        List<EquipmentDtos.SaveResponsiblePersonRequest> people =
                mapper.findResponsiblePersons(tenantId, equipmentId).stream()
                        .filter(person -> !"PRIMARY".equals(person.responsibilityType()))
                        .map(person -> new EquipmentDtos.SaveResponsiblePersonRequest(
                                person.userId(), person.responsibilityType(),
                                person.startDate(), person.endDate()
                        ))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (primaryUserId != null) {
            people.add(new EquipmentDtos.SaveResponsiblePersonRequest(
                    primaryUserId, "PRIMARY", LocalDate.now(), null
            ));
        }
        replaceResponsiblePersons(tenantId, equipmentId, people, operatorId);
    }

    private void synchronizeEnableStatus(
            long tenantId,
            long equipmentId,
            boolean wasEnabled,
            boolean enabled,
            long operatorId
    ) {
        if (wasEnabled == enabled) {
            return;
        }
        EquipmentMapper.CurrentStatus current = mapper.findCurrentStatus(tenantId, equipmentId);
        if (current == null) {
            throw new BusinessException(
                    "CURRENT_STATUS_NOT_FOUND", "设备当前状态不存在", HttpStatus.NOT_FOUND
            );
        }
        String target = "IDLE";
        if (target.equals(current.statusCode())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (mapper.updateCurrentStatus(
                tenantId,
                equipmentId,
                target,
                now,
                enabled ? "设备启用" : "设备停用",
                "SYSTEM",
                current.version(),
                operatorId
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.closeOpenStatusHistory(tenantId, equipmentId, now);
        mapper.insertStatusHistory(
                tenantId,
                equipmentId,
                current.statusCode(),
                target,
                now,
                enabled ? "设备启用" : "设备停用",
                "SYSTEM",
                operatorId
        );
    }

    private String normalizeOperatingStatus(String statusCode) {
        if (statusCode == null) {
            return null;
        }
        return switch (statusCode) {
            case "IDLE", "RUNNING", "STOPPED", "SCRAPPED" -> statusCode;
            case "NOT_ENABLED" -> "IDLE";
            default -> "STOPPED";
        };
    }

    private EquipmentDtos.EquipmentRow requireAccessible(
            long tenantId,
            long id,
            DataPermission scope
    ) {
        EquipmentDtos.EquipmentRow equipment = mapper.findEquipment(tenantId, id, scope);
        if (equipment == null) {
            throw new BusinessException(
                    "EQUIPMENT_NOT_FOUND", "设备不存在或无权访问", HttpStatus.NOT_FOUND
            );
        }
        return equipment;
    }

    private EquipmentDtos.SaveEquipmentRequest normalize(
            EquipmentDtos.SaveEquipmentRequest request
    ) {
        return new EquipmentDtos.SaveEquipmentRequest(
                cleanUpper(request.equipmentCode()),
                request.equipmentName().trim(),
                request.categoryId(),
                clean(request.model()),
                clean(request.specification()),
                clean(request.brand()),
                clean(request.manufacturer()),
                clean(request.factorySerialNumber()),
                request.productionDate(),
                request.commissioningDate(),
                request.organizationId(),
                request.locationId(),
                request.primaryResponsibleUserId(),
                clean(request.assetNumber()),
                upper(request.lifecycleStage()),
                request.critical(),
                request.special(),
                request.oeeEnabled(),
                request.enabled(),
                clean(request.description()),
                request.attributes(),
                request.responsiblePersons(),
                request.version()
        );
    }

    private ImportCandidate validateImportRow(
            long tenantId,
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            DataPermission scope,
            List<EquipmentDtos.ImportError> errors
    ) {
        int rowNumber = row.getRowNum() + 1;
        String categoryOriginalValue = cell(row, columns, formatter, "设备分类");
        String organizationOriginalValue = cell(row, columns, formatter, "所属组织");
        String categoryValue = importValue(
                rowNumber, "设备分类", categoryOriginalValue, errors,
                () -> requiredCell(row, columns, formatter, "设备分类")
        );
        String organizationValue = importValue(
                rowNumber, "所属组织", organizationOriginalValue, errors,
                () -> requiredCell(row, columns, formatter, "所属组织")
        );
        String categoryCode = normalizeCategoryCode(categoryValue);
        String organizationCode = upper(organizationValue);
        String locationOriginalValue = cell(row, columns, formatter, "物理位置");
        String locationCode = upper(locationOriginalValue);
        EquipmentMapper.LookupRow category = null;
        if (categoryCode != null) {
            category = importValue(rowNumber, "设备分类", categoryOriginalValue, errors, () -> {
                EquipmentMapper.LookupRow found = mapper.findCategoryByCode(tenantId, categoryCode);
                if (found == null || found.status() != 1) {
                    throw new BusinessException("IMPORT_CATEGORY_INVALID", "设备分类不存在或已停用");
                }
                return found;
            });
        }
        EquipmentMapper.LookupRow organization = null;
        if (organizationCode != null) {
            organization = importValue(
                    rowNumber, "所属组织", organizationOriginalValue, errors, () -> {
                        EquipmentMapper.LookupRow found =
                                mapper.findOrganizationByCode(tenantId, organizationCode);
                        if (found == null || found.status() != 1) {
                            throw new BusinessException(
                                    "IMPORT_ORGANIZATION_INVALID", "所属组织编码不存在或已停用"
                            );
                        }
                        if (!scope.canCreateIn(found.id())) {
                            throw new BusinessException(
                                    "IMPORT_ORGANIZATION_FORBIDDEN", "无权在该组织下创建设备"
                            );
                        }
                        return found;
                    });
        }
        EquipmentMapper.LocationLookup location = null;
        if (locationCode != null) {
            EquipmentMapper.LookupRow selectedOrganization = organization;
            location = importValue(
                    rowNumber, "物理位置", locationOriginalValue, errors, () -> {
                        EquipmentMapper.LocationLookup found =
                                mapper.findLocationByCode(tenantId, locationCode);
                        if (found == null || found.status() != 1
                                || (selectedOrganization != null
                                && found.organizationId() != selectedOrganization.id())) {
                            throw new BusinessException(
                                    "IMPORT_LOCATION_INVALID",
                                    "位置编码不存在、已停用或不属于所选组织"
                            );
                        }
                        return found;
                    });
        }
        String username = cell(row, columns, formatter, "主负责人");
        EquipmentMapper.UserLookup user = null;
        if (username != null) {
            user = importValue(rowNumber, "主负责人", username, errors, () -> {
                EquipmentMapper.UserLookup found = mapper.findUserByUsername(tenantId, username);
                if (found == null || found.status() != 1) {
                    throw new BusinessException(
                            "IMPORT_USER_INVALID", "主负责人账号不存在或已停用"
                    );
                }
                return found;
            });
        }
        String equipmentCode = cleanUpper(cell(row, columns, formatter, "设备编码"));
        String equipmentNameOriginalValue = cell(row, columns, formatter, "设备名称");
        String equipmentName = importValue(
                rowNumber, "设备名称", equipmentNameOriginalValue, errors,
                () -> requiredCell(row, columns, formatter, "设备名称")
        );
        String lifecycleStage = normalizeLifecycleStage(cell(row, columns, formatter, "生命周期"));
        String productionDateOriginalValue = cell(row, columns, formatter, "生产日期");
        LocalDate productionDate = importValue(
                rowNumber, "生产日期", productionDateOriginalValue, errors,
                () -> dateCell(row, columns, formatter, "生产日期")
        );
        String commissioningDateOriginalValue = cell(row, columns, formatter, "投产日期");
        LocalDate commissioningDate = importValue(
                rowNumber, "投产日期", commissioningDateOriginalValue, errors,
                () -> dateCell(row, columns, formatter, "投产日期")
        );
        String criticalOriginalValue = cell(row, columns, formatter, "关键设备");
        Boolean critical = importValue(
                rowNumber, "关键设备", criticalOriginalValue, errors,
                () -> parseBoolean(criticalOriginalValue, false)
        );
        String specialOriginalValue = cell(row, columns, formatter, "特种设备");
        Boolean special = importValue(
                rowNumber, "特种设备", specialOriginalValue, errors,
                () -> parseBoolean(specialOriginalValue, false)
        );
        String oeeEnabledOriginalValue = cell(row, columns, formatter, "OEE启用");
        Boolean oeeEnabled = importValue(
                rowNumber, "OEE启用", oeeEnabledOriginalValue, errors,
                () -> parseBoolean(oeeEnabledOriginalValue, true)
        );
        String enabledOriginalValue = cell(row, columns, formatter, "启用");
        Boolean enabled = importValue(
                rowNumber, "启用", enabledOriginalValue, errors,
                () -> parseBoolean(enabledOriginalValue, true)
        );
        EquipmentDtos.SaveEquipmentRequest request = new EquipmentDtos.SaveEquipmentRequest(
                equipmentCode,
                equipmentName == null ? "" : equipmentName,
                category == null ? 0L : category.id(),
                cell(row, columns, formatter, "型号"),
                cell(row, columns, formatter, "规格"),
                cell(row, columns, formatter, "品牌"),
                cell(row, columns, formatter, "制造商"),
                cell(row, columns, formatter, "出厂编号"),
                productionDate,
                commissioningDate,
                organization == null ? 0L : organization.id(),
                location == null ? null : location.id(),
                user == null ? null : user.id(),
                cell(row, columns, formatter, "资产编号"),
                lifecycleStage,
                critical == null ? false : critical,
                special == null ? false : special,
                oeeEnabled == null ? true : oeeEnabled,
                enabled == null ? true : enabled,
                null,
                List.of(),
                List.of(),
                null
        );
        validateImportFields(rowNumber, request, errors);
        if (productionDate != null && commissioningDate != null
                && productionDate.isAfter(commissioningDate)) {
            errors.add(new EquipmentDtos.ImportError(
                    rowNumber, "生产日期", productionDateOriginalValue,
                    "生产日期不能晚于投产日期"
            ));
        }
        return new ImportCandidate(rowNumber, request);
    }

    private void validateImportFields(
            int rowNumber,
            EquipmentDtos.SaveEquipmentRequest request,
            List<EquipmentDtos.ImportError> errors
    ) {
        importValidation(rowNumber, "设备编码", request.equipmentCode(), errors, () -> {
            String code = request.equipmentCode();
            if (code != null && (code.length() > 64
                    || !IMPORT_EQUIPMENT_CODE_PATTERN.matcher(code).matches())) {
                throw new BusinessException(
                        "IMPORT_EQUIPMENT_CODE_INVALID",
                        "设备编码最多 64 位，且只能包含中文、字母、数字及 . _ # -"
                );
            }
        });
        importValidation(rowNumber, "设备名称", request.equipmentName(), errors, () -> {
            if (request.equipmentName().length() > 150) {
                throw new BusinessException(
                        "IMPORT_EQUIPMENT_NAME_INVALID", "设备名称最多 150 个字符"
                );
            }
        });
        importValidation(rowNumber, "型号", request.model(), errors,
                () -> validateImportLength("IMPORT_MODEL_INVALID", "型号", request.model(), 100));
        importValidation(rowNumber, "规格", request.specification(), errors,
                () -> validateImportLength("IMPORT_SPECIFICATION_INVALID", "规格", request.specification(), 200));
        importValidation(rowNumber, "品牌", request.brand(), errors,
                () -> validateImportLength("IMPORT_BRAND_INVALID", "品牌", request.brand(), 100));
        importValidation(rowNumber, "制造商", request.manufacturer(), errors,
                () -> validateImportLength("IMPORT_MANUFACTURER_INVALID", "制造商", request.manufacturer(), 150));
        importValidation(rowNumber, "出厂编号", request.factorySerialNumber(), errors,
                () -> validateImportLength("IMPORT_FACTORY_SERIAL_INVALID", "出厂编号", request.factorySerialNumber(), 100));
        importValidation(rowNumber, "资产编号", request.assetNumber(), errors,
                () -> validateImportLength("IMPORT_ASSET_NUMBER_INVALID", "资产编号", request.assetNumber(), 100));
        importValidation(rowNumber, "设备说明", request.description(), errors,
                () -> validateImportLength("IMPORT_DESCRIPTION_INVALID", "设备说明", request.description(), 1000));
        importValidation(rowNumber, "生命周期", request.lifecycleStage(), errors, () -> {
            if (!IMPORT_LIFECYCLE_VALUES.contains(request.lifecycleStage())) {
                throw new BusinessException(
                        "IMPORT_LIFECYCLE_INVALID",
                        "生命周期应填写规划、安装、调试、在役、闲置、封存或报废"
                );
            }
        });
    }

    private <T> T importValue(
            int rowNumber,
            String field,
            String originalValue,
            List<EquipmentDtos.ImportError> errors,
            Supplier<T> supplier
    ) {
        try {
            return supplier.get();
        } catch (BusinessException | IllegalArgumentException exception) {
            errors.add(new EquipmentDtos.ImportError(
                    rowNumber, field, originalValue, exception.getMessage()
            ));
            return null;
        }
    }

    private void importValidation(
            int rowNumber,
            String field,
            String originalValue,
            List<EquipmentDtos.ImportError> errors,
            Runnable validation
    ) {
        try {
            validation.run();
        } catch (BusinessException | IllegalArgumentException exception) {
            errors.add(new EquipmentDtos.ImportError(
                    rowNumber, field, originalValue, exception.getMessage()
            ));
        }
    }

    private int importMaxRows(long tenantId) {
        String value = parameterService.getString(
                tenantId, "equipment.import.max-rows", "1000"
        );
        try {
            return Math.max(1, Math.min(10_000, Integer.parseInt(value)));
        } catch (NumberFormatException exception) {
            return 1000;
        }
    }

    private Map<String, Integer> headerColumns(Row row) {
        if (row == null) {
            throw new BusinessException("IMPORT_HEADER_INVALID", "Excel 缺少表头");
        }
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            String value = clean(formatter.formatCellValue(cell));
            if (value != null) {
                result.put(canonicalImportHeader(value), cell.getColumnIndex());
            }
        }
        return result;
    }

    static String canonicalImportHeader(String value) {
        String canonical = ImportWorkbookSupport.canonicalHeader(value);
        return IMPORT_HEADER_ALIASES.getOrDefault(canonical, canonical);
    }

    static String normalizeCategoryCode(String value) {
        String cleaned = value == null ? null : value.trim();
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        return IMPORT_CATEGORY_CODES.getOrDefault(cleaned, cleaned).toUpperCase(Locale.ROOT);
    }

    static String normalizeLifecycleStage(String value) {
        String cleaned = value == null ? null : value.trim();
        if (cleaned == null || cleaned.isEmpty()) {
            return "IN_SERVICE";
        }
        return IMPORT_LIFECYCLE_CODES.getOrDefault(cleaned, cleaned.toUpperCase(Locale.ROOT));
    }

    static void validateImportRequest(EquipmentDtos.SaveEquipmentRequest request) {
        String equipmentCode = request.equipmentCode();
        if (equipmentCode != null && (equipmentCode.length() > 64
                || !IMPORT_EQUIPMENT_CODE_PATTERN.matcher(equipmentCode).matches())) {
            throw new BusinessException(
                    "IMPORT_EQUIPMENT_CODE_INVALID",
                    "设备编码最多 64 位，且只能包含中文、字母、数字及 . _ # -"
            );
        }
        if (request.equipmentName().length() > 150) {
            throw new BusinessException("IMPORT_EQUIPMENT_NAME_INVALID", "设备名称最多 150 个字符");
        }
        validateImportLength("IMPORT_MODEL_INVALID", "型号", request.model(), 100);
        validateImportLength("IMPORT_SPECIFICATION_INVALID", "规格", request.specification(), 200);
        validateImportLength("IMPORT_BRAND_INVALID", "品牌", request.brand(), 100);
        validateImportLength("IMPORT_MANUFACTURER_INVALID", "制造商", request.manufacturer(), 150);
        validateImportLength("IMPORT_FACTORY_SERIAL_INVALID", "出厂编号", request.factorySerialNumber(), 100);
        validateImportLength("IMPORT_ASSET_NUMBER_INVALID", "资产编号", request.assetNumber(), 100);
        validateImportLength("IMPORT_DESCRIPTION_INVALID", "设备说明", request.description(), 1000);
        if (!IMPORT_LIFECYCLE_VALUES.contains(request.lifecycleStage())) {
            throw new BusinessException(
                    "IMPORT_LIFECYCLE_INVALID",
                    "生命周期应填写规划、安装、调试、在役、闲置、封存或报废"
            );
        }
    }

    private static void validateImportLength(String code, String field, String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new BusinessException(code, field + "最多 " + maximum + " 个字符");
        }
    }

    static String importErrorField(BusinessException exception) {
        return switch (exception.getCode()) {
            case "IMPORT_EQUIPMENT_CODE_INVALID" -> "设备编码";
            case "IMPORT_EQUIPMENT_NAME_INVALID" -> "设备名称";
            case "IMPORT_CATEGORY_INVALID" -> "设备分类";
            case "IMPORT_ORGANIZATION_INVALID" -> "所属组织";
            case "IMPORT_LOCATION_INVALID" -> "物理位置";
            case "IMPORT_USER_INVALID" -> "主负责人";
            case "IMPORT_MODEL_INVALID" -> "型号";
            case "IMPORT_SPECIFICATION_INVALID" -> "规格";
            case "IMPORT_BRAND_INVALID" -> "品牌";
            case "IMPORT_MANUFACTURER_INVALID" -> "制造商";
            case "IMPORT_FACTORY_SERIAL_INVALID" -> "出厂编号";
            case "IMPORT_ASSET_NUMBER_INVALID" -> "资产编号";
            case "IMPORT_DESCRIPTION_INVALID" -> "设备说明";
            case "IMPORT_LIFECYCLE_INVALID" -> "生命周期";
            default -> null;
        };
    }

    private void writeImportGuide(Workbook workbook) {
        Sheet sheet = workbook.createSheet("填写规范");
        writeHeader(sheet, List.of("字段", "填写说明"));
        List<List<String>> rows = List.of(
                List.of("设备分类", "可填写五个默认中文名称（见“设备分类参考”）或任意分类编码"),
                List.of("所属组织", "填写所属组织编码"),
                List.of("物理位置", "选填；填写物理位置编码，且必须属于所选组织"),
                List.of("主负责人", "选填；填写主负责人账号"),
                List.of("生命周期", "规划、安装、调试、在役、闲置、封存、报废；兼容历史英文编码"),
                List.of("生产日期/投产日期", "支持 Excel 日期、yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd 或 yyyy年MM月dd日"),
                List.of("关键设备/特种设备/OEE启用/启用", "填写是或否")
        );
        writeRows(sheet, rows);
        autoSize(sheet, 2);
    }

    private void writeEquipmentCategoryReference(Workbook workbook) {
        Sheet sheet = workbook.createSheet("设备分类参考");
        writeHeader(sheet, List.of("设备分类", "稳定编码"));
        writeRows(sheet, List.of(
                List.of("生产设备", "PRODUCTION"),
                List.of("环保设备", "ENVIRONMENTAL_EQUIPMENT"),
                List.of("辅助设备", "AUXILIARY_EQUIPMENT"),
                List.of("运输设备", "TRANSPORT_EQUIPMENT"),
                List.of("其它设备", "OTHER_EQUIPMENT")
        ));
        autoSize(sheet, 2);
    }

    private void writeRows(Sheet sheet, List<List<String>> rows) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex + 1);
            List<String> values = rows.get(rowIndex);
            for (int column = 0; column < values.size(); column++) {
                row.createCell(column).setCellValue(values.get(column));
            }
        }
    }

    private String requiredCell(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            String header
    ) {
        String value = cell(row, columns, formatter, header);
        if (value == null) {
            throw new BusinessException("IMPORT_VALUE_REQUIRED", header + "不能为空");
        }
        return value;
    }

    private String cell(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            String header
    ) {
        Integer index = columns.get(header);
        if (index == null) {
            return null;
        }
        return clean(formatter.formatCellValue(
                row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
        ));
    }

    private boolean rowIsBlank(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (clean(formatter.formatCellValue(cell)) != null) {
                return false;
            }
        }
        return true;
    }

    private LocalDate dateCell(
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter,
            String field
    ) {
        Integer index = columns.get(field);
        if (index == null) {
            return null;
        }
        Cell source = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (source == null) {
            return null;
        }
        if (source.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            if (!DateUtil.isCellDateFormatted(source)) {
                throw new BusinessException(
                        "IMPORT_DATE_INVALID", field + "必须是日期，不能填写普通数字"
                );
            }
            return source.getLocalDateTimeCellValue().toLocalDate();
        }
        return parseImportDate(formatter.formatCellValue(source), field);
    }

    static LocalDate parseImportDate(String value, String field) {
        String cleaned = value == null ? null : value.trim();
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter dateFormatter : IMPORT_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(cleaned, dateFormatter);
            } catch (DateTimeParseException ignored) {
                // Try the next unambiguous year-first format.
            }
        }
        throw new BusinessException(
                "IMPORT_DATE_INVALID",
                field + "应为 yyyy-MM-dd、yyyy/MM/dd、yyyy.MM.dd 或 yyyy年MM月dd日"
        );
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "是", "1", "true", "yes", "y" -> true;
            case "否", "0", "false", "no", "n" -> false;
            default -> throw new BusinessException(
                    "IMPORT_BOOLEAN_INVALID", "布尔值应填写是/否、true/false 或 1/0"
            );
        };
    }

    private record ImportCandidate(
            int rowNumber,
            EquipmentDtos.SaveEquipmentRequest request
    ) {
    }

    private void writeHeader(Sheet sheet, List<String> headers) {
        Row header = sheet.createRow(0);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(
                new org.apache.poi.ss.util.CellRangeAddress(
                        0, 0, 0, Math.max(0, headers.size() - 1)
                )
        );
    }

    private void autoSize(Sheet sheet, int columns) {
        for (int index = 0; index < columns; index++) {
            sheet.autoSizeColumn(index);
            sheet.setColumnWidth(
                    index,
                    Math.min(50 * 256, Math.max(12 * 256, sheet.getColumnWidth(index) + 512))
            );
        }
    }

    private byte[] workbookBytes(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String stripTrailingSlash(String value) {
        String cleaned = value == null ? DEFAULT_BARCODE_BASE_URL : value.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private String defaultValue(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String yesNo(Boolean value) {
        return Boolean.TRUE.equals(value) ? "是" : "否";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String upper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private String cleanUpper(String value) {
        return upper(value);
    }

    private BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT",
                "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }

    private BusinessException dataScopeDenied() {
        return new BusinessException(
                "DATA_SCOPE_DENIED", "无权在所选组织下维护设备", HttpStatus.FORBIDDEN
        );
    }

    private record ValidatedAttribute(long definitionId, String dataType, String value) {
    }
}
