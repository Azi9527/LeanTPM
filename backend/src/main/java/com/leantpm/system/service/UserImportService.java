package com.leantpm.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.dto.UserImportDtos;
import com.leantpm.system.mapper.SystemMapper;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserImportService {
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final int MAX_ROWS = 1_000;
    private static final String SHEET_NAME = "用户导入";
    private static final List<String> HEADERS = List.of(
            "账号", "姓名", "工号", "手机号", "邮箱", "组织编码", "角色编码列表",
            "允许移动端", "初始密码", "处理策略"
    );
    private static final Set<String> STRATEGIES = Set.of("ADD_ONLY", "ADD_UPDATE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SystemMapper mapper;
    private final SystemService systemService;
    private final DataPermissionService dataPermissionService;
    private final ChangeLogService changeLogService;

    public UserImportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SystemMapper mapper,
            SystemService systemService,
            DataPermissionService dataPermissionService,
            ChangeLogService changeLogService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.systemService = systemService;
        this.dataPermissionService = dataPermissionService;
        this.changeLogService = changeLogService;
    }

    @Transactional(readOnly = true)
    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook();
             var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);
            var style = workbook.createCellStyle();
            style.setFillForegroundColor(
                    org.apache.poi.ss.usermodel.IndexedColors.DARK_TEAL.getIndex()
            );
            style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            font.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            style.setFont(font);
            for (int index = 0; index < HEADERS.size(); index++) {
                var cell = header.createCell(index);
                cell.setCellValue(HEADERS.get(index));
                cell.setCellStyle(style);
            }
            Row example = sheet.createRow(1);
            List<String> values = List.of(
                    "operator06", "操作工06", "OP-006", "13800000006",
                    "operator06@example.com", "TEAM-A-1", "OPERATOR",
                    "是", "888888", "仅新增"
            );
            for (int index = 0; index < values.size(); index++) {
                example.createCell(index).setCellValue(values.get(index));
            }
            sheet.createFreezePane(0, 1);
            for (int index = 0; index < HEADERS.size(); index++) {
                sheet.autoSizeColumn(index);
                sheet.setColumnWidth(index, Math.min(sheet.getColumnWidth(index) + 512, 8_000));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(
                    "USER_IMPORT_TEMPLATE_FAILED", "用户导入模板生成失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    @Transactional
    public UserImportDtos.ImportResult validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("IMPORT_FILE_EMPTY", "请选择要导入的用户 Excel 文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException("IMPORT_FILE_TOO_LARGE", "用户导入文件不能超过 5 MB");
        }
        var current = SecurityUtils.currentUser();
        Parsed parsed = parse(file);
        Validated validated = validateRows(
                current.tenantId(), parsed.rows(), parsed.errors()
        );
        String status = validated.rows().isEmpty()
                ? "INVALID"
                : validated.errors().isEmpty() ? "VALIDATED" : "VALIDATED_WITH_ERRORS";
        String strategy = validated.rows().stream()
                .map(UserImportDtos.UserInput::strategy)
                .distinct()
                .count() > 1 ? "MIXED" : validated.rows().stream()
                .map(UserImportDtos.UserInput::strategy)
                .findFirst().orElse("MIXED");
        String batchId = UUID.randomUUID().toString();
        UserImportDtos.ImportPayload payload = new UserImportDtos.ImportPayload(
                validated.rows()
        );
        UserImportDtos.ImportCounts counts = predictedCounts(
                current.tenantId(), validated.rows()
        );
        jdbc.update("""
                INSERT INTO system_user_import_batch
                    (tenant_id, batch_code, file_name, file_sha256, import_status,
                     import_strategy, payload_json, errors_json, total_rows, created_by)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?)
                """,
                current.tenantId(), batchId, safeFileName(file.getOriginalFilename()),
                sha256(file), status, strategy, json(payload), json(validated.errors()),
                parsed.totalRows(), current.userId()
        );
        return result(
                batchId, status, strategy, parsed.totalRows(), payload, counts,
                validated.errors(), null
        );
    }

    @Transactional(readOnly = true)
    public UserImportDtos.ImportResult batch(String batchId) {
        var current = SecurityUtils.currentUser();
        return rowResult(findBatch(
                current.tenantId(), current.userId(), batchId, false
        ));
    }

    @Transactional
    public UserImportDtos.ImportResult commit(String batchId) {
        var current = SecurityUtils.currentUser();
        UserImportDtos.BatchRow batch = findBatch(
                current.tenantId(), current.userId(), batchId, true
        );
        if ("COMMITTED".equals(batch.status())) {
            return rowResult(batch);
        }
        if (!Set.of("VALIDATED", "VALIDATED_WITH_ERRORS").contains(batch.status())) {
            throw new BusinessException(
                    "USER_IMPORT_NOT_VALID", "用户导入批次没有可导入的数据",
                    HttpStatus.CONFLICT
            );
        }
        UserImportDtos.ImportPayload payload = read(
                batch.payloadJson(), UserImportDtos.ImportPayload.class
        );
        UserImportDtos.ImportCounts counts = apply(
                current.tenantId(), payload.users()
        );
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime committedTime = now.withNano(
                (now.getNano() / 1_000_000) * 1_000_000
        );
        int changed = jdbc.update("""
                UPDATE system_user_import_batch
                SET import_status = 'COMMITTED', result_json = CAST(? AS JSON),
                    committed_by = ?, committed_time = ?
                WHERE tenant_id = ? AND batch_code = ? AND created_by = ?
                  AND import_status IN ('VALIDATED', 'VALIDATED_WITH_ERRORS')
                """,
                json(counts), current.userId(), committedTime, current.tenantId(),
                batchId, current.userId()
        );
        if (changed != 1) {
            throw new BusinessException(
                    "USER_IMPORT_STATE_CONFLICT", "用户导入批次状态已变化",
                    HttpStatus.CONFLICT
            );
        }
        changeLogService.record(
                "SYSTEM_USER_IMPORT", batchId, "COMMIT", null,
                Map.of("totalRows", batch.totalRows(), "validRows", payload.users().size(),
                        "counts", counts)
        );
        List<UserImportDtos.ImportError> errors = readErrors(batch.errorsJson());
        return result(
                batchId, "COMMITTED", batch.strategy(), batch.totalRows(), payload,
                counts, errors, committedTime
        );
    }

    private UserImportDtos.ImportCounts apply(
            long tenantId,
            List<UserImportDtos.UserInput> rows
    ) {
        Map<String, SystemDtos.OrganizationNode> organizations = organizationsByCode();
        Map<String, SystemDtos.RoleRow> roles = rolesByCode();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (UserImportDtos.UserInput row : rows) {
            SystemDtos.UserRow existing = mapper.findUserByUsername(
                    tenantId, row.username()
            );
            if (existing != null && "ADD_ONLY".equals(row.strategy())) {
                skipped++;
                continue;
            }
            long organizationId = organizations.get(row.organizationCode()).id();
            List<Long> roleIds = row.roleCodes().stream()
                    .map(code -> roles.get(code).id())
                    .toList();
            if (existing == null) {
                systemService.createUser(new SystemDtos.CreateUserRequest(
                        row.username(), row.realName(), row.employeeNo(), row.mobile(),
                        row.email(), organizationId, row.mobileEnabled(), roleIds,
                        row.initialPassword()
                ));
                created++;
            } else {
                systemService.updateUser(existing.id(), new SystemDtos.UpdateUserRequest(
                        row.realName(), row.employeeNo(), row.mobile(), row.email(),
                        organizationId, row.mobileEnabled(), roleIds, existing.version()
                ));
                updated++;
            }
        }
        return new UserImportDtos.ImportCounts(created, updated, skipped);
    }

    private Parsed parse(MultipartFile file) {
        List<UserImportDtos.ImportError> errors = new ArrayList<>();
        List<UserImportDtos.UserInput> rows = new ArrayList<>();
        int totalRows = 0;
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw new BusinessException("USER_IMPORT_SHEET_MISSING", "缺少“用户导入”工作表");
            }
            Map<String, Integer> columns = columns(sheet.getRow(0));
            for (String header : HEADERS) {
                if (!columns.containsKey(header)) {
                    throw new BusinessException(
                            "USER_IMPORT_HEADER_INVALID", "缺少模板列：" + header
                    );
                }
            }
            DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row, formatter)) {
                    continue;
                }
                totalRows++;
                try {
                    rows.add(new UserImportDtos.UserInput(
                            index + 1,
                            required(row, columns, formatter, "账号"),
                            required(row, columns, formatter, "姓名"),
                            optional(row, columns, formatter, "工号"),
                            optional(row, columns, formatter, "手机号"),
                            optional(row, columns, formatter, "邮箱"),
                            upper(required(row, columns, formatter, "组织编码")),
                            splitRoles(required(row, columns, formatter, "角色编码列表")),
                            bool(row, columns, formatter, "允许移动端", true),
                            optional(row, columns, formatter, "初始密码"),
                            strategy(required(row, columns, formatter, "处理策略"))
                    ));
                } catch (RowError exception) {
                    errors.add(new UserImportDtos.ImportError(
                            index + 1, exception.column(), exception.getMessage()
                    ));
                }
            }
            if (totalRows > MAX_ROWS) {
                throw new BusinessException("USER_IMPORT_ROW_LIMIT", "单次最多导入 1000 个用户");
            }
            return new Parsed(rows, errors, totalRows);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(
                    "USER_IMPORT_FILE_INVALID", "无法读取用户 Excel 文件，请使用系统模板"
            );
        }
    }

    private Validated validateRows(
            long tenantId,
            List<UserImportDtos.UserInput> rows,
            List<UserImportDtos.ImportError> parseErrors
    ) {
        List<UserImportDtos.ImportError> errors = new ArrayList<>(parseErrors);
        List<UserImportDtos.UserInput> valid = new ArrayList<>();
        Map<String, SystemDtos.OrganizationNode> organizations = organizationsByCode();
        Map<String, SystemDtos.RoleRow> roles = rolesByCode();
        DataPermission scope = dataPermissionService.current();
        Set<String> usernames = new HashSet<>();
        for (UserImportDtos.UserInput row : rows) {
            List<UserImportDtos.ImportError> rowErrors = new ArrayList<>();
            if (!row.username().matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")) {
                rowErrors.add(error(row, "账号", "账号格式不正确"));
            }
            if (!usernames.add(row.username().toLowerCase(Locale.ROOT))) {
                rowErrors.add(error(row, "账号", "账号在文件中重复"));
            }
            SystemDtos.OrganizationNode organization = organizations.get(
                    row.organizationCode()
            );
            if (organization == null || organization.status() != 1) {
                rowErrors.add(error(row, "组织编码", "组织不存在或已停用"));
            } else if (!scope.canCreateIn(organization.id())) {
                rowErrors.add(error(row, "组织编码", "无权在该组织创建或更新用户"));
            }
            if (row.roleCodes().isEmpty()) {
                rowErrors.add(error(row, "角色编码列表", "至少填写一个角色编码"));
            }
            for (String roleCode : row.roleCodes()) {
                SystemDtos.RoleRow role = roles.get(roleCode);
                if (role == null || role.status() != 1) {
                    rowErrors.add(error(row, "角色编码列表", "角色不存在或已停用：" + roleCode));
                }
            }
            if (row.mobile() != null && !row.mobile().matches("^[0-9+\\-]{6,32}$")) {
                rowErrors.add(error(row, "手机号", "手机号格式不正确"));
            }
            if (row.email() != null
                    && !row.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                rowErrors.add(error(row, "邮箱", "邮箱格式不正确"));
            }
            SystemDtos.UserRow existing = mapper.findUserByUsername(
                    tenantId, row.username()
            );
            if (existing == null && (row.initialPassword() == null
                    || row.initialPassword().length() < 6)) {
                rowErrors.add(error(row, "初始密码", "新增用户的初始密码至少 6 位"));
            }
            if (existing != null && "ADD_UPDATE".equals(row.strategy())) {
                SystemMapper.UserScopeTarget target = mapper.findUserScopeTarget(
                        tenantId, existing.id()
                );
                if (target == null || !scope.canAccess(target.id(), target.organizationId())) {
                    rowErrors.add(error(row, "账号", "无权更新该用户"));
                }
            }
            if (row.realName().length() > 100) {
                rowErrors.add(error(row, "姓名", "姓名不能超过 100 个字符"));
            }
            if (rowErrors.isEmpty()) {
                valid.add(row);
            } else {
                errors.addAll(rowErrors);
            }
        }
        return new Validated(valid, errors);
    }

    private UserImportDtos.ImportCounts predictedCounts(
            long tenantId,
            List<UserImportDtos.UserInput> rows
    ) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (UserImportDtos.UserInput row : rows) {
            boolean exists = mapper.findUserByUsername(tenantId, row.username()) != null;
            if (!exists) {
                created++;
            } else if ("ADD_UPDATE".equals(row.strategy())) {
                updated++;
            } else {
                skipped++;
            }
        }
        return new UserImportDtos.ImportCounts(created, updated, skipped);
    }

    private Map<String, SystemDtos.OrganizationNode> organizationsByCode() {
        return systemService.organizations().stream()
                .collect(Collectors.toMap(
                        row -> upper(row.organizationCode()), row -> row,
                        (first, second) -> first, LinkedHashMap::new
                ));
    }

    private Map<String, SystemDtos.RoleRow> rolesByCode() {
        return systemService.roles().stream()
                .collect(Collectors.toMap(
                        row -> upper(row.roleCode()), row -> row,
                        (first, second) -> first, LinkedHashMap::new
                ));
    }

    private UserImportDtos.BatchRow findBatch(
            long tenantId, long userId, String batchId, boolean lock
    ) {
        if (batchId == null || !batchId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new BusinessException("USER_IMPORT_BATCH_INVALID", "用户导入批次编号不正确");
        }
        String sql = """
                SELECT batch_code, import_status, import_strategy,
                       CAST(payload_json AS CHAR), CAST(errors_json AS CHAR),
                       CAST(result_json AS CHAR), total_rows, committed_time
                FROM system_user_import_batch
                WHERE tenant_id = ? AND created_by = ? AND batch_code = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<UserImportDtos.BatchRow> rows = jdbc.query(
                sql,
                (rs, rowNumber) -> new UserImportDtos.BatchRow(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7),
                        rs.getTimestamp(8) == null ? null
                                : rs.getTimestamp(8).toLocalDateTime()
                ),
                tenantId, userId, batchId
        );
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "USER_IMPORT_BATCH_NOT_FOUND", "用户导入批次不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return rows.getFirst();
    }

    private UserImportDtos.ImportResult rowResult(UserImportDtos.BatchRow row) {
        UserImportDtos.ImportPayload payload = read(
                row.payloadJson(), UserImportDtos.ImportPayload.class
        );
        UserImportDtos.ImportCounts counts = row.resultJson() == null
                ? predictedCounts(SecurityUtils.currentUser().tenantId(), payload.users())
                : read(row.resultJson(), UserImportDtos.ImportCounts.class);
        return result(
                row.batchId(), row.status(), row.strategy(), row.totalRows(), payload,
                counts, readErrors(row.errorsJson()), row.committedTime()
        );
    }

    private UserImportDtos.ImportResult result(
            String batchId,
            String status,
            String strategy,
            int totalRows,
            UserImportDtos.ImportPayload payload,
            UserImportDtos.ImportCounts counts,
            List<UserImportDtos.ImportError> errors,
            LocalDateTime committedTime
    ) {
        return new UserImportDtos.ImportResult(
                batchId, status, strategy, totalRows, payload.users().size(),
                counts.newUsers(), counts.updatedUsers(), counts.skippedUsers(),
                List.copyOf(errors), committedTime
        );
    }

    private Map<String, Integer> columns(Row row) {
        if (row == null) {
            throw new BusinessException("USER_IMPORT_HEADER_INVALID", "用户导入工作表缺少表头");
        }
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            String value = formatter.formatCellValue(row.getCell(index)).trim();
            if (!value.isEmpty()) {
                result.put(value, index);
            }
        }
        return result;
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

    private String optional(
            Row row, Map<String, Integer> columns, DataFormatter formatter, String column
    ) {
        String value = formatter.formatCellValue(row.getCell(columns.get(column))).trim();
        return value.isEmpty() ? null : value;
    }

    private boolean bool(
            Row row, Map<String, Integer> columns, DataFormatter formatter,
            String column, boolean defaultValue
    ) {
        String value = optional(row, columns, formatter, column);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "是", "Y", "YES", "TRUE", "1" -> true;
            case "否", "N", "NO", "FALSE", "0" -> false;
            default -> throw new RowError(column, "请填写是或否");
        };
    }

    private String strategy(String value) {
        String normalized = switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "仅新增", "ADD_ONLY" -> "ADD_ONLY";
            case "新增并更新", "ADD_UPDATE" -> "ADD_UPDATE";
            default -> null;
        };
        if (normalized == null || !STRATEGIES.contains(normalized)) {
            throw new RowError("处理策略", "请填写仅新增或新增并更新");
        }
        return normalized;
    }

    private List<String> splitRoles(String value) {
        return java.util.Arrays.stream(value.split("[,，;；|\\n]"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(this::upper)
                .distinct()
                .toList();
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private UserImportDtos.ImportError error(
            UserImportDtos.UserInput row, String column, String message
    ) {
        return new UserImportDtos.ImportError(row.rowNumber(), column, message);
    }

    private String safeFileName(String fileName) {
        String value = fileName == null ? "user-import.xlsx" : fileName;
        value = value.replace('\\', '_').replace('/', '_').replace("..", "_");
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private String sha256(MultipartFile file) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(file.getBytes())
            );
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new BusinessException("USER_IMPORT_FILE_INVALID", "无法读取用户导入文件");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "USER_IMPORT_SERIALIZE_FAILED", "用户导入数据序列化失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "USER_IMPORT_BATCH_CORRUPTED", "用户导入批次数据损坏",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private List<UserImportDtos.ImportError> readErrors(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    value, new TypeReference<List<UserImportDtos.ImportError>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "USER_IMPORT_BATCH_CORRUPTED", "用户导入错误回执损坏",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    private record Parsed(
            List<UserImportDtos.UserInput> rows,
            List<UserImportDtos.ImportError> errors,
            int totalRows
    ) {
    }

    private record Validated(
            List<UserImportDtos.UserInput> rows,
            List<UserImportDtos.ImportError> errors
    ) {
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
}
