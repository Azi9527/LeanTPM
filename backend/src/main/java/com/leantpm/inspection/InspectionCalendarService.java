package com.leantpm.inspection;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.audit.ChangeLogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class InspectionCalendarService {
    private final InspectionCalendarMapper mapper;
    private final ChangeLogService changeLogService;

    public InspectionCalendarService(
            InspectionCalendarMapper mapper,
            ChangeLogService changeLogService
    ) {
        this.mapper = mapper;
        this.changeLogService = changeLogService;
    }

    @Transactional(readOnly = true)
    public List<InspectionCalendarDtos.CalendarRow> calendars(String keyword, Integer status) {
        var current = SecurityUtils.currentUser();
        return mapper.findCalendars(current.tenantId(), clean(keyword), status);
    }

    @Transactional(readOnly = true)
    public InspectionCalendarDtos.CalendarDetail detail(long id) {
        var current = SecurityUtils.currentUser();
        InspectionCalendarDtos.CalendarRow calendar = requireCalendar(current.tenantId(), id);
        return new InspectionCalendarDtos.CalendarDetail(
                calendar,
                mapper.findExceptions(current.tenantId(), id)
        );
    }

    @Transactional
    public long create(InspectionCalendarDtos.SaveCalendarRequest request) {
        var current = SecurityUtils.currentUser();
        var normalized = normalize(request);
        if (mapper.countCalendarName(
                current.tenantId(), normalized.calendarName(), null
        ) > 0) {
            throw conflict("INSPECTION_CALENDAR_NAME_EXISTS", "点检日历名称已存在");
        }
        if (normalized.defaultFlag()) {
            mapper.clearDefault(current.tenantId(), current.userId());
        }
        mapper.insertCalendar(current.tenantId(), normalized, current.userId());
        Long id = mapper.findCalendarIdByName(current.tenantId(), normalized.calendarName());
        if (id == null) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_CREATE_FAILED", "点检日历创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        changeLogService.record(
                "INSPECTION_WORK_CALENDAR", id, "CREATE", null,
                mapper.findCalendar(current.tenantId(), id)
        );
        return id;
    }

    @Transactional
    public void update(long id, InspectionCalendarDtos.SaveCalendarRequest request) {
        var current = SecurityUtils.currentUser();
        var before = requireCalendar(current.tenantId(), id);
        requireVersion(request.version());
        var normalized = normalize(request);
        if (mapper.countCalendarName(
                current.tenantId(), normalized.calendarName(), id
        ) > 0) {
            throw conflict("INSPECTION_CALENDAR_NAME_EXISTS", "点检日历名称已存在");
        }
        if (Boolean.TRUE.equals(before.defaultFlag()) && !normalized.defaultFlag()) {
            throw conflict(
                    "INSPECTION_CALENDAR_DEFAULT_REQUIRED",
                    "默认点检日历不能直接取消，请将其他启用日历设为默认"
            );
        }
        if (normalized.defaultFlag() && !Boolean.TRUE.equals(before.defaultFlag())) {
            mapper.clearDefault(current.tenantId(), current.userId());
        }
        if (mapper.updateCalendar(
                current.tenantId(), id, normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_WORK_CALENDAR", id, "UPDATE", before,
                mapper.findCalendar(current.tenantId(), id)
        );
    }

    @Transactional
    public void delete(long id, int version) {
        var current = SecurityUtils.currentUser();
        var before = requireCalendar(current.tenantId(), id);
        if (Boolean.TRUE.equals(before.defaultFlag())) {
            throw conflict("INSPECTION_CALENDAR_DEFAULT_DELETE", "默认点检日历不能删除");
        }
        if (mapper.countCalendarReferences(current.tenantId(), id) > 0) {
            throw conflict("INSPECTION_CALENDAR_IN_USE", "点检日历已被方案或计划使用");
        }
        if (mapper.deleteCalendar(current.tenantId(), id, version, current.userId()) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record("INSPECTION_WORK_CALENDAR", id, "DELETE", before, null);
    }

    @Transactional
    public long createException(
            long calendarId,
            InspectionCalendarDtos.SaveExceptionRequest request
    ) {
        var current = SecurityUtils.currentUser();
        requireCalendar(current.tenantId(), calendarId);
        var normalized = normalize(request);
        validateDateRange(normalized);
        boolean conflict = mapper.countConflictingExceptions(
                current.tenantId(), calendarId, null, normalized
        ) > 0;
        mapper.insertException(current.tenantId(), calendarId, normalized, current.userId());
        Long id = mapper.findLatestExceptionId(
                current.tenantId(), calendarId, current.userId()
        );
        if (id == null) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_EXCEPTION_CREATE_FAILED", "自由日历创建失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        changeLogService.record(
                "INSPECTION_CALENDAR_EXCEPTION", id, "CREATE", null,
                mapper.findException(current.tenantId(), id)
        );
        if (conflict) {
            changeLogService.record(
                    "INSPECTION_CALENDAR_CONFLICT", id, "RULE_CONFLICT", null,
                    normalized
            );
        }
        return id;
    }

    @Transactional
    public void updateException(
            long calendarId,
            long id,
            InspectionCalendarDtos.SaveExceptionRequest request
    ) {
        var current = SecurityUtils.currentUser();
        requireCalendar(current.tenantId(), calendarId);
        var before = requireException(current.tenantId(), calendarId, id);
        requireVersion(request.version());
        var normalized = normalize(request);
        validateDateRange(normalized);
        boolean conflict = mapper.countConflictingExceptions(
                current.tenantId(), calendarId, id, normalized
        ) > 0;
        if (mapper.updateException(
                current.tenantId(), calendarId, id, normalized, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_CALENDAR_EXCEPTION", id, "UPDATE", before,
                mapper.findException(current.tenantId(), id)
        );
        if (conflict) {
            changeLogService.record(
                    "INSPECTION_CALENDAR_CONFLICT", id, "RULE_CONFLICT", before,
                    normalized
            );
        }
    }

    @Transactional
    public void deleteException(long calendarId, long id, int version) {
        var current = SecurityUtils.currentUser();
        requireCalendar(current.tenantId(), calendarId);
        var before = requireException(current.tenantId(), calendarId, id);
        if (mapper.deleteException(
                current.tenantId(), calendarId, id, version, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        changeLogService.record(
                "INSPECTION_CALENDAR_EXCEPTION", id, "DELETE", before, null
        );
    }

    private InspectionCalendarDtos.CalendarRow requireCalendar(long tenantId, long id) {
        var row = mapper.findCalendar(tenantId, id);
        if (row == null) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_NOT_FOUND", "点检日历不存在", HttpStatus.NOT_FOUND
            );
        }
        return row;
    }

    private InspectionCalendarDtos.CalendarExceptionRow requireException(
            long tenantId,
            long calendarId,
            long id
    ) {
        var row = mapper.findException(tenantId, id);
        if (row == null || row.calendarId() != calendarId) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_EXCEPTION_NOT_FOUND", "自由日历不存在",
                    HttpStatus.NOT_FOUND
            );
        }
        return row;
    }

    private InspectionCalendarDtos.SaveCalendarRequest normalize(
            InspectionCalendarDtos.SaveCalendarRequest request
    ) {
        if (request.defaultFlag() && request.status() != 1) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_DEFAULT_INACTIVE",
                    "默认点检日历必须保持启用"
            );
        }
        Set<Integer> days = new TreeSet<>();
        for (String value : request.workDays().split(",")) {
            int day = Integer.parseInt(value);
            if (day < 1 || day > 7) {
                throw new BusinessException(
                        "INSPECTION_CALENDAR_WORK_DAYS_INVALID", "工作日必须为周一至周日"
                );
            }
            days.add(day);
        }
        if (days.isEmpty()) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_WORK_DAYS_REQUIRED", "至少选择一个工作日"
            );
        }
        return new InspectionCalendarDtos.SaveCalendarRequest(
                request.calendarName().trim(),
                days.stream().map(String::valueOf).collect(Collectors.joining(",")),
                request.defaultFlag(), request.status(), clean(request.description()),
                request.version()
        );
    }

    private InspectionCalendarDtos.SaveExceptionRequest normalize(
            InspectionCalendarDtos.SaveExceptionRequest request
    ) {
        return new InspectionCalendarDtos.SaveExceptionRequest(
                request.exceptionName().trim(), request.startDate(), request.endDate(),
                request.dayType().trim().toUpperCase(Locale.ROOT),
                request.priorityValue(), request.status(), clean(request.description()),
                request.version()
        );
    }

    private void validateDateRange(InspectionCalendarDtos.SaveExceptionRequest request) {
        if (request.startDate().isAfter(request.endDate())) {
            throw new BusinessException(
                    "INSPECTION_CALENDAR_EXCEPTION_DATE_INVALID", "开始日期不能晚于结束日期"
            );
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void requireVersion(Integer version) {
        if (version == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少数据版本");
        }
    }

    private static BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT", "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
