package com.leantpm.system.controller;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.service.SystemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/v1/system")
public class SystemController {
    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('system:user:view')")
    public ApiResponse<PageResult<SystemDtos.UserRow>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.users(keyword, status, page, pageSize));
    }

    @PostMapping("/users")
    @Idempotent
    @PreAuthorize("hasAuthority('system:user:create')")
    public ApiResponse<Map<String, Long>> createUser(
            @Valid @RequestBody SystemDtos.CreateUserRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createUser(request)));
    }

    @PutMapping("/users/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<Void> updateUser(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.UpdateUserRequest request
    ) {
        service.updateUser(id, request);
        return ApiResponse.success();
    }

    @PatchMapping("/users/{id}/status")
    @Idempotent
    @PreAuthorize("hasAuthority('system:user:status')")
    public ApiResponse<Void> updateUserStatus(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.StatusRequest request
    ) {
        service.updateUserStatus(id, request);
        return ApiResponse.success();
    }

    @PostMapping("/users/{id}/reset-password")
    @Idempotent
    @PreAuthorize("hasAuthority('system:user:reset-password')")
    public ApiResponse<Void> resetPassword(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.ResetPasswordRequest request
    ) {
        service.resetPassword(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('system:role:view') or hasAuthority('system:data-scope:view')")
    public ApiResponse<List<SystemDtos.RoleRow>> roles() {
        return ApiResponse.success(service.roles());
    }

    @PostMapping("/roles")
    @Idempotent
    @PreAuthorize(
            "hasAuthority('system:role:create') and hasAuthority('system:data-scope:manage')"
    )
    public ApiResponse<Map<String, Long>> createRole(
            @Valid @RequestBody SystemDtos.SaveRoleRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createRole(request)));
    }

    @PutMapping("/roles/{id}")
    @Idempotent
    @PreAuthorize(
            "(hasAuthority('system:role:update') or hasAuthority('system:role:authorize')) "
                    + "and hasAuthority('system:data-scope:manage')"
    )
    public ApiResponse<Void> updateRole(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.SaveRoleRequest request
    ) {
        service.updateRole(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/organizations/tree")
    @PreAuthorize(
            "hasAuthority('system:user:view') or hasAuthority('system:role:view') "
                    + "or hasAuthority('system:data-scope:view')"
    )
    public ApiResponse<List<SystemDtos.OrganizationNode>> organizations() {
        return ApiResponse.success(service.organizations());
    }

    @GetMapping("/data-scopes")
    @PreAuthorize("hasAuthority('system:data-scope:view')")
    public ApiResponse<List<SystemDtos.DataScopeDefinition>> dataScopes() {
        return ApiResponse.success(service.dataScopeDefinitions());
    }

    @PutMapping("/roles/{id}/data-scope")
    @Idempotent
    @PreAuthorize("hasAuthority('system:data-scope:manage')")
    public ApiResponse<Void> updateRoleDataScope(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.UpdateRoleDataScopeRequest request
    ) {
        service.updateRoleDataScope(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/menus/tree")
    @PreAuthorize("hasAuthority('system:menu:view')")
    public ApiResponse<List<SystemDtos.MenuRow>> menus() {
        return ApiResponse.success(service.menus());
    }

    @GetMapping("/dictionaries")
    @PreAuthorize("hasAuthority('system:dictionary:view')")
    public ApiResponse<List<SystemDtos.DictionaryTypeRow>> dictionaries() {
        return ApiResponse.success(service.dictionaries());
    }

    @PostMapping("/dictionaries")
    @Idempotent
    @PreAuthorize("hasAuthority('system:dictionary:manage')")
    public ApiResponse<Map<String, Long>> createDictionaryType(
            @Valid @RequestBody SystemDtos.SaveDictionaryTypeRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createDictionaryType(request)));
    }

    @PutMapping("/dictionaries/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('system:dictionary:manage')")
    public ApiResponse<Void> updateDictionaryType(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.SaveDictionaryTypeRequest request
    ) {
        service.updateDictionaryType(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/dictionaries/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('system:dictionary:manage')")
    public ApiResponse<Void> deleteDictionaryType(@PathVariable long id) {
        service.deleteDictionaryType(id);
        return ApiResponse.success();
    }

    @PostMapping("/dictionaries/{typeId}/items")
    @Idempotent
    @PreAuthorize("hasAuthority('system:dictionary:manage')")
    public ApiResponse<Void> createDictionaryItem(
            @PathVariable long typeId,
            @Valid @RequestBody SystemDtos.SaveDictionaryItemRequest request
    ) {
        service.createDictionaryItem(typeId, request);
        return ApiResponse.success();
    }

    @PutMapping("/dictionary-items/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('system:dictionary:manage')")
    public ApiResponse<Void> updateDictionaryItem(
            @PathVariable long id,
            @Valid @RequestBody SystemDtos.SaveDictionaryItemRequest request
    ) {
        service.updateDictionaryItem(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/dictionary-items/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('system:dictionary:manage')")
    public ApiResponse<Void> deleteDictionaryItem(@PathVariable long id) {
        service.deleteDictionaryItem(id);
        return ApiResponse.success();
    }

    @GetMapping("/login-logs")
    @PreAuthorize("hasAuthority('system:login-log:view')")
    public ApiResponse<PageResult<SystemDtos.LoginLogRow>> loginLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.loginLogs(keyword, page, pageSize));
    }

    @GetMapping("/operation-logs")
    @PreAuthorize("hasAuthority('system:operation-log:view')")
    public ApiResponse<PageResult<SystemDtos.OperationLogRow>> operationLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.success(service.operationLogs(keyword, page, pageSize));
    }
}
