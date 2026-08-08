package com.leantpm.mobile.release;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.system.attachment.AttachmentService;
import com.leantpm.system.mapper.SystemMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@Service
public class AppReleaseService {
    private static final String ATTACHMENT_ID = "mobile.android-attachment-id";
    private static final String ENABLED = "mobile.android-download-enabled";
    private static final String VERSION_NAME = "mobile.android-latest-version-name";
    private static final String VERSION_CODE = "mobile.android-latest-version-code";
    private static final String MINIMUM_VERSION_CODE = "mobile.android-min-version-code";
    private static final String RELEASE_NOTES = "mobile.android-release-notes";
    private static final String DOWNLOAD_URL = "mobile.android-download-url";
    private static final String PUBLIC_DOWNLOAD_PATH = "/public/app/android/download";
    private static final String PUBLIC_QR_PATH = "/public/app/android/qr";

    private final JdbcTemplate jdbc;
    private final SystemMapper systemMapper;
    private final AttachmentService attachmentService;
    private final long defaultTenantId;

    public AppReleaseService(
            JdbcTemplate jdbc,
            SystemMapper systemMapper,
            AttachmentService attachmentService,
            @Value("${leantpm.branding.default-tenant-id:1}") long defaultTenantId
    ) {
        this.jdbc = jdbc;
        this.systemMapper = systemMapper;
        this.attachmentService = attachmentService;
        this.defaultTenantId = defaultTenantId;
    }

    @Transactional(readOnly = true)
    public AppReleaseDtos.AndroidRelease current() {
        return release(SecurityUtils.currentUser().tenantId(), false);
    }

    @Transactional(readOnly = true)
    public AppReleaseDtos.AndroidRelease publicCurrent() {
        return release(defaultTenantId, true);
    }

    @Transactional
    public AppReleaseDtos.AndroidRelease upload(
            MultipartFile file,
            String versionName,
            int versionCode,
            int minimumVersionCode,
            String releaseNotes,
            boolean enabled
    ) {
        validate(file, versionName, versionCode, minimumVersionCode);
        var current = SecurityUtils.currentUser();
        var attachment = attachmentService.store(file, null, null);
        save(current.tenantId(), ATTACHMENT_ID, "Android APK 附件标识",
                String.valueOf(attachment.id()), "INTEGER", "当前发布 APK 对应的附件标识", current.userId());
        save(current.tenantId(), ENABLED, "登录页展示 Android 下载",
                String.valueOf(enabled), "BOOLEAN", "控制登录页是否展示 Android APP 下载入口", current.userId());
        save(current.tenantId(), VERSION_NAME, "Android 最新版本",
                versionName.trim(), "STRING", "当前可下载 Android 版本名称", current.userId());
        save(current.tenantId(), VERSION_CODE, "Android 最新版本号",
                String.valueOf(versionCode), "INTEGER", "当前可下载 Android 内部版本号", current.userId());
        save(current.tenantId(), MINIMUM_VERSION_CODE, "Android 最低版本号",
                String.valueOf(minimumVersionCode), "INTEGER", "低于该版本号时提示强制升级", current.userId());
        save(current.tenantId(), RELEASE_NOTES, "Android 升级说明",
                clean(releaseNotes), "STRING", "Android APP 发布说明", current.userId());
        save(current.tenantId(), DOWNLOAD_URL, "Android 下载地址",
                PUBLIC_DOWNLOAD_PATH, "STRING", "企业签名 APK 的公开下载地址", current.userId());
        return release(current.tenantId(), false);
    }

    @Transactional
    public AppReleaseDtos.AndroidRelease updateEnabled(boolean enabled) {
        var current = SecurityUtils.currentUser();
        if (enabled && longValue(current.tenantId(), ATTACHMENT_ID, 0) <= 0) {
            throw new BusinessException("ANDROID_APK_REQUIRED", "请先上传 Android APK 文件");
        }
        save(current.tenantId(), ENABLED, "登录页展示 Android 下载",
                String.valueOf(enabled), "BOOLEAN", "控制登录页是否展示 Android APP 下载入口", current.userId());
        return release(current.tenantId(), false);
    }

