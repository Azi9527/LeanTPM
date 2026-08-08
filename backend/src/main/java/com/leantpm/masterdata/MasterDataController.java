package com.leantpm.masterdata;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {
    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    @GetMapping("/organizations")
    @PreAuthorize("hasAuthority('master-data:organization:view')")
    public ApiResponse<List<MasterDataDtos.OrganizationRow>> organizations() {
        return ApiResponse.success(service.organizations());
    }

    @PostMapping("/organizations")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:organization:manage')")
    public ApiResponse<Map<String, Long>> createOrganization(
            @Valid @RequestBody MasterDataDtos.SaveOrganizationRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createOrganization(request)));
    }

    @PutMapping("/organizations/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:organization:manage')")
    public ApiResponse<Void> updateOrganization(
            @PathVariable long id,
            @Valid @RequestBody MasterDataDtos.SaveOrganizationRequest request
    ) {
        service.updateOrganization(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/organizations/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:organization:delete')")
    public ApiResponse<Void> deleteOrganization(
            @PathVariable long id,
            @RequestParam @Min(0) int version,
            @RequestParam(defaultValue = "false") boolean cascadeRelations
    ) {
        service.deleteOrganization(id, version, cascadeRelations);
        return ApiResponse.success();
    }

    @GetMapping("/organizations/{id}/delete-impact")
    @PreAuthorize("hasAuthority('master-data:organization:delete')")
    public ApiResponse<MasterDataDtos.OrganizationDeleteImpact> organizationDeleteImpact(
            @PathVariable long id
    ) {
        return ApiResponse.success(service.organizationDeleteImpact(id));
    }

    @GetMapping("/locations")
    @PreAuthorize("hasAuthority('master-data:location:view')")
    public ApiResponse<List<MasterDataDtos.LocationRow>> locations() {
        return ApiResponse.success(service.locations());
    }

    @PostMapping("/locations")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:location:manage')")
    public ApiResponse<Map<String, Long>> createLocation(
            @Valid @RequestBody MasterDataDtos.SaveLocationRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createLocation(request)));
    }

    @PutMapping("/locations/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:location:manage')")
    public ApiResponse<Void> updateLocation(
            @PathVariable long id,
            @Valid @RequestBody MasterDataDtos.SaveLocationRequest request
    ) {
        service.updateLocation(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/locations/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:location:delete')")
    public ApiResponse<Void> deleteLocation(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteLocation(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/equipment-categories")
    @PreAuthorize("hasAuthority('master-data:equipment-category:view')")
    public ApiResponse<List<MasterDataDtos.EquipmentCategoryRow>> categories() {
        return ApiResponse.success(service.categories());
    }

    @PostMapping("/equipment-categories")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:equipment-category:manage')")
    public ApiResponse<Map<String, Long>> createCategory(
            @Valid @RequestBody MasterDataDtos.SaveEquipmentCategoryRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createCategory(request)));
    }

    @PutMapping("/equipment-categories/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:equipment-category:manage')")
    public ApiResponse<Void> updateCategory(
            @PathVariable long id,
            @Valid @RequestBody MasterDataDtos.SaveEquipmentCategoryRequest request
    ) {
        service.updateCategory(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/equipment-categories/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:equipment-category:delete')")
    public ApiResponse<Void> deleteCategory(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteCategory(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/equipment-categories/{id}/attributes")
    @PreAuthorize("hasAuthority('master-data:equipment-category:view')")
    public ApiResponse<List<MasterDataDtos.AttributeDefinitionRow>> attributes(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean includeInherited
    ) {
        return ApiResponse.success(service.attributes(id, includeInherited));
    }

    @PostMapping("/equipment-categories/{id}/attributes")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:equipment-attribute:manage')")
    public ApiResponse<Map<String, Long>> createAttribute(
            @PathVariable long id,
            @Valid @RequestBody MasterDataDtos.SaveAttributeDefinitionRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createAttribute(id, request)));
    }

    @PutMapping("/equipment-attributes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:equipment-attribute:manage')")
    public ApiResponse<Void> updateAttribute(
            @PathVariable long id,
            @Valid @RequestBody MasterDataDtos.SaveAttributeDefinitionRequest request
    ) {
        service.updateAttribute(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/equipment-attributes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('master-data:equipment-attribute:manage')")
    public ApiResponse<Void> deleteAttribute(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteAttribute(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/reference-users")
    @PreAuthorize(
            "hasAuthority('master-data:organization:view') "
                    + "or hasAuthority('master-data:location:view') "
                    + "or hasAuthority('equipment:ledger:view')"
    )
    public ApiResponse<List<MasterDataDtos.ReferenceUser>> referenceUsers() {
        return ApiResponse.success(service.referenceUsers());
    }
}
