package com.leantpm.system.attachment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class AttachmentDtos {
    private AttachmentDtos() {
    }

    public record AttachmentRelationRow(
            long id,
            long attachmentId,
            String businessType,
            long businessId,
            String relationType,
            Integer sortOrder,
            String remark,
            LocalDateTime createdTime
    ) {
    }

    public record SaveAttachmentRelationRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "必须为大写业务编码")
            String businessType,
            @NotNull @Min(1) Long businessId,
            @NotBlank
            @Pattern(
                    regexp = "^(IMAGE|DOCUMENT|MODEL|OTHER)$",
                    message = "必须为 IMAGE、DOCUMENT、MODEL 或 OTHER"
            )
            String relationType,
            Integer sortOrder,
            @Size(max = 500) String remark
    ) {
    }

    public record AttachmentViewRow(
            long id,
            String originalName,
            String storedName,
            String storagePath,
            String contentType,
            String extension,
            long fileSize,
            String sha256,
            LocalDateTime createdTime,
            List<AttachmentRelationRow> relations
    ) {
    }
}
