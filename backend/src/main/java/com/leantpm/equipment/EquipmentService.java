package com.leantpm.equipment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class EquipmentService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String DEFAULT_BARCODE_BASE_URL = "http://localhost:5173/m/e";
    private static final List<String> IMPORT_HEADERS = List.of(
            "设备编码", "设备名称", "分类编码", "组织编码", "位置编码",
            "型号", "规格", "品牌", "制造商", "出厂编号", "生产日期", "投产日期",
            "负责人账号", "资产编号", "生命周期阶段", "关键设备", "特种设备",
            "OEE启用", "启用"
    );
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.ofEntries(
            Map.entry("NOT_ENABLED", Set.of("IDLE", "COMMISSIONING", "OFFLINE")),
            Map.entry("IDLE", Set.of(
                    "RUNNING", "MAINTENANCE", "INSPECTION", "FAULT",
                    "STOPPED", "OFFLINE", "SCRAPPED"
            )),
            Map.entry("RUNNING", Set.of("IDLE", "CHANGEOVER", "FAULT", "STOPPED", "OFFLINE")),
            Map.entry("COMMISSIONING", Set.of("IDLE", "FAULT", "OFFLINE")),
            Map.entry("CHANGEOVER", Set.of("RUNNING", "IDLE", "FAULT")),
            Map.entry("MAINTENANCE", Set.of("IDLE", "FAULT", "OFFLINE")),
            Map.entry("INSPECTION", Set.of("IDLE", "FAULT", "OFFLINE")),
            Map.entry("FAULT", Set.of("REPAIR", "STOPPED", "OFFLINE")),
            Map.entry("REPAIR", Set.of("IDLE", "RUNNING", "FAULT", "STOPPED", "OFFLINE")),
            Map.entry("STOPPED", Set.of("IDLE", "MAINTENANCE", "REPAIR", "FAULT", "SCRAPPED", "OFFLINE")),
            Map.entry("OFFLINE", Set.of("IDLE", "COMMISSIONING", "FAULT")),
            Map.entry("SCRAPPED", Set.of())
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
            int page,
            int pageSize
    ) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = DataPermission.all(current.userId());
        int offset = (page - 1) * pageSize;
        String normalizedStatus = upper(currentStatusCode);
        String normalizedStage = upper(lifecycleStage);
        return PageResult.of(
                mapper.findEquipmentPage(
                        current.tenantId(), scope, clean(keyword), categoryId,
                        organizationId, locationId, normalizedStatus, normalizedStage,
                        status, offset, pageSize
                ),
                mapper.countEquipment(
                        current.tenantId(), scope, clean(keyword), categoryId,
                        organizationId, locationId, normalizedStatus, normalizedStage, status
                ),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public EquipmentDtos.EquipmentDetail detail(long id) {
        var current = SecurityUtils.currentUser();
        DataPermission readScope = DataPermission.all(current.userId());
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
        String initialStatus = Boolean.TRUE.equals(normalized.enabled()) ? "IDLE" : "NOT_ENABLED";
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
                && existing.locationId() == request.locationId()
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
        String next = upper(request.statusCode());
        if (equipment.status() != 1 && !"NOT_ENABLED".equals(next)) {
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
        requireAccessible(current.tenantId(), id, DataPermission.all(current.userId()));
        return mapper.findStatusHistory(current.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public List<EquipmentDtos.BarcodeRow> barcodes(Long equipmentId, boolean activeOnly) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = DataPermission.all(current.userId());
        if (equipmentId != null) {
            requireAccessible(current.tenantId(), equipmentId, scope);
        }
        return mapper.findBarcodes(current.tenantId(), scope, equipmentId, activeOnly);
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
                current.tenantId(), barcode.equipmentId(), DataPermission.all(current.userId())
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
        int effectiveHeight = format == BarcodeFormat.QR_CODE ? width : height;
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(
                    content,
                    format,
                    width,
                    effectiveHeight,
                    Map.of(EncodeHintType.MARGIN, 1)
            );
            BufferedImage image = new BufferedImage(
                    matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB
            );
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF111827 : 0xFFFFFFFF);
                }
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", output);
                return output.toByteArray();
            }
        } catch (WriterException | IOException exception) {
            throw new BusinessException(
                    "BARCODE_RENDER_FAILED", "条码图片生成失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
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
                0, 100_000
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
            writeHeader(sheet, IMPORT_HEADERS);
            Row example = sheet.createRow(1);
            List<String> values = List.of(
                    "", "示例设备", "PUMP", "FACTORY", "WS-A", "M-100",
                    "示例规格", "示例品牌", "示例制造商", "SN-001",
                    "2026-01-01", "2026-02-01", "admin", "ASSET-001",
                    "IN_SERVICE", "否", "否", "是", "是"
            );
            for (int column = 0; column < values.size(); column++) {
                example.createCell(column).setCellValue(values.get(column));
            }
            autoSize(sheet, IMPORT_HEADERS.size());
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
        int totalRows;
        int importedRows = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new BusinessException("IMPORT_FILE_INVALID", "Excel 文件没有数据");
            }
            Map<String, Integer> columns = headerColumns(sheet.getRow(0));
            for (String required : List.of("设备名称", "分类编码", "组织编码", "位置编码")) {
                if (!columns.containsKey(required)) {
                    throw new BusinessException(
                            "IMPORT_HEADER_INVALID", "缺少必填列：" + required
                    );
                }
            }
            totalRows = Math.max(0, sheet.getLastRowNum());
            if (totalRows > maxRows) {
                throw new BusinessException(
                        "IMPORT_ROW_LIMIT", "单次最多导入 " + maxRows + " 行设备"
                );
            }
            DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
                Row row = sheet.getRow(rowNumber);
                if (row == null || rowIsBlank(row, formatter)) {
                    continue;
                }
                try {
                    EquipmentDtos.SaveEquipmentRequest request =
                            importRequest(current.tenantId(), row, columns, formatter);
                    create(current.tenantId(), current.userId(), request, scope);
                    importedRows++;
                } catch (BusinessException | IllegalArgumentException exception) {
                    errors.add(new EquipmentDtos.ImportError(
                            rowNumber + 1, null, exception.getMessage()
                    ));
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("IMPORT_FILE_INVALID", "无法读取 Excel 文件");
        }
        changeLogService.record(
                "EQUIPMENT_IMPORT",
                LocalDateTime.now(),
                "IMPORT",
                null,
                Map.of(
                        "fileName", safe(file.getOriginalFilename()),
                        "totalRows", totalRows,
                        "importedRows", importedRows,
                        "errorRows", errors.size()
                )
        );
        return new EquipmentDtos.ImportResult(totalRows, importedRows, errors);
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
                mapper.findBarcodes(tenantId, scope, equipment.id(), false),
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
            long locationId,
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
        String target = enabled ? "IDLE" : "NOT_ENABLED";
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

    private EquipmentDtos.SaveEquipmentRequest importRequest(
            long tenantId,
            Row row,
            Map<String, Integer> columns,
            DataFormatter formatter
    ) {
        String categoryCode = requiredCell(row, columns, formatter, "分类编码").toUpperCase();
        String organizationCode =
                requiredCell(row, columns, formatter, "组织编码").toUpperCase();
        String locationCode = requiredCell(row, columns, formatter, "位置编码").toUpperCase();
        EquipmentMapper.LookupRow category = mapper.findCategoryByCode(tenantId, categoryCode);
        EquipmentMapper.LookupRow organization =
                mapper.findOrganizationByCode(tenantId, organizationCode);
        EquipmentMapper.LocationLookup location =
                mapper.findLocationByCode(tenantId, locationCode);
        if (category == null || category.status() != 1) {
            throw new BusinessException("IMPORT_CATEGORY_INVALID", "分类编码不存在或已停用");
        }
        if (organization == null || organization.status() != 1) {
            throw new BusinessException("IMPORT_ORGANIZATION_INVALID", "组织编码不存在或已停用");
        }
        if (location == null
                || location.status() != 1
                || location.organizationId() != organization.id()) {
            throw new BusinessException(
                    "IMPORT_LOCATION_INVALID", "位置编码不存在、已停用或不属于所选组织"
            );
        }
        String username = cell(row, columns, formatter, "负责人账号");
        EquipmentMapper.UserLookup user =
                username == null ? null : mapper.findUserByUsername(tenantId, username);
        if (username != null && (user == null || user.status() != 1)) {
            throw new BusinessException("IMPORT_USER_INVALID", "负责人账号不存在或已停用");
        }
        return new EquipmentDtos.SaveEquipmentRequest(
                cleanUpper(cell(row, columns, formatter, "设备编码")),
                requiredCell(row, columns, formatter, "设备名称"),
                category.id(),
                cell(row, columns, formatter, "型号"),
                cell(row, columns, formatter, "规格"),
                cell(row, columns, formatter, "品牌"),
                cell(row, columns, formatter, "制造商"),
                cell(row, columns, formatter, "出厂编号"),
                parseDate(cell(row, columns, formatter, "生产日期"), "生产日期"),
                parseDate(cell(row, columns, formatter, "投产日期"), "投产日期"),
                organization.id(),
                location.id(),
                user == null ? null : user.id(),
                cell(row, columns, formatter, "资产编号"),
                defaultValue(
                        upper(cell(row, columns, formatter, "生命周期阶段")),
                        "IN_SERVICE"
                ),
                parseBoolean(cell(row, columns, formatter, "关键设备"), false),
                parseBoolean(cell(row, columns, formatter, "特种设备"), false),
                parseBoolean(cell(row, columns, formatter, "OEE启用"), true),
                parseBoolean(cell(row, columns, formatter, "启用"), true),
                null,
                List.of(),
                List.of(),
                null
        );
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
                result.put(value, cell.getColumnIndex());
            }
        }
        return result;
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

    private LocalDate parseDate(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException("IMPORT_DATE_INVALID", field + "应为 yyyy-MM-dd");
        }
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
