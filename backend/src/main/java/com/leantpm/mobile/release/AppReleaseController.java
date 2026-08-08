package com.leantpm.mobile.release;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.exception.BusinessException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Validated
@RestController
public class AppReleaseController {
    private final AppReleaseService service;

    public AppReleaseController(AppReleaseService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/system/app-releases/android")
    @PreAuthorize("hasAuthority('system:app-release:view')")
    public ApiResponse<AppReleaseDtos.AndroidRelease> current() {
        return ApiResponse.success(service.current());
    }

    @PostMapping(
            value = "/api/v1/system/app-releases/android",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAuthority('system:app-release:manage')")
    public ApiResponse<AppReleaseDtos.AndroidRelease> upload(
            @RequestParam MultipartFile file,
            @RequestParam @NotBlank String versionName,
            @RequestParam @Min(1) int versionCode,
            @RequestParam @Min(1) int minimumVersionCode,
            @RequestParam(required = false) String releaseNotes,
            @RequestParam(defaultValue = "true") boolean enabled
    ) {
        return ApiResponse.success(service.upload(
                file, versionName, versionCode, minimumVersionCode, releaseNotes, enabled
        ));
    }

    @PatchMapping("/api/v1/system/app-releases/android/enabled")
    @PreAuthorize("hasAuthority('system:app-release:manage')")
    public ApiResponse<AppReleaseDtos.AndroidRelease> updateEnabled(
            @RequestParam boolean enabled
    ) {
        return ApiResponse.success(service.updateEnabled(enabled));
    }

    @GetMapping("/api/v1/public/app/android/latest")
    public ApiResponse<AppReleaseDtos.AndroidRelease> latest() {
        return ApiResponse.success(service.publicCurrent());
    }

    @GetMapping("/api/v1/public/app/android/download")
    public ResponseEntity<org.springframework.core.io.Resource> download() {
        var download = service.download();
        String contentType = download.record().contentType() == null
                ? "application/vnd.android.package-archive"
                : download.record().contentType();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
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

    @GetMapping(value = "/api/v1/public/app/android/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrCode(
            @RequestParam(defaultValue = "280") @Min(160) @Max(800) int size,
            @RequestParam(required = false) String origin
    ) throws Exception {
        if (!service.publicCurrent().available()) {
            return ResponseEntity.notFound().build();
        }
        String url = appDownloadUrl(origin);
        var matrix = new QRCodeWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                size,
                size,
                Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8")
        );
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .body(output.toByteArray());
        }
    }

    static String appDownloadUrl(String requestedOrigin) {
        if (requestedOrigin == null || requestedOrigin.isBlank()) {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/v1/public/app/android/download")
                    .build()
                    .toUriString();
        }
        try {
            URI origin = URI.create(requestedOrigin.trim());
            if (!("http".equalsIgnoreCase(origin.getScheme())
                    || "https".equalsIgnoreCase(origin.getScheme()))
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || (origin.getPath() != null
                    && !origin.getPath().isBlank()
                    && !"/".equals(origin.getPath()))) {
                throw new IllegalArgumentException("invalid origin");
            }
            return UriComponentsBuilder.newInstance()
                    .scheme(origin.getScheme())
                    .host(origin.getHost())
                    .port(origin.getPort())
                    .path("/api/v1/public/app/android/download")
                    .build()
                    .toUriString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    "ANDROID_APP_DOWNLOAD_ORIGIN_INVALID",
                    "APP 下载二维码访问地址不正确",
                    HttpStatus.BAD_REQUEST
            );
        }
    }
}
