package com.leantpm.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class SystemDtos {
    private SystemDtos() {
    }

    public record UserRow(
            long id,
            String username,
            String realName,
            String employeeNo,
            String mobile,
            String email,
            Long organizationId,
            String organizationName,
            Integer status,
            Boolean mobileEnabled,
            Boolean mustChangePassword,
            LocalDateTime lastLoginTime,
            LocalDateTime createdTime,
            Integer version,
            List<Long> roleIds
    ) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 100) String realName,
            @Size(max = 50) String employeeNo,
            @Pattern(regexp = "^$|^[0-9+\\-]{6,32}$", message = "格式不正确") String mobile,
            @Email @Size(max = 128) String email,
            @NotNull Long organizationId,
            Boolean mobileEnabled,
            @NotEmpty List<Long> roleIds,
            @NotBlank
            @Size(min = 6, max = 128)
            String initialPassword
    ) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 100) String realName,
            @Size(max = 50) String employeeNo,
            @Pattern(regexp = "^$|^[0-9+\\-]{6,32}$", message = "格式不正确") String mobile,
            @Email @Size(max = 128) String email,
            @NotNull Long organizationId,
            Boolean mobileEnabled,
            @NotEmpty List<Long> roleIds,
            @NotNull Integer version
    ) {
    }

    public record PersonnelOrganizationRow(
            long id,
            long parentId,
            String organizationCode,
            String organizationName,
            String organizationType,
            Long managerUserId,
            String managerName,
            Integer status,
            Integer version,
            List<Long> memberUserIds,
            List<Long> managerUserIds,
            String managerNames
    ) {
    }

    public record PersonnelUserRow(
            long id,
            String username,
            String realName,
            String employeeNo,
            Long organizationId,
            String organizationName,
            Integer status,
            List<String> roleCodes,
            List<Long> teamIds,
            Long primaryTeamId
    ) {
    }

    public record PersonnelOrganizationSnapshot(
            List<PersonnelOrganizationRow> organizations,
            List<PersonnelUserRow> users
    ) {
    }

    public record UpdateOrganizationManagerRequest(
            @NotNull List<Long> managerUserIds,
            @NotNull Integer version
    ) {
    }

    public record UpdateOrganizationMembersRequest(
            @NotNull List<Long> userIds
    ) {
    }

    public record UpdateTeamRelationshipsRequest(
            @NotNull List<Long> managerUserIds,
            @NotNull List<Long> userIds,
            @NotNull Integer version
    ) {
    }

    public record StatusRequest(@NotNull Boolean enabled, @NotNull Integer version) {
    }

    public record ResetPasswordRequest(
            @NotBlank
            @Size(min = 6, max = 128)
            String newPassword
    ) {
    }

    public record RoleRow(
            long id,
            String roleCode,
            String roleName,
            String dataScope,
            Integer status,
            Integer sortOrder,
            String remark,
            Integer version,
            List<Long> menuIds,
            List<Long> customOrganizationIds
    ) {
    }

    public record SaveRoleRequest(
            @NotBlank @Size(max = 64) String roleCode,
            @NotBlank @Size(max = 100) String roleName,
            @NotBlank String dataScope,
            @NotNull Boolean enabled,
            Integer sortOrder,
            @Size(max = 500) String remark,
            List<Long> menuIds,
            List<Long> customOrganizationIds,
            Integer version
    ) {
    }

    public record OrganizationNode(
            long id,
            long parentId,
            String organizationCode,
            String organizationName,
            String organizationType,
            Integer status
    ) {
    }

    public record DataScopeDefinition(
            long id,
            String scopeCode,
            String scopeName,
            String scopeType,
            String description,
            Integer sortOrder
    ) {
    }

    public record UpdateRoleDataScopeRequest(
            @NotBlank String dataScope,
            List<Long> customOrganizationIds,
            @NotNull Integer version
    ) {
    }

    public record MenuRow(
            long id,
            long parentId,
            String menuType,
            String menuName,
            String routeName,
            String routePath,
            String componentPath,
            String permissionCode,
            String icon,
            Integer visible,
            Integer status,
            Integer sortOrder
    ) {
    }

    public record MenuStatusRequest(@NotNull Boolean enabled) {
    }

    public record DictionaryTypeRow(
            long id,
            String dictCode,
            String dictName,
            Integer status,
            String remark,
            Integer version,
            List<DictionaryItemRow> items
    ) {
    }

    public record DictionaryItemRow(
            long id,
            long dictTypeId,
            String itemValue,
            String itemLabel,
            String color,
            String icon,
            Integer status,
            Integer sortOrder,
            Boolean isDefault,
            Integer version
    ) {
    }

    public record SaveDictionaryTypeRequest(
            @NotBlank @Size(max = 64) String dictCode,
            @NotBlank @Size(max = 100) String dictName,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark,
            Integer version
    ) {
    }

    public record SaveDictionaryItemRequest(
            @NotBlank @Size(max = 64) String itemValue,
            @NotBlank @Size(max = 100) String itemLabel,
            @Size(max = 32) String color,
            @Size(max = 64) String icon,
            @NotNull Boolean enabled,
            Integer sortOrder,
            Boolean isDefault,
            Integer version
    ) {
    }

    public record LoginLogRow(
            long id,
            String username,
            Long userId,
            String loginIp,
            String userAgent,
            Boolean success,
            String failureReason,
            LocalDateTime loginTime
    ) {
    }

    public record OperationLogRow(
            long id,
            Long userId,
            String username,
            String requestMethod,
            String requestPath,
            String requestIp,
            Boolean success,
            String errorMessage,
            Long durationMs,
            LocalDateTime operationTime
    ) {
    }
}
