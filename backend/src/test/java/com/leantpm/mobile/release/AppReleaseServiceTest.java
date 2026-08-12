package com.leantpm.mobile.release;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.system.attachment.AttachmentService;
import com.leantpm.system.mapper.SystemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AppReleaseServiceTest {
    private AppReleaseService service;

    @BeforeEach
    void setUp() {
        service = new AppReleaseService(
                mock(JdbcTemplate.class),
                mock(SystemMapper.class),
                mock(AttachmentService.class),
                1L
        );
    }

    @Test
    void rejectsNonApkFileBeforePersisting() {
        var file = new MockMultipartFile(
                "file", "LeanTPM.zip", "application/zip", new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> service.upload(file, "1.0.0", 100, 100, "", true, false))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("ANDROID_APK_REQUIRED");
    }

    @Test
    void rejectsMinimumVersionAboveCurrentVersion() {
        var file = new MockMultipartFile(
                "file", "LeanTPM.apk", "application/vnd.android.package-archive",
                new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> service.upload(file, "1.0.0", 100, 101, "", true, false))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("ANDROID_VERSION_CODE_INVALID");
    }
}
