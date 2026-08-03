package com.leantpm.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.equipment.EquipmentMapper;
import com.leantpm.masterdata.MasterDataDtos;
import com.leantpm.masterdata.MasterDataMapper;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InspectionImportService {
    private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_ITEM_ROWS = 1_000;
    private static final int MAX_SCHEME_ROWS = 200;
    private static final int MAX_RELATION_ROWS = 5_000;

    private static final String ITEM_SHEET = "点检项目";
    private static final String SCHEME_SHEET = "点检方案";
    private static final String SCHEME_ITEM_SHEET = "方案项目";
    private static final String APPLICABILITY_SHEET = "适用设备";

    private static final List<String> ITEM_HEADERS = List.of(
            "项目编码", "项目名称", "项目分类", "点检部位", "点检内容", "点检方法",
            "点检工具", "点检标准", "标准值", "下限", "上限", "单位", "结果类型",
            "结果选项", "必填", "必拍", "必须数值", "允许跳过", "异常等级",
            "异常建议", "标准分钟", "安全说明", "启用", "描述"
    );
    private static final List<String> SCHEME_HEADERS = List.of(
            "方案编码", "方案名称", "点检类型", "周期类型", "周期间隔", "星期",
            "月日期", "计划时间", "班次编码", "默认执行人账号", "默认班组编码",
            "需要复核", "允许补录", "生效日期", "失效日期", "启用", "描述", "变更说明"
    );
    private static final List<String> SCHEME_ITEM_HEADERS = List.of(
            "方案编码", "项目编码", "顺序", "必填覆盖", "必拍覆盖", "允许跳过覆盖"
    );
    private static final List<String> APPLICABILITY_HEADERS = List.of(
            "方案编码", "设备编码", "分类编码"
    );
    private static final Set<String> RESULT_TYPES = Set.of(
            "NORMAL_ABNORMAL", "PASS_FAIL", "NUMBER", "TEXT", "SINGLE_CHOICE",
            "MULTIPLE_CHOICE", "IMAGE", "ATTACHMENT"
    );
    private static final Set<String> CHOICE_TYPES = Set.of(
            "SINGLE_CHOICE", "MULTIPLE_CHOICE"
    );
    private static final Set<String> SEVERITIES = Set.of(
            "LOW", "MEDIUM", "HIGH", "CRITICAL"
    );
    private static final Set<String> CYCLE_TYPES = Set.of(
            "DAILY", "WEEKLY", "MONTHLY", "INTERVAL_DAYS"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final InspectionMapper inspectionMapper;
    private final InspectionCalendarMapper inspectionCalendarMapper;
    private final EquipmentMapper equipmentMapper;
    private final MasterDataMapper masterDataMapper;
    private final InspectionCatalogService catalogService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;

    public InspectionImportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            InspectionMapper inspectionMapper,
            InspectionCalendarMapper inspectionCalendarMapper,
            EquipmentMapper equipmentMapper,
            MasterDataMapper masterDataMapper,
            InspectionCatalogService catalogService,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.inspectionMapper = inspectionMapper;
        this.inspectionCalendarMapper = inspectionCalendarMapper;
        this.equipmentMapper = equipmentMapper;
        this.masterDataMapper = masterDataMapper;
        this.catalogService = catalogService;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
    }

    @Transactional(readOnly = true)
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook();
             var output = new ByteArrayOutputStream()) {
            CellStyle header = WorkbookSupport.headerStyle(workbook);
            addSheet(workbook, ITEM_SHEET, ITEM_HEADERS, List.of(
                    "IMP-LUB-001", "润滑油液位", "OPERATION", "主轴润滑箱",
                    "检查润滑油液位", "目视", "", "液位处于刻度范围内",
                    "", "30", "80", "%", "NUMBER", "", "是", "否", "是",
                    "否", "MEDIUM", "补充润滑油", "5", "设备运转时不得打开油箱",
                    "是", "导入示例"
            ), header);
            addSheet(workbook, SCHEME_SHEET, SCHEME_HEADERS, List.of(
                    "IMP-SCHEME-001", "导入示例日常点检", "DAILY", "DAILY", "1",
                    "", "", "08:00", "", "operator01", "TEAM-A-1", "是", "是",
                    LocalDate.now().toString(), "", "是", "导入示例", "首次导入"
            ), header);
            addSheet(workbook, SCHEME_ITEM_SHEET, SCHEME_ITEM_HEADERS, List.of(
                    "IMP-SCHEME-001", "IMP-LUB-001", "10", "", "", ""
            ), header);
            addSheet(workbook, APPLICABILITY_SHEET, APPLICABILITY_HEADERS, List.of(
                    "IMP-SCHEME-001", "VIZ-PUMP-01", ""
            ), header);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(
                    "INSPECTION_IMPORT_TEMPLATE_FAILED", "点检导入模板生成失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    @Transactional
    public InspectionImportDtos.ImportResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("IMPORT_FILE_EMPTY", "请选择要导入的 Excel 文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException("IMPORT_FILE_TOO_LARGE", "导入文件不能超过 10 MB");
        }
        var current = SecurityUtils.currentUser();
        ParsedWorkbook parsed = parse(file);
        validateSemantics(current.tenantId(), parsed);
        String batchId = UUID.randomUUID().toString();
        String status = parsed.errors().isEmpty() ? "VALIDATED" : "INVALID";
        InspectionImportDtos.ImportCounts counts = predictedCounts(
                current.tenantId(), parsed.payload()
        );
        jdbc.update("""
                INSERT INTO inspection_import_batch
                    (tenant_id, batch_code, file_name, file_sha256, import_status,
                     payload_json, errors_json, item_rows, scheme_rows, relation_rows,
                     created_by)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?)
                """,
                current.tenantId(), batchId, safeFileName(file.getOriginalFilename()),
                sha256(file), status, json(parsed.payload()), json(parsed.errors()),
                parsed.payload().items().size(), parsed.payload().schemes().size(),
                parsed.payload().schemeItems().size() + parsed.payload().applicability().size(),
                current.userId()
        );
        return result(
                batchId, status, parsed.payload(), counts, parsed.errors(), null
        );
    }

    @Transactional(readOnly = true)
    public InspectionImportDtos.ImportResult batch(String batchId) {
        var current = SecurityUtils.currentUser();
        InspectionImportDtos.BatchRow row = findBatch(
                current.tenantId(), current.userId(), batchId, false
        );
        return rowResult(row);
    }

    @Transactional
    public InspectionImportDtos.ImportResult commit(String batchId) {
        var current = SecurityUtils.currentUser();
        InspectionImportDtos.BatchRow batch = findBatch(
                current.tenantId(), current.userId(), batchId, true
        );
        if ("COMMITTED".equals(batch.status())) {
            return rowResult(batch);
        }
        if (!"VALIDATED".equals(batch.status())) {
            throw new BusinessException(
                    "INSPECTION_IMPORT_NOT_VALID", "导入批次校验未通过，不能确认导入",
                    HttpStatus.CONFLICT
            );
        }

        InspectionImportDtos.ImportPayload payload = read(
                batch.payloadJson(), InspectionImportDtos.ImportPayload.class
        );
        InspectionImportDtos.ImportCounts counts = apply(current.tenantId(), payload);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime committedTime = now.withNano(
                (now.getNano() / 1_000_000) * 1_000_000
        );
        int changed = jdbc.update("""
                UPDATE inspection_import_batch
                SET import_status = 'COMMITTED', result_json = CAST(? AS JSON),
                    committed_by = ?, committed_time = ?
                WHERE tenant_id = ? AND batch_code = ? AND created_by = ?
                  AND import_status = 'VALIDATED'
                """,
                json(counts), current.userId(), committedTime, current.tenantId(),
                batchId, current.userId()
        );
        if (changed != 1) {
            throw new BusinessException(
                    "INSPECTION_IMPORT_STATE_CONFLICT", "导入批次状态已变化，请刷新后重试",
                    HttpStatus.CONFLICT
            );
        }
        changeLogService.record(
                "INSPECTION_IMPORT", batchId, "COMMIT", null,
                Map.of(
                        "items", payload.items().size(),
                        "schemes", payload.schemes().size(),
                        "relations", payload.schemeItems().size()
                                + payload.applicability().size(),
                        "counts", counts
                )
        );
        return result(
                batchId, "COMMITTED", payload, counts, List.of(), committedTime
        );
    }

    private InspectionImportDtos.ImportCounts apply(
            long tenantId,
            InspectionImportDtos.ImportPayload payload
    ) {
        int newItems = 0;
        int updatedItems = 0;
        for (InspectionImportDtos.ItemInput input : payload.items()) {
            Long itemId = inspectionMapper.findItemIdByCode(tenantId, input.itemCode());
            InspectionDtos.SaveItemRequest request = itemRequest(input, null);
            if (itemId == null) {
                catalogService.createItem(request);
                newItems++;
            } else {
                InspectionDtos.ItemRow existing = inspectionMapper.findItem(tenantId, itemId);
                catalogService.updateItem(itemId, itemRequest(input, existing.version()));
                updatedItems++;
            }
        }

        Map<String, List<InspectionImportDtos.SchemeItemInput>> schemeItems =
                payload.schemeItems().stream().collect(Collectors.groupingBy(
                        InspectionImportDtos.SchemeItemInput::schemeCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, List<InspectionImportDtos.ApplicabilityInput>> applicability =
                payload.applicability().stream().collect(Collectors.groupingBy(
                        InspectionImportDtos.ApplicabilityInput::schemeCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        int newSchemes = 0;
        int newVersions = 0;
        for (InspectionImportDtos.SchemeInput input : payload.schemes()) {
            Long existingId = inspectionMapper.findSchemeIdByCode(
                    tenantId, input.schemeCode()
            );
            Integer version = existingId == null
                    ? null : inspectionMapper.findScheme(tenantId, existingId).version();
            InspectionDtos.SaveSchemeRequest request = schemeRequest(
                    tenantId, input,
                    schemeItems.getOrDefault(input.schemeCode(), List.of()),
                    applicability.getOrDefault(input.schemeCode(), List.of()),
                    version
            );
            if (existingId == null) {
                catalogService.createScheme(request);
                newSchemes++;
            } else {
                catalogService.createSchemeVersion(existingId, request);
                newVersions++;
            }
        }
        return new InspectionImportDtos.ImportCounts(
                newItems, updatedItems, newSchemes, newVersions
        );
    }

    private InspectionDtos.SaveItemRequest itemRequest(
            InspectionImportDtos.ItemInput input,
            Integer version
    ) {
        return new InspectionDtos.SaveItemRequest(
                input.itemCode(), input.itemName(), input.itemCategory(),
                input.inspectionPart(), input.inspectionContent(), input.inspectionMethod(),
                input.inspectionTool(), input.inspectionStandard(), input.standardValue(),
                input.minimumValue(), input.maximumValue(), input.unit(), input.resultType(),
                input.resultOptions(), input.required(), input.photoRequired(),
                input.numericRequired(), input.skipAllowed(), input.abnormalSeverity(),
                input.abnormalAdvice(), true, input.standardMinutes(), input.safetyNotes(),
                input.enabled(), input.description(), version
        );
    }

    private InspectionDtos.SaveSchemeRequest schemeRequest(
            long tenantId,
            InspectionImportDtos.SchemeInput input,
            List<InspectionImportDtos.SchemeItemInput> itemInputs,
            List<InspectionImportDtos.ApplicabilityInput> applicability,
            Integer version
    ) {
        Long assigneeId = null;
        if (input.defaultAssigneeUsername() != null) {
            EquipmentMapper.UserLookup user = equipmentMapper.findUserByUsername(
                    tenantId, input.defaultAssigneeUsername()
            );
            assigneeId = user == null ? null : user.id();
        }
        List<InspectionDtos.SaveSchemeItemRequest> items = itemInputs.stream()
                .map(item -> new InspectionDtos.SaveSchemeItemRequest(
                        inspectionMapper.findItemIdByCode(tenantId, item.itemCode()),
                        item.sortOrder(), item.required(), item.photoRequired(),
                        item.skipAllowed(), null
                ))
                .toList();
        LinkedHashSet<Long> categoryIds = new LinkedHashSet<>();
        LinkedHashSet<Long> equipmentIds = new LinkedHashSet<>();
        for (InspectionImportDtos.ApplicabilityInput row : applicability) {
            if (row.categoryCode() != null) {
                categoryIds.add(equipmentMapper.findCategoryByCode(
                        tenantId, row.categoryCode()
                ).id());
            }
            if (row.equipmentCode() != null) {
                equipmentIds.add(equipmentMapper.findEquipmentIdByCode(
                        tenantId, row.equipmentCode()
                ));
            }
        }
        return new InspectionDtos.SaveSchemeRequest(
                input.schemeCode(), input.schemeName(), input.inspectionType(),
                input.cycleType(), input.cycleInterval(), input.weekDays(),
                input.monthDays(), input.scheduledTime(), 60,
                inspectionCalendarId(tenantId), input.shiftCode(), assigneeId,
                input.defaultTeamCode(), input.reviewRequired(), input.backfillAllowed(),
                input.effectiveDate(), input.expiryDate(), items, List.copyOf(categoryIds),
                List.copyOf(equipmentIds), input.enabled(), input.description(),
                input.changeSummary(), version
        );
    }

    private ParsedWorkbook parse(MultipartFile file) {
        List<InspectionImportDtos.ImportError> errors = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            List<InspectionImportDtos.ItemInput> items = parseItems(
                    workbook.getSheet(ITEM_SHEET), errors
            );
            List<InspectionImportDtos.SchemeInput> schemes = parseSchemes(
                    workbook.getSheet(SCHEME_SHEET), errors
            );
            List<InspectionImportDtos.SchemeItemInput> schemeItems = parseSchemeItems(
                    workbook.getSheet(SCHEME_ITEM_SHEET), errors
            );
            List<InspectionImportDtos.ApplicabilityInput> applicability = parseApplicability(
                    workbook.getSheet(APPLICABILITY_SHEET), errors
            );
            if (items.size() > MAX_ITEM_ROWS) {
                errors.add(error(ITEM_SHEET, 0, null, "最多允许 1000 行点检项目"));
            }
            if (schemes.size() > MAX_SCHEME_ROWS) {
                errors.add(error(SCHEME_SHEET, 0, null, "最多允许 200 行点检方案"));
            }
            if (schemeItems.size() + applicability.size() > MAX_RELATION_ROWS) {
                errors.add(error("关系数据", 0, null, "方案关系合计最多允许 5000 行"));
            }
            return new ParsedWorkbook(
                    new InspectionImportDtos.ImportPayload(
                            items, schemes, schemeItems, applicability
                    ), errors
            );
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(
                    "IMPORT_FILE_INVALID", "无法读取点检 Excel 文件，请使用系统模板"
            );
        }
    }

    private Long inspectionCalendarId(long tenantId) {
        Long calendarId = inspectionCalendarMapper.findDefaultCalendarId(tenantId);
        if (calendarId == null) {
            throw new BusinessException(
                    "INSPECTION_WORK_CALENDAR_REQUIRED",
                    "导入点检方案前请先设置默认点检工作日历"
            );
        }
        return calendarId;
    }

    private List<InspectionImportDtos.ItemInput> parseItems(
            Sheet sheet,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Map<String, Integer> columns = columns(sheet, ITEM_SHEET, ITEM_HEADERS, errors);
        if (columns.isEmpty()) {
            return List.of();
        }
        List<InspectionImportDtos.ItemInput> rows = new ArrayList<>();
        eachRow(sheet, (row, formatter) -> {
            try {
                rows.add(new InspectionImportDtos.ItemInput(
                        row.getRowNum() + 1,
                        required(row, columns, formatter, "项目编码").toUpperCase(Locale.ROOT),
                        required(row, columns, formatter, "项目名称"),
                        value(row, columns, formatter, "项目分类", "OPERATION")
                                .toUpperCase(Locale.ROOT),
                        optional(row, columns, formatter, "点检部位"),
                        required(row, columns, formatter, "点检内容"),
                        optional(row, columns, formatter, "点检方法"),
                        optional(row, columns, formatter, "点检工具"),
                        required(row, columns, formatter, "点检标准"),
                        optional(row, columns, formatter, "标准值"),
                        decimal(row, columns, formatter, "下限"),
                        decimal(row, columns, formatter, "上限"),
                        optional(row, columns, formatter, "单位"),
                        required(row, columns, formatter, "结果类型").toUpperCase(Locale.ROOT),
                        split(optional(row, columns, formatter, "结果选项"), "[,，;；|\\n]"),
                        bool(row, columns, formatter, "必填", true),
                        bool(row, columns, formatter, "必拍", false),
                        bool(row, columns, formatter, "必须数值", false),
                        bool(row, columns, formatter, "允许跳过", false),
                        value(row, columns, formatter, "异常等级", "MEDIUM")
                                .toUpperCase(Locale.ROOT),
                        optional(row, columns, formatter, "异常建议"),
                        integer(row, columns, formatter, "标准分钟", 5),
                        optional(row, columns, formatter, "安全说明"),
                        bool(row, columns, formatter, "启用", true),
                        optional(row, columns, formatter, "描述")
                ));
            } catch (RowError exception) {
                errors.add(error(ITEM_SHEET, row.getRowNum() + 1,
                        exception.column(), exception.getMessage()));
            }
        });
        return rows;
    }

    private List<InspectionImportDtos.SchemeInput> parseSchemes(
            Sheet sheet,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Map<String, Integer> columns = columns(
                sheet, SCHEME_SHEET, SCHEME_HEADERS, errors
        );
        if (columns.isEmpty()) {
            return List.of();
        }
        List<InspectionImportDtos.SchemeInput> rows = new ArrayList<>();
        eachRow(sheet, (row, formatter) -> {
            try {
                rows.add(new InspectionImportDtos.SchemeInput(
                        row.getRowNum() + 1,
                        required(row, columns, formatter, "方案编码").toUpperCase(Locale.ROOT),
                        required(row, columns, formatter, "方案名称"),
                        value(row, columns, formatter, "点检类型", "DAILY")
                                .toUpperCase(Locale.ROOT),
                        required(row, columns, formatter, "周期类型").toUpperCase(Locale.ROOT),
                        integer(row, columns, formatter, "周期间隔", 1),
                        optional(row, columns, formatter, "星期"),
                        optional(row, columns, formatter, "月日期"),
                        time(row, columns, formatter, "计划时间"),
                        upper(optional(row, columns, formatter, "班次编码")),
                        optional(row, columns, formatter, "默认执行人账号"),
                        upper(optional(row, columns, formatter, "默认班组编码")),
                        bool(row, columns, formatter, "需要复核", true),
                        bool(row, columns, formatter, "允许补录", true),
                        date(row, columns, formatter, "生效日期", true),
                        date(row, columns, formatter, "失效日期", false),
                        bool(row, columns, formatter, "启用", true),
                        optional(row, columns, formatter, "描述"),
                        optional(row, columns, formatter, "变更说明")
                ));
            } catch (RowError exception) {
                errors.add(error(SCHEME_SHEET, row.getRowNum() + 1,
                        exception.column(), exception.getMessage()));
            }
        });
        return rows;
    }

    private List<InspectionImportDtos.SchemeItemInput> parseSchemeItems(
            Sheet sheet,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Map<String, Integer> columns = columns(
                sheet, SCHEME_ITEM_SHEET, SCHEME_ITEM_HEADERS, errors
        );
        if (columns.isEmpty()) {
            return List.of();
        }
        List<InspectionImportDtos.SchemeItemInput> rows = new ArrayList<>();
        eachRow(sheet, (row, formatter) -> {
            try {
                rows.add(new InspectionImportDtos.SchemeItemInput(
                        row.getRowNum() + 1,
                        required(row, columns, formatter, "方案编码").toUpperCase(Locale.ROOT),
                        required(row, columns, formatter, "项目编码").toUpperCase(Locale.ROOT),
                        integer(row, columns, formatter, "顺序", 10),
                        nullableBool(row, columns, formatter, "必填覆盖"),
                        nullableBool(row, columns, formatter, "必拍覆盖"),
                        nullableBool(row, columns, formatter, "允许跳过覆盖")
                ));
            } catch (RowError exception) {
                errors.add(error(SCHEME_ITEM_SHEET, row.getRowNum() + 1,
                        exception.column(), exception.getMessage()));
            }
        });
        return rows;
    }

    private List<InspectionImportDtos.ApplicabilityInput> parseApplicability(
            Sheet sheet,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Map<String, Integer> columns = columns(
                sheet, APPLICABILITY_SHEET, APPLICABILITY_HEADERS, errors
        );
        if (columns.isEmpty()) {
            return List.of();
        }
        List<InspectionImportDtos.ApplicabilityInput> rows = new ArrayList<>();
        eachRow(sheet, (row, formatter) -> {
            try {
                rows.add(new InspectionImportDtos.ApplicabilityInput(
                        row.getRowNum() + 1,
                        required(row, columns, formatter, "方案编码").toUpperCase(Locale.ROOT),
                        upper(optional(row, columns, formatter, "设备编码")),
                        upper(optional(row, columns, formatter, "分类编码"))
                ));
            } catch (RowError exception) {
                errors.add(error(APPLICABILITY_SHEET, row.getRowNum() + 1,
                        exception.column(), exception.getMessage()));
            }
        });
        return rows;
    }

    private void validateSemantics(long tenantId, ParsedWorkbook parsed) {
        InspectionImportDtos.ImportPayload payload = parsed.payload();
        List<InspectionImportDtos.ImportError> errors = parsed.errors();
        DataPermission scope = dataPermissionService.current();
        Set<String> itemCodes = validateItemRows(payload.items(), errors);
        Set<String> schemeCodes = validateSchemeRows(
                tenantId, payload.schemes(), errors
        );
        validateSchemeItemRows(
                tenantId, payload.schemeItems(), itemCodes, schemeCodes, errors
        );
        validateApplicabilityRows(
                tenantId, payload.applicability(), schemeCodes, scope, errors
        );

        Map<String, Long> itemCounts = payload.schemeItems().stream().collect(
                Collectors.groupingBy(
                        InspectionImportDtos.SchemeItemInput::schemeCode,
                        Collectors.counting()
                )
        );
        Map<String, Long> scopeCounts = payload.applicability().stream().collect(
                Collectors.groupingBy(
                        InspectionImportDtos.ApplicabilityInput::schemeCode,
                        Collectors.counting()
                )
        );
        for (InspectionImportDtos.SchemeInput scheme : payload.schemes()) {
            if (itemCounts.getOrDefault(scheme.schemeCode(), 0L) == 0L) {
                errors.add(error(SCHEME_SHEET, scheme.rowNumber(), "方案编码",
                        "方案至少需要一个方案项目"));
            }
            if (scopeCounts.getOrDefault(scheme.schemeCode(), 0L) == 0L) {
                errors.add(error(SCHEME_SHEET, scheme.rowNumber(), "方案编码",
                        "方案至少需要一个适用设备或分类"));
            }
        }
    }

    private Set<String> validateItemRows(
            List<InspectionImportDtos.ItemInput> rows,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Set<String> codes = new HashSet<>();
        for (InspectionImportDtos.ItemInput row : rows) {
            if (!row.itemCode().matches("^[A-Z][A-Z0-9_-]{0,63}$")) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "项目编码", "项目编码格式不正确"));
            }
            if (!codes.add(row.itemCode())) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "项目编码", "项目编码在文件中重复"));
            }
            if (!RESULT_TYPES.contains(row.resultType())) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "结果类型", "结果类型不正确"));
            }
            if (CHOICE_TYPES.contains(row.resultType()) && row.resultOptions().isEmpty()) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "结果选项", "选择类型必须填写结果选项"));
            }
            if (row.minimumValue() != null && row.maximumValue() != null
                    && row.minimumValue().compareTo(row.maximumValue()) > 0) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "下限", "下限不能大于上限"));
            }
            if (!SEVERITIES.contains(row.abnormalSeverity())) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "异常等级", "异常等级不正确"));
            }
            if (row.standardMinutes() < 0) {
                errors.add(error(ITEM_SHEET, row.rowNumber(), "标准分钟", "标准分钟不能小于 0"));
            }
        }
        return codes;
    }

    private Set<String> validateSchemeRows(
            long tenantId,
            List<InspectionImportDtos.SchemeInput> rows,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Set<String> codes = new HashSet<>();
        for (InspectionImportDtos.SchemeInput row : rows) {
            if (!row.schemeCode().matches("^[A-Z][A-Z0-9_-]{0,63}$")) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "方案编码", "方案编码格式不正确"));
            }
            if (!codes.add(row.schemeCode())) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "方案编码", "方案编码在文件中重复"));
            }
            if (!CYCLE_TYPES.contains(row.cycleType())) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "周期类型", "周期类型不正确"));
            }
            if (row.cycleInterval() < 1) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "周期间隔", "周期间隔必须大于 0"));
            }
            if ("WEEKLY".equals(row.cycleType()) && row.weekDays() == null) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "星期", "周计划必须填写星期"));
            }
            if ("MONTHLY".equals(row.cycleType()) && row.monthDays() == null) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "月日期", "月计划必须填写月日期"));
            }
            if (row.expiryDate() != null && row.effectiveDate().isAfter(row.expiryDate())) {
                errors.add(error(SCHEME_SHEET, row.rowNumber(), "失效日期", "失效日期不能早于生效日期"));
            }
            if (row.defaultAssigneeUsername() != null) {
                EquipmentMapper.UserLookup user = equipmentMapper.findUserByUsername(
                        tenantId, row.defaultAssigneeUsername()
                );
                if (user == null || user.status() != 1) {
                    errors.add(error(SCHEME_SHEET, row.rowNumber(), "默认执行人账号",
                            "默认执行人不存在或已停用"));
                }
            }
            if (row.defaultTeamCode() != null) {
                EquipmentMapper.LookupRow team = equipmentMapper.findOrganizationByCode(
                        tenantId, row.defaultTeamCode()
                );
                MasterDataDtos.OrganizationRow organization = team == null
                        ? null : masterDataMapper.findOrganization(tenantId, team.id());
                if (team == null || team.status() != 1 || organization == null
                        || !"TEAM".equals(organization.organizationType())) {
                    errors.add(error(SCHEME_SHEET, row.rowNumber(), "默认班组编码",
                            "默认班组不存在、不是班组或已停用"));
                }
            }
        }
        return codes;
    }

    private void validateSchemeItemRows(
            long tenantId,
            List<InspectionImportDtos.SchemeItemInput> rows,
            Set<String> importedItemCodes,
            Set<String> importedSchemeCodes,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Set<String> relations = new HashSet<>();
        for (InspectionImportDtos.SchemeItemInput row : rows) {
            if (!importedSchemeCodes.contains(row.schemeCode())) {
                errors.add(error(SCHEME_ITEM_SHEET, row.rowNumber(), "方案编码",
                        "方案编码未在点检方案工作表中定义"));
            }
            if (!importedItemCodes.contains(row.itemCode())
                    && inspectionMapper.findItemIdByCode(tenantId, row.itemCode()) == null) {
                errors.add(error(SCHEME_ITEM_SHEET, row.rowNumber(), "项目编码",
                        "项目编码不存在"));
            }
            if (!relations.add(row.schemeCode() + "\u0000" + row.itemCode())) {
                errors.add(error(SCHEME_ITEM_SHEET, row.rowNumber(), "项目编码",
                        "同一方案不能重复引用项目"));
            }
            if (row.sortOrder() < 0) {
                errors.add(error(SCHEME_ITEM_SHEET, row.rowNumber(), "顺序", "顺序不能小于 0"));
            }
        }
    }

    private void validateApplicabilityRows(
            long tenantId,
            List<InspectionImportDtos.ApplicabilityInput> rows,
            Set<String> importedSchemeCodes,
            DataPermission scope,
            List<InspectionImportDtos.ImportError> errors
    ) {
        Set<String> relations = new HashSet<>();
        for (InspectionImportDtos.ApplicabilityInput row : rows) {
            if (!importedSchemeCodes.contains(row.schemeCode())) {
                errors.add(error(APPLICABILITY_SHEET, row.rowNumber(), "方案编码",
                        "方案编码未在点检方案工作表中定义"));
            }
            boolean equipment = row.equipmentCode() != null;
            boolean category = row.categoryCode() != null;
            if (equipment == category) {
                errors.add(error(APPLICABILITY_SHEET, row.rowNumber(), "设备编码",
                        "设备编码和分类编码必须且只能填写一个"));
                continue;
            }
            if (equipment) {
                Long id = equipmentMapper.findEquipmentIdByCode(
                        tenantId, row.equipmentCode()
                );
                if (id == null || inspectionMapper.countActiveEquipment(
                        tenantId, id, scope
                ) == 0) {
                    errors.add(error(APPLICABILITY_SHEET, row.rowNumber(), "设备编码",
                            "设备不存在、已停用或无权访问"));
                }
            } else {
                EquipmentMapper.LookupRow lookup = equipmentMapper.findCategoryByCode(
                        tenantId, row.categoryCode()
                );
                if (lookup == null || lookup.status() != 1) {
                    errors.add(error(APPLICABILITY_SHEET, row.rowNumber(), "分类编码",
                            "设备分类不存在或已停用"));
                }
            }
            String key = row.schemeCode() + "\u0000"
                    + (equipment ? "E:" + row.equipmentCode() : "C:" + row.categoryCode());
            if (!relations.add(key)) {
                errors.add(error(APPLICABILITY_SHEET, row.rowNumber(), "方案编码",
                        "适用范围关系重复"));
            }
        }
    }

    private InspectionImportDtos.ImportCounts predictedCounts(
            long tenantId,
            InspectionImportDtos.ImportPayload payload
    ) {
        int newItems = 0;
        int updatedItems = 0;
        for (InspectionImportDtos.ItemInput row : payload.items()) {
            if (inspectionMapper.findItemIdByCode(tenantId, row.itemCode()) == null) {
                newItems++;
            } else {
                updatedItems++;
            }
        }
        int newSchemes = 0;
        int versions = 0;
        for (InspectionImportDtos.SchemeInput row : payload.schemes()) {
            if (inspectionMapper.findSchemeIdByCode(tenantId, row.schemeCode()) == null) {
                newSchemes++;
            } else {
                versions++;
            }
        }
        return new InspectionImportDtos.ImportCounts(
                newItems, updatedItems, newSchemes, versions
        );
    }

    private InspectionImportDtos.BatchRow findBatch(
            long tenantId,
            long userId,
            String batchId,
            boolean lock
    ) {
        if (batchId == null || !batchId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new BusinessException("INSPECTION_IMPORT_BATCH_INVALID", "导入批次编号不正确");
        }
        String sql = """
                SELECT batch_code, import_status, CAST(payload_json AS CHAR),
                       CAST(errors_json AS CHAR), CAST(result_json AS CHAR),
                       item_rows, scheme_rows, relation_rows, committed_time
                FROM inspection_import_batch
                WHERE tenant_id = ? AND created_by = ? AND batch_code = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<InspectionImportDtos.BatchRow> rows = jdbc.query(
                sql,
                (resultSet, rowNumber) -> new InspectionImportDtos.BatchRow(
                        resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getString(5), resultSet.getInt(6),
                        resultSet.getInt(7), resultSet.getInt(8),
                        resultSet.getTimestamp(9) == null
                                ? null : resultSet.getTimestamp(9).toLocalDateTime()
                ),
                tenantId, userId, batchId
        );
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "INSPECTION_IMPORT_BATCH_NOT_FOUND", "导入批次不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return rows.getFirst();
    }

    private InspectionImportDtos.ImportResult rowResult(
            InspectionImportDtos.BatchRow row
    ) {
        InspectionImportDtos.ImportPayload payload = read(
                row.payloadJson(), InspectionImportDtos.ImportPayload.class
        );
        List<InspectionImportDtos.ImportError> errors = row.errorsJson() == null
                ? List.of() : read(
                        row.errorsJson(), new TypeReference<List<InspectionImportDtos.ImportError>>() {
                        }
                );
        InspectionImportDtos.ImportCounts counts = row.resultJson() == null
                ? predictedCounts(SecurityUtils.currentUser().tenantId(), payload)
                : read(row.resultJson(), InspectionImportDtos.ImportCounts.class);
        return result(
                row.batchId(), row.status(), payload, counts, errors, row.committedTime()
        );
    }

    private InspectionImportDtos.ImportResult result(
            String batchId,
            String status,
            InspectionImportDtos.ImportPayload payload,
            InspectionImportDtos.ImportCounts counts,
            List<InspectionImportDtos.ImportError> errors,
            LocalDateTime committedTime
    ) {
        return new InspectionImportDtos.ImportResult(
                batchId, status, payload.items().size(), payload.schemes().size(),
                payload.schemeItems().size() + payload.applicability().size(),
                counts.newItems(), counts.updatedItems(), counts.newSchemes(),
                counts.newSchemeVersions(), List.copyOf(errors), committedTime
        );
    }

    private Map<String, Integer> columns(
            Sheet sheet,
            String sheetName,
            List<String> requiredHeaders,
            List<InspectionImportDtos.ImportError> errors
    ) {
        if (sheet == null) {
            errors.add(error(sheetName, 0, null, "缺少工作表：" + sheetName));
            return Map.of();
        }
        Row header = sheet.getRow(0);
        if (header == null) {
            errors.add(error(sheetName, 1, null, "工作表缺少表头"));
            return Map.of();
        }
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            String name = formatter.formatCellValue(header.getCell(index)).trim();
            if (!name.isEmpty()) {
                columns.put(name, index);
            }
        }
        boolean missing = false;
        for (String required : requiredHeaders) {
            if (!columns.containsKey(required)) {
                errors.add(error(sheetName, 1, required, "缺少模板列：" + required));
                missing = true;
            }
        }
        return missing ? Map.of() : columns;
    }

    private void eachRow(Sheet sheet, RowConsumer consumer) {
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row != null && !blank(row, formatter)) {
                consumer.accept(row, formatter);
            }
        }
    }

    private boolean blank(Row row, DataFormatter formatter) {
        for (int index = row.getFirstCellNum(); index < row.getLastCellNum(); index++) {
            if (index >= 0 && !formatter.formatCellValue(row.getCell(index)).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String required(
            Row row, Map<String, Integer> columns, DataFormatter formatter, String column
    ) {
        String value = optional(row, columns, formatter, column);
        if (value == null) {
            throw new RowError(column, "不能为空");
        }
        return value;
    }

    private String value(
            Row row, Map<String, Integer> columns, DataFormatter formatter,
            String column, String defaultValue
    ) {
        String value = optional(row, columns, formatter, column);
        return value == null ? defaultValue : value;
    }

    private String optional(
            Row row, Map<String, Integer> columns, DataFormatter formatter, String column
    ) {
        String value = formatter.formatCellValue(row.getCell(columns.get(column))).trim();
        return value.isEmpty() ? null : value;
    }

    private BigDecimal decimal(
            Row row, Map<String, Integer> columns, DataFormatter formatter, String column
    ) {
        String value = optional(row, columns, formatter, column);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            throw new RowError(column, "必须是数字");
        }
    }

    private int integer(
            Row row, Map<String, Integer> columns, DataFormatter formatter,
            String column, int defaultValue
    ) {
        BigDecimal value = decimal(row, columns, formatter, column);
        if (value == null) {
            return defaultValue;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw new RowError(column, "必须是整数");
        }
    }

    private boolean bool(
            Row row, Map<String, Integer> columns, DataFormatter formatter,
            String column, boolean defaultValue
    ) {
        Boolean value = nullableBool(row, columns, formatter, column);
        return value == null ? defaultValue : value;
    }

    private Boolean nullableBool(
            Row row, Map<String, Integer> columns, DataFormatter formatter, String column
    ) {
        String value = optional(row, columns, formatter, column);
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "是", "Y", "YES", "TRUE", "1" -> true;
            case "否", "N", "NO", "FALSE", "0" -> false;
            default -> throw new RowError(column, "请填写是或否");
        };
    }

    private LocalDate date(
            Row row, Map<String, Integer> columns, DataFormatter formatter,
            String column, boolean required
    ) {
        String value = optional(row, columns, formatter, column);
        if (value == null) {
            if (required) {
                throw new RowError(column, "不能为空");
            }
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new RowError(column, "日期格式应为 yyyy-MM-dd");
        }
    }

    private LocalTime time(
            Row row, Map<String, Integer> columns, DataFormatter formatter, String column
    ) {
        String value = optional(row, columns, formatter, column);
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value.length() == 5 ? value + ":00" : value);
        } catch (DateTimeParseException exception) {
            throw new RowError(column, "时间格式应为 HH:mm");
        }
    }

    private List<String> split(String value, String pattern) {
        if (value == null) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(pattern))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .distinct()
                .toList();
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private void addSheet(
            Workbook workbook,
            String name,
            List<String> headers,
            List<String> example,
            CellStyle headerStyle
    ) {
        Sheet sheet = workbook.createSheet(name);
        WorkbookSupport.writeHeader(sheet, headers, headerStyle);
        Row row = sheet.createRow(1);
        for (int index = 0; index < example.size(); index++) {
            row.createCell(index).setCellValue(example.get(index));
        }
        sheet.createFreezePane(0, 1);
        for (int index = 0; index < headers.size(); index++) {
            sheet.autoSizeColumn(index);
            sheet.setColumnWidth(index, Math.min(sheet.getColumnWidth(index) + 512, 10_000));
        }
    }

    private String safeFileName(String fileName) {
        String value = fileName == null ? "inspection-import.xlsx" : fileName;
        value = value.replace('\\', '_').replace('/', '_').replace("..", "_");
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private String sha256(MultipartFile file) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(file.getBytes())
            );
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new BusinessException("IMPORT_FILE_INVALID", "无法读取导入文件");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "INSPECTION_IMPORT_SERIALIZE_FAILED", "点检导入数据序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw invalidStoredBatch();
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw invalidStoredBatch();
        }
    }

    private BusinessException invalidStoredBatch() {
        return new BusinessException(
                "INSPECTION_IMPORT_BATCH_CORRUPTED", "导入批次数据损坏",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private InspectionImportDtos.ImportError error(
            String sheet, int row, String column, String message
    ) {
        return new InspectionImportDtos.ImportError(sheet, row, column, message);
    }

    private record ParsedWorkbook(
            InspectionImportDtos.ImportPayload payload,
            List<InspectionImportDtos.ImportError> errors
    ) {
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(Row row, DataFormatter formatter);
    }

    private static final class RowError extends RuntimeException {
        private final String column;

        private RowError(String column, String message) {
            super(message);
            this.column = column;
        }

        private String column() {
            return column;
        }
    }

    private static final class WorkbookSupport {
        private WorkbookSupport() {
        }

        private static CellStyle headerStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(
                    org.apache.poi.ss.usermodel.IndexedColors.DARK_TEAL.getIndex()
            );
            style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            font.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            style.setFont(font);
            return style;
        }

        private static void writeHeader(
                Sheet sheet, List<String> headers, CellStyle style
        ) {
            Row row = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                var cell = row.createCell(index);
                cell.setCellValue(headers.get(index));
                cell.setCellStyle(style);
            }
        }
    }
}
