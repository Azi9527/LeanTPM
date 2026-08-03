package com.leantpm.inspection;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/inspection/calendars")
public class InspectionCalendarController {
    private final InspectionCalendarService service;

    public InspectionCalendarController(InspectionCalendarService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inspection:calendar:view')")
    public ApiResponse<List<InspectionCalendarDtos.CalendarRow>> calendars(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status
    ) {
        return ApiResponse.success(service.calendars(keyword, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inspection:calendar:view')")
    public ApiResponse<InspectionCalendarDtos.CalendarDetail> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:calendar:manage')")
    public ApiResponse<Map<String, Long>> create(
            @Valid @RequestBody InspectionCalendarDtos.SaveCalendarRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.create(request)));
    }

    @PutMapping("/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:calendar:manage')")
    public ApiResponse<Void> update(
            @PathVariable long id,
            @Valid @RequestBody InspectionCalendarDtos.SaveCalendarRequest request
    ) {
        service.update(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:calendar:manage')")
    public ApiResponse<Void> delete(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.delete(id, version);
        return ApiResponse.success();
    }

    @PostMapping("/{calendarId}/exceptions")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:calendar:manage')")
    public ApiResponse<Map<String, Long>> createException(
            @PathVariable long calendarId,
            @Valid @RequestBody InspectionCalendarDtos.SaveExceptionRequest request
    ) {
        return ApiResponse.success(Map.of(
                "id", service.createException(calendarId, request)
        ));
    }

    @PutMapping("/{calendarId}/exceptions/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:calendar:manage')")
    public ApiResponse<Void> updateException(
            @PathVariable long calendarId,
            @PathVariable long id,
            @Valid @RequestBody InspectionCalendarDtos.SaveExceptionRequest request
    ) {
        service.updateException(calendarId, id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{calendarId}/exceptions/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('inspection:calendar:manage')")
    public ApiResponse<Void> deleteException(
            @PathVariable long calendarId,
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteException(calendarId, id, version);
        return ApiResponse.success();
    }
}
