package com.leantpm.foundation.service;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.mapper.FoundationMapper;
import com.leantpm.security.CurrentUser;
import com.leantpm.system.audit.ChangeLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParameterServiceBarcodeLogoTest {
    private static final String KEY = "equipment.barcode.center-logo-url";
    private final FoundationMapper mapper = mock(FoundationMapper.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final ParameterService service = new ParameterService(mapper, changeLogService);

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(
                7L, 1L, "tester", "测试用户", false,
                Set.of("ADMIN"), Set.of("system:parameter:manage"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
        when(mapper.countParameterKey(1L, KEY)).thenReturn(0L);
        when(mapper.findParameterIdByKey(1L, KEY)).thenReturn(88L);
        when(mapper.findParameterById(1L, 88L)).thenReturn(null);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsDefaultAndLargeImageDataUrlForBarcodeCenterLogo() {
        String imageDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(
                new byte[3000]
        );

        assertThatCode(() -> service.create(request("DEFAULT"))).doesNotThrowAnyException();
        assertThatCode(() -> service.create(request(imageDataUrl))).doesNotThrowAnyException();
    }

    @Test
    void rejectsExternalUrlForBarcodeCenterLogo() {
        assertThatThrownBy(() -> service.create(request("https://example.com/logo.png")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("EQUIPMENT_BARCODE_LOGO_INVALID");
    }

    @Test
    void rejectsBarcodeCenterLogoLargerThanConfiguredLimit() {
        String oversized = "data:image/png;base64," + Base64.getEncoder().encodeToString(
                new byte[513 * 1024]
        );

        assertThatThrownBy(() -> service.create(request(oversized)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("PARAMETER_VALUE_TOO_LONG");
    }

    private FoundationDtos.SaveParameterRequest request(String value) {
        return new FoundationDtos.SaveParameterRequest(
                KEY,
                "设备二维码中心图标",
                value,
                "STRING",
                "EQUIPMENT",
                "设备二维码标签中心图标",
                true,
                null
        );
    }
}
