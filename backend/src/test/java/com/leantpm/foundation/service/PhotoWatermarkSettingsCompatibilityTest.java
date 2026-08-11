package com.leantpm.foundation.service;

import com.leantpm.foundation.dto.FoundationDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoWatermarkSettingsCompatibilityTest {
    @Test
    void acceptsTheLegacyPayloadAndDefaultsTheNewAlbumFlagToFalse() {
        FoundationDtos.SavePhotoWatermarkSettingsRequest request =
                new FoundationDtos.SavePhotoWatermarkSettingsRequest(
                        null, true, true, true, "{brand}", "BOTTOM",
                        74, "#ffffff", "#031922"
                );
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(request)).isEmpty();
        }

        ParameterService parameterService = mock(ParameterService.class);
        AtomicLong ids = new AtomicLong(1);
        List<String> keys = List.of(
                "mobile.photo-allow-album-selection",
                "mobile.photo-watermark-enabled",
                "mobile.photo-save-original",
                "mobile.photo-save-watermarked",
                "mobile.photo-watermark-template",
                "mobile.photo-watermark-position",
                "mobile.photo-watermark-background-opacity",
                "mobile.photo-watermark-font-color",
                "mobile.photo-watermark-background-color"
        );
        when(parameterService.list(null, null)).thenReturn(keys.stream()
                .map(key -> new FoundationDtos.ParameterRow(
                        ids.getAndIncrement(), key, key, "true", "STRING",
                        "MOBILE_WATERMARK", null, true, 1, null, 0
                ))
                .toList());

        new PhotoWatermarkSettingsService(parameterService).update(request);

        ArgumentCaptor<FoundationDtos.SaveParameterRequest> captor =
                ArgumentCaptor.forClass(FoundationDtos.SaveParameterRequest.class);
        verify(parameterService, times(keys.size())).update(anyLong(), captor.capture());
        FoundationDtos.SaveParameterRequest albumUpdate = captor.getAllValues().stream()
                .filter(value -> value.parameterKey().equals("mobile.photo-allow-album-selection"))
                .findFirst()
                .orElseThrow();
        assertThat(albumUpdate.parameterValue()).isEqualTo("false");
    }
}
