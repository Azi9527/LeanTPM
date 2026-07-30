package com.leantpm.system.attachment;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import com.leantpm.system.mapper.SystemMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@Validated
@RestController
@RequestMapping("/api/v1/system/attachments")
public class AttachmentController {
    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:attachment:view')")
    public ApiResponse<PageResult<AttachmentDtos.AttachmentViewRow>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.list(keyword, page, pageSize));
    }

    @PostMapping("/{id}/relations")
    @Idempotent
    @PreAuthorize("hasAuthority('system:attachment:relation')")
    public ApiResponse<AttachmentDtos.AttachmentRelationRow> addRelation(
            @PathVariable long id,
            @Valid @RequestBody AttachmentDtos.SaveAttachmentRelationRequest request
    ) {
        return ApiResponse.success(service.addRelation(id, request));
    }

    @DeleteMapping("/relations/{relationId}")
    @Idempotent
    @PreAuthorize("hasAuthority('system:attachment:relation')")
    public ApiResponse<Void> removeRelation(@PathVariable long relationId) {
        service.removeRelation(relationId);
        return ApiResponse.success();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Idempotent
    @PreAuthorize("hasAuthority('system:attachment:upload')")
    public ApiResponse<SystemMapper.AttachmentRecord> upload(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) Long businessId
    ) {
        return ApiResponse.success(service.store(file, businessType, businessId));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('system:attachment:view')")
    public ResponseEntity<org.springframework.core.io.Resource> content(@PathVariable long id) {
        var download = service.load(id);
        String contentType = download.record().contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : download.record().contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(download.record().fileSize())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.record().originalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(download.resource());
    }
}
