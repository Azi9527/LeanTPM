package com.leantpm.system.attachment;

import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.mapper.SystemMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AttachmentService {
    private final SystemMapper mapper;
    private final StorageProperties properties;
    private final Path root;

    public AttachmentService(SystemMapper mapper, StorageProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.root = Path.of(properties.getUploadDir()).toAbsolutePath().normalize();
    }

    @Transactional
    public SystemMapper.AttachmentRecord store(
            MultipartFile file,
            String businessType,
            Long businessId
    ) {
        if (file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "请选择要上传的文件");
        }
        if (file.getSize() > properties.getMaxFileBytes()) {
            throw new BusinessException("FILE_TOO_LARGE", "上传文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extension(originalName);
        if (!properties.getAllowedExtensions().stream().map(String::toLowerCase).toList().contains(extension)) {
            throw new BusinessException("FILE_TYPE_NOT_ALLOWED", "不支持此文件类型");
        }

        var current = SecurityUtils.currentUser();
        LocalDate date = LocalDate.now();
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path directory = root.resolve(String.valueOf(date.getYear()))
                .resolve(String.format("%02d", date.getMonthValue()))
                .normalize();
        Path target = directory.resolve(storedName).normalize();
        ensureInsideRoot(target);

        try {
            Files.createDirectories(directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(file.getInputStream(), digest);
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                input.transferTo(output);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            String relativePath = root.relativize(target).toString().replace('\\', '/');
            mapper.insertAttachment(
                    current.tenantId(),
                    clean(businessType),
                    businessId,
                    originalName,
                    storedName,
                    relativePath,
                    clean(file.getContentType()),
                    extension,
                    file.getSize(),
                    sha256,
                    current.userId()
            );
            Long id = mapper.findAttachmentIdByStoredName(current.tenantId(), storedName);
            return mapper.findAttachment(current.tenantId(), id);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new BusinessException(
                    "FILE_STORE_FAILED",
                    "文件保存失败",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    @Transactional(readOnly = true)
    public PageResult<SystemMapper.AttachmentRecord> list(String keyword, int page, int pageSize) {
        var current = SecurityUtils.currentUser();
        int offset = (page - 1) * pageSize;
        return PageResult.of(
                mapper.findAttachments(current.tenantId(), clean(keyword), offset, pageSize),
                mapper.countAttachments(current.tenantId(), clean(keyword)),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public DownloadedAttachment load(long id) {
        var current = SecurityUtils.currentUser();
        var record = mapper.findAttachment(current.tenantId(), id);
        if (record == null) {
            throw new BusinessException("ATTACHMENT_NOT_FOUND", "附件不存在", HttpStatus.NOT_FOUND);
        }
        Path path = root.resolve(record.storagePath()).normalize();
        ensureInsideRoot(path);
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BusinessException("ATTACHMENT_CONTENT_MISSING", "附件内容不存在", HttpStatus.NOT_FOUND);
            }
            return new DownloadedAttachment(record, resource);
        } catch (IOException exception) {
            throw new BusinessException("ATTACHMENT_READ_FAILED", "附件读取失败");
        }
    }

    private void ensureInsideRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new BusinessException("INVALID_FILE_PATH", "非法文件路径");
        }
    }

    private String safeOriginalName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("INVALID_FILE_NAME", "文件名不能为空");
        }
        String normalized = name.replace('\\', '/');
        String result = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\r\\n\\t]", "_");
        if (result.length() > 255) {
            throw new BusinessException("FILE_NAME_TOO_LONG", "文件名过长");
        }
        return result;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            throw new BusinessException("FILE_EXTENSION_REQUIRED", "文件必须包含扩展名");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record DownloadedAttachment(SystemMapper.AttachmentRecord record, Resource resource) {
    }
}
