package com.leantpm.notification;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('notification:rule:view')")
    public ApiResponse<List<NotificationDtos.RuleRow>> rules() {
        return ApiResponse.success(service.rules());
    }

    @PostMapping("/rules")
    @Idempotent
    @PreAuthorize("hasAuthority('notification:rule:manage')")
    public ApiResponse<Map<String, Long>> createRule(
            @Valid @RequestBody NotificationDtos.SaveRuleRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createRule(request)));
    }

    @PutMapping("/rules/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('notification:rule:manage')")
    public ApiResponse<Void> updateRule(
            @PathVariable long id,
            @Valid @RequestBody NotificationDtos.SaveRuleRequest request
    ) {
        service.updateRule(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('notification:message:view') or hasAuthority('mobile:message:view')")
    public ApiResponse<PageResult<NotificationDtos.MessageRow>> messages(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.messages(unreadOnly, page, pageSize));
    }

    @GetMapping("/messages/{id}/business-detail")
    @PreAuthorize("hasAuthority('notification:message:view') or hasAuthority('mobile:message:view')")
    public ApiResponse<NotificationDtos.BusinessDetail> businessDetail(@PathVariable long id) {
        return ApiResponse.success(service.businessDetail(id));
    }

    @GetMapping("/messages/{id}/attachments/{attachmentId}/content")
    @PreAuthorize("hasAuthority('notification:message:view') or hasAuthority('mobile:message:view')")
    public ResponseEntity<Resource> businessAttachmentContent(
            @PathVariable long id, @PathVariable long attachmentId
    ) {
        var download = service.businessAttachmentContent(id, attachmentId);
        String contentType = download.record().contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : download.record().contentType();
        boolean image = contentType.toLowerCase().startsWith("image/");
        ContentDisposition disposition = (image
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(download.record().originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(download.record().fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.resource());
    }

    @PostMapping("/messages/{id}/read")
    @Idempotent
    @PreAuthorize("hasAuthority('notification:message:view') or hasAuthority('mobile:message:view')")
    public ApiResponse<Void> read(@PathVariable long id) {
        service.read(id);
        return ApiResponse.success();
    }

    @PostMapping("/messages/{id}/acknowledge")
    @Idempotent
    @PreAuthorize("hasAuthority('notification:message:view') or hasAuthority('mobile:message:view')")
    public ApiResponse<Void> acknowledge(@PathVariable long id) {
        service.acknowledge(id);
        return ApiResponse.success();
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('notification:delivery:view')")
    public ApiResponse<PageResult<NotificationDtos.DeliveryRow>> deliveries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.deliveries(status, page, pageSize));
    }

    @PostMapping("/scan")
    @Idempotent
    @PreAuthorize("hasAuthority('notification:scan')")
    public ApiResponse<NotificationDtos.ScanResult> scan() {
        return ApiResponse.success(service.scanCurrentTenant());
    }
}
