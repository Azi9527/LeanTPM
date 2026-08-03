package com.leantpm.inspection;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class InspectionCalendarDtos {
    private InspectionCalendarDtos() {
    }

    public record CalendarRow(
            long id,
            String calendarName,
            String workDays,
            Boolean defaultFlag,
            Integer status,
            String description,
            Integer exceptionCount,
            int version
    ) {
    }

    public record CalendarExceptionRow(
            long id,
            long calendarId,
            String exceptionName,
            LocalDate startDate,
            LocalDate endDate,
            String dayType,
            int priorityValue,
            Integer status,
            String description,
            LocalDateTime updatedTime,
            int version
    ) {
    }

    public record CalendarDetail(
            CalendarRow calendar,
            List<CalendarExceptionRow> exceptions
    ) {
    }

    public record SaveCalendarRequest(
            @NotBlank @Size(max = 150) String calendarName,
            @NotBlank
            @Pattern(regexp = "^[1-7](,[1-7])*$", message = "工作日格式不正确")
            String workDays,
            @NotNull Boolean defaultFlag,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }

    public record SaveExceptionRequest(
            @NotBlank @Size(max = 150) String exceptionName,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank
            @Pattern(regexp = "^(WORKDAY|RESTDAY)$", message = "日期类型不正确")
            String dayType,
            @NotNull @Min(0) @Max(10000) Integer priorityValue,
            @NotNull @Min(0) @Max(1) Integer status,
            @Size(max = 500) String description,
            @Min(0) Integer version
    ) {
    }
}