    @Transactional(readOnly = true)
    public AttachmentService.DownloadedAttachment download() {
        AppReleaseDtos.AndroidRelease release = publicCurrent();
        if (!release.available()) {
            throw new BusinessException(
                    "ANDROID_APP_RELEASE_NOT_FOUND", "当前没有可下载的 Android APP", HttpStatus.NOT_FOUND
            );
        }
        long attachmentId = longValue(defaultTenantId, ATTACHMENT_ID, 0);
        return attachmentService.loadForTenant(defaultTenantId, attachmentId);
    }

    private AppReleaseDtos.AndroidRelease release(long tenantId, boolean publicOnly) {
        long attachmentId = longValue(tenantId, ATTACHMENT_ID, 0);
        boolean enabled = booleanValue(tenantId, ENABLED, false);
        if (attachmentId <= 0 || (publicOnly && !enabled)) {
            return AppReleaseDtos.AndroidRelease.unavailable();
        }
        SystemMapper.AttachmentRecord attachment = systemMapper.findAttachment(tenantId, attachmentId);
        if (attachment == null || !"apk".equalsIgnoreCase(attachment.extension())) {
            return AppReleaseDtos.AndroidRelease.unavailable();
        }
        return new AppReleaseDtos.AndroidRelease(
                true,
                enabled,
                stringValue(tenantId, VERSION_NAME, "1.0.0"),
                intValue(tenantId, VERSION_CODE, 1),
                intValue(tenantId, MINIMUM_VERSION_CODE, 1),
                attachment.originalName(),
                attachment.fileSize(),
                attachment.sha256(),
                stringValue(tenantId, RELEASE_NOTES, ""),
                attachment.createdTime(),
                PUBLIC_DOWNLOAD_PATH,
                PUBLIC_QR_PATH
        );
    }

    private void validate(
            MultipartFile file,
            String versionName,
            int versionCode,
            int minimumVersionCode
    ) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty() || fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            throw new BusinessException("ANDROID_APK_REQUIRED", "请选择有效的 APK 文件");
        }
        if (versionName == null || versionName.isBlank() || versionName.trim().length() > 32) {
            throw new BusinessException("ANDROID_VERSION_NAME_INVALID", "版本名称不能为空且不能超过 32 个字符");
        }
        if (versionCode < 1 || minimumVersionCode < 1 || minimumVersionCode > versionCode) {
            throw new BusinessException(
                    "ANDROID_VERSION_CODE_INVALID", "版本号必须大于 0，且最低版本号不能高于当前版本号"
            );
        }
    }

    private void save(
            long tenantId,
            String key,
            String name,
            String value,
            String valueType,
            String description,
            long operatorId
    ) {
        jdbc.update("""
                INSERT INTO system_parameter
                    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
                     group_code, description, built_in, status, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'MOBILE', ?, 1, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    parameter_name = VALUES(parameter_name),
                    parameter_value = VALUES(parameter_value),
                    value_type = VALUES(value_type),
                    group_code = 'MOBILE',
                    description = VALUES(description),
                    built_in = 1,
                    status = 1,
                    deleted = 0,
                    updated_by = VALUES(updated_by),
                    version = version + 1
                """, tenantId, key, name, value == null ? "" : value, valueType,
                description, operatorId, operatorId);
    }

    private String stringValue(long tenantId, String key, String fallback) {
        var values = jdbc.query(
                """
                SELECT parameter_value FROM system_parameter
                WHERE tenant_id = ? AND parameter_key = ? AND status = 1 AND deleted = 0
                LIMIT 1
                """,
                (resultSet, rowNum) -> resultSet.getString(1), tenantId, key
        );
        return values.isEmpty() ? fallback : values.getFirst();
    }

    private long longValue(long tenantId, String key, long fallback) {
        try {
            return Long.parseLong(stringValue(tenantId, key, String.valueOf(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int intValue(long tenantId, String key, int fallback) {
        long value = longValue(tenantId, key, fallback);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? fallback : (int) value;
    }

    private boolean booleanValue(long tenantId, String key, boolean fallback) {
        String value = stringValue(tenantId, key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
