package com.leantpm.mobile;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/mobile")
public class MobileController {
    private final MobileService service;

    public MobileController(MobileService service) {
        this.service = service;
    }

    @GetMapping("/bootstrap")
    @PreAuthorize(
            "hasAuthority('mobile:access') and hasAuthority('mobile:workbench:view')"
    )
    public ApiResponse<MobileDtos.Bootstrap> bootstrap() {
        return ApiResponse.success(service.bootstrap());
    }

    @GetMapping("/equipment/{token}")
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:scan')")
    public ApiResponse<MobileDtos.EquipmentContext> equipment(
            @PathVariable
            @Pattern(
                    regexp = "^[a-fA-F0-9]{64}$",
                    message = "设备访问令牌格式不正确"
            )
            String token
    ) {
        return ApiResponse.success(service.equipment(token));
    }

    @PostMapping("/photo-evidence")
    @Idempotent
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:task:view')")
    public ApiResponse<MobileDtos.PhotoEvidence> registerPhotoEvidence(
            @Valid @RequestBody MobileDtos.RegisterPhotoEvidenceRequest request
    ) {
        return ApiResponse.success(service.registerPhotoEvidence(request));
    }

    @GetMapping("/photo-evidence/{id}")
    @PreAuthorize("hasAuthority('mobile:access') and hasAuthority('mobile:task:view')")
    public ApiResponse<MobileDtos.PhotoEvidence> photoEvidence(@PathVariable long id) {
        return ApiResponse.success(service.photoEvidence(id));
    }
}
