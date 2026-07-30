package com.leantpm.system.attachment;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.system.audit.ChangeLogService;
import com.leantpm.system.mapper.AttachmentRelationMapper;
import com.leantpm.system.mapper.SystemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AttachmentSecurityTest {
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir("./target-codex/attachment-security-test");
        properties.setMaxFileBytes(10);
        properties.setAllowedExtensions(List.of("png", "pdf"));
        service = new AttachmentService(
                mock(SystemMapper.class),
                mock(AttachmentRelationMapper.class),
                mock(ChangeLogService.class),
                properties
        );
    }

    @Test
    void rejectsExecutableExtensionBeforeWriting() {
        var file = new MockMultipartFile(
                "file", "../../payload.exe", "application/octet-stream",
                new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> service.store(file, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("FILE_TYPE_NOT_ALLOWED");
    }

    @Test
    void rejectsOversizedPayloadBeforeWriting() {
        var file = new MockMultipartFile(
                "file", "evidence.png", "image/png", new byte[11]
        );

        assertThatThrownBy(() -> service.store(file, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void rejectsInjectedBusinessTypeBeforeAuthenticationOrSql() {
        var file = new MockMultipartFile(
                "file", "evidence.png", "image/png", new byte[]{1}
        );

        assertThatThrownBy(() -> service.store(
                file, "EQUIPMENT' OR 1=1 --", 1L
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("ATTACHMENT_BUSINESS_TYPE_INVALID");
    }
}
