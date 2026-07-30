package com.leantpm.system.service;

import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.mapper.SystemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SystemService {
    private static final Set<String> DATA_SCOPE_TYPES = Set.of(
            "ALL", "ORGANIZATION", "ORGANIZATION_AND_CHILDREN", "SELF", "CUSTOM"
    );

    private final SystemMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final DataPermissionService dataPermissionService;

    public SystemService(
            SystemMapper mapper,
            PasswordEncoder passwordEncoder,
            DataPermissionService dataPermissionService
    ) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.dataPermissionService = dataPermissionService;
    }

    @Transactional(readOnly = true)
    public PageResult<SystemDtos.UserRow> users(String keyword, Integer status, int page, int pageSize) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        var rows = mapper.findUsers(
                current.tenantId(), clean(keyword), status, scope, offset(page, pageSize), pageSize
        );
        var enriched = rows.stream().map(row -> new SystemDtos.UserRow(
                row.id(), row.username(), row.realName(), row.employeeNo(), row.mobile(), row.email(),
                row.organizationId(), row.organizationName(),
                row.status(), row.mobileEnabled(), row.mustChangePassword(), row.lastLoginTime(),
                row.createdTime(), row.version(), mapper.findUserRoleIds(current.tenantId(), row.id())
        )).toList();
        return PageResult.of(
                enriched,
                mapper.countUsers(current.tenantId(), clean(keyword), status, scope),
                page,
                pageSize
        );
    }

    @Transactional
    public long createUser(SystemDtos.CreateUserRequest request) {
        var current = SecurityUtils.currentUser();
        assertCanCreateIn(request.organizationId());
        String username = request.username().trim();
        if (mapper.countUsername(current.tenantId(), username) > 0) {
            throw new BusinessException("USERNAME_EXISTS", "用户名已存在", HttpStatus.CONFLICT);
        }
        mapper.insertUser(
                current.tenantId(),
                username,
                passwordEncoder.encode(request.initialPassword()),
                request.realName().trim(),
                clean(request.employeeNo()),
                clean(request.mobile()),
                clean(request.email()),
                request.organizationId(),
                !Boolean.FALSE.equals(request.mobileEnabled()),
                current.userId()
        );
        long userId = mapper.findUserIdByUsername(current.tenantId(), username);
        replaceUserRoles(current.tenantId(), userId, request.roleIds(), current.userId());
        return userId;
    }

    @Transactional
    public void updateUser(long userId, SystemDtos.UpdateUserRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        assertCanAccessUser(current.tenantId(), userId, scope);
        if (!scope.canCreateIn(request.organizationId())) {
            throw dataAccessDenied();
        }
        if (mapper.updateUser(current.tenantId(), userId, request, scope, current.userId()) == 0) {
            throw optimisticConflict();
        }
        replaceUserRoles(current.tenantId(), userId, request.roleIds(), current.userId());
    }

    @Transactional
    public void updateUserStatus(long userId, SystemDtos.StatusRequest request) {
        var current = SecurityUtils.currentUser();
        if (userId == current.userId() && !request.enabled()) {
            throw new BusinessException("CANNOT_DISABLE_SELF", "不能停用当前登录账号");
        }
        DataPermission scope = dataPermissionService.current();
        assertCanAccessUser(current.tenantId(), userId, scope);
        if (mapper.updateUserStatus(
                current.tenantId(), userId, request.enabled(), request.version(),
                scope, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
    }

    @Transactional
    public void resetPassword(long userId, SystemDtos.ResetPasswordRequest request) {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        assertCanAccessUser(current.tenantId(), userId, scope);
        if (mapper.resetUserPassword(
                current.tenantId(),
                userId,
                passwordEncoder.encode(request.newPassword()),
                scope,
                current.userId()
        ) == 0) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.RoleRow> roles() {
        var current = SecurityUtils.currentUser();
        return mapper.findRoles(current.tenantId()).stream()
                .map(role -> new SystemDtos.RoleRow(
                        role.id(), role.roleCode(), role.roleName(), role.dataScope(), role.status(),
                        role.sortOrder(), role.remark(), role.version(),
                        mapper.findRoleMenuIds(current.tenantId(), role.id()),
                        mapper.findRoleCustomOrganizationIds(current.tenantId(), role.id())
                ))
                .toList();
    }

    @Transactional
    public long createRole(SystemDtos.SaveRoleRequest request) {
        var current = SecurityUtils.currentUser();
        validateDataScope(current.tenantId(), request.dataScope(), request.customOrganizationIds());
        String code = request.roleCode().trim().toUpperCase();
        if (mapper.countRoleCode(current.tenantId(), code) > 0) {
            throw new BusinessException("ROLE_CODE_EXISTS", "角色编码已存在", HttpStatus.CONFLICT);
        }
        var normalized = new SystemDtos.SaveRoleRequest(
                code, request.roleName(), request.dataScope(), request.enabled(), request.sortOrder(),
                request.remark(), request.menuIds(), request.customOrganizationIds(), request.version()
        );
        mapper.insertRole(current.tenantId(), normalized, current.userId());
        long roleId = mapper.findRoleIdByCode(current.tenantId(), code);
        replaceRoleMenus(current.tenantId(), roleId, request.menuIds(), current.userId());
        replaceRoleDataScopes(
                current.tenantId(), roleId, request.dataScope(), request.customOrganizationIds(), current.userId()
        );
        return roleId;
    }

    @Transactional
    public void updateRole(long roleId, SystemDtos.SaveRoleRequest request) {
        var current = SecurityUtils.currentUser();
        if (request.version() == null) {
            throw new BusinessException("VERSION_REQUIRED", "缺少数据版本");
        }
        validateDataScope(current.tenantId(), request.dataScope(), request.customOrganizationIds());
        if (mapper.updateRole(current.tenantId(), roleId, request, current.userId()) == 0) {
            throw optimisticConflict();
        }
        replaceRoleMenus(current.tenantId(), roleId, request.menuIds(), current.userId());
        replaceRoleDataScopes(
                current.tenantId(), roleId, request.dataScope(), request.customOrganizationIds(), current.userId()
        );
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.OrganizationNode> organizations() {
        return mapper.findOrganizations(SecurityUtils.currentUser().tenantId());
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.DataScopeDefinition> dataScopeDefinitions() {
        return mapper.findDataScopeDefinitions(SecurityUtils.currentUser().tenantId());
    }

    @Transactional
    public void updateRoleDataScope(long roleId, SystemDtos.UpdateRoleDataScopeRequest request) {
        var current = SecurityUtils.currentUser();
        validateDataScope(current.tenantId(), request.dataScope(), request.customOrganizationIds());
        if (mapper.updateRoleDataScope(
                current.tenantId(),
                roleId,
                request.dataScope(),
                request.version(),
                current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        replaceRoleDataScopes(
                current.tenantId(),
                roleId,
                request.dataScope(),
                request.customOrganizationIds(),
                current.userId()
        );
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.MenuRow> menus() {
        return mapper.findMenus(SecurityUtils.currentUser().tenantId());
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.DictionaryTypeRow> dictionaries() {
        var current = SecurityUtils.currentUser();
        return mapper.findDictionaryTypes(current.tenantId()).stream()
                .map(type -> new SystemDtos.DictionaryTypeRow(
                        type.id(), type.dictCode(), type.dictName(), type.status(), type.remark(),
                        type.version(), mapper.findDictionaryItems(current.tenantId(), type.id())
                ))
                .toList();
    }

    @Transactional
    public long createDictionaryType(SystemDtos.SaveDictionaryTypeRequest request) {
        var current = SecurityUtils.currentUser();
        String code = request.dictCode().trim();
        if (mapper.countDictionaryCode(current.tenantId(), code) > 0) {
            throw new BusinessException("DICTIONARY_CODE_EXISTS", "字典编码已存在", HttpStatus.CONFLICT);
        }
        mapper.insertDictionaryType(current.tenantId(), request, current.userId());
        return mapper.findDictionaryTypeIdByCode(current.tenantId(), code);
    }

    @Transactional
    public void updateDictionaryType(long typeId, SystemDtos.SaveDictionaryTypeRequest request) {
        var current = SecurityUtils.currentUser();
        if (request.version() == null
                || mapper.updateDictionaryType(current.tenantId(), typeId, request, current.userId()) == 0) {
            throw optimisticConflict();
        }
    }

    @Transactional
    public void deleteDictionaryType(long typeId) {
        var current = SecurityUtils.currentUser();
        if (mapper.deleteDictionaryType(current.tenantId(), typeId, current.userId()) == 0) {
            throw new BusinessException("DICTIONARY_IN_USE", "字典不存在或仍包含字典项");
        }
    }

    @Transactional
    public void createDictionaryItem(long typeId, SystemDtos.SaveDictionaryItemRequest request) {
        var current = SecurityUtils.currentUser();
        if (mapper.countDictionaryItemValue(current.tenantId(), typeId, request.itemValue()) > 0) {
            throw new BusinessException("DICTIONARY_ITEM_EXISTS", "字典项值已存在", HttpStatus.CONFLICT);
        }
        mapper.insertDictionaryItem(current.tenantId(), typeId, request, current.userId());
    }

    @Transactional
    public void updateDictionaryItem(long itemId, SystemDtos.SaveDictionaryItemRequest request) {
        var current = SecurityUtils.currentUser();
        if (request.version() == null
                || mapper.updateDictionaryItem(current.tenantId(), itemId, request, current.userId()) == 0) {
            throw optimisticConflict();
        }
    }

    @Transactional
    public void deleteDictionaryItem(long itemId) {
        var current = SecurityUtils.currentUser();
        if (mapper.deleteDictionaryItem(current.tenantId(), itemId, current.userId()) == 0) {
            throw new BusinessException("DICTIONARY_ITEM_NOT_FOUND", "字典项不存在", HttpStatus.NOT_FOUND);
        }
    }

    @Transactional(readOnly = true)
    public PageResult<SystemDtos.LoginLogRow> loginLogs(String keyword, int page, int pageSize) {
        var current = SecurityUtils.currentUser();
        return PageResult.of(
                mapper.findLoginLogs(current.tenantId(), clean(keyword), offset(page, pageSize), pageSize),
                mapper.countLoginLogs(current.tenantId(), clean(keyword)),
                page,
                pageSize
        );
    }

    @Transactional(readOnly = true)
    public PageResult<SystemDtos.OperationLogRow> operationLogs(String keyword, int page, int pageSize) {
        var current = SecurityUtils.currentUser();
        return PageResult.of(
                mapper.findOperationLogs(current.tenantId(), clean(keyword), offset(page, pageSize), pageSize),
                mapper.countOperationLogs(current.tenantId(), clean(keyword)),
                page,
                pageSize
        );
    }

    private void replaceUserRoles(long tenantId, long userId, List<Long> roleIds, long operatorId) {
        mapper.deleteUserRoles(tenantId, userId);
        for (Long roleId : roleIds) {
            mapper.insertUserRole(tenantId, userId, roleId, operatorId);
        }
    }

    private void replaceRoleMenus(long tenantId, long roleId, List<Long> menuIds, long operatorId) {
        mapper.deleteRoleMenus(tenantId, roleId);
        for (Long menuId : menuIds == null ? new ArrayList<Long>() : menuIds) {
            mapper.insertRoleMenu(tenantId, roleId, menuId, operatorId);
        }
    }

    private void replaceRoleDataScopes(
            long tenantId,
            long roleId,
            String scopeCode,
            List<Long> customOrganizationIds,
            long operatorId
    ) {
        mapper.deleteRoleDataScopes(tenantId, roleId);
        if ("CUSTOM".equals(scopeCode)) {
            for (Long organizationId : customOrganizationIds.stream().distinct().toList()) {
                mapper.insertRoleDataScope(tenantId, roleId, scopeCode, organizationId, operatorId);
            }
            return;
        }
        mapper.insertRoleDataScope(tenantId, roleId, scopeCode, null, operatorId);
    }

    private void validateDataScope(long tenantId, String scopeCode, List<Long> customOrganizationIds) {
        if (!DATA_SCOPE_TYPES.contains(scopeCode)) {
            throw new BusinessException("INVALID_DATA_SCOPE", "不支持的数据范围类型");
        }
        List<Long> organizations = customOrganizationIds == null
                ? List.of()
                : customOrganizationIds.stream().distinct().toList();
        if ("CUSTOM".equals(scopeCode)) {
            if (organizations.isEmpty()) {
                throw new BusinessException("CUSTOM_SCOPE_REQUIRED", "自定义数据范围至少选择一个组织");
            }
            if (mapper.countOrganizations(tenantId, organizations) != organizations.size()) {
                throw new BusinessException("ORGANIZATION_NOT_FOUND", "数据范围包含不存在或已停用的组织");
            }
        }
    }

    private void assertCanCreateIn(Long organizationId) {
        if (!dataPermissionService.current().canCreateIn(organizationId)) {
            throw dataAccessDenied();
        }
    }

    private void assertCanAccessUser(long tenantId, long userId, DataPermission scope) {
        SystemMapper.UserScopeTarget target = mapper.findUserScopeTarget(tenantId, userId);
        if (target == null || !scope.canAccess(target.id(), target.organizationId())) {
            throw dataAccessDenied();
        }
    }

    private int offset(int page, int pageSize) {
        return (page - 1) * pageSize;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException optimisticConflict() {
        return new BusinessException(
                "OPTIMISTIC_LOCK_CONFLICT",
                "数据已被其他用户修改，请刷新后重试",
                HttpStatus.CONFLICT
        );
    }

    private BusinessException dataAccessDenied() {
        return new BusinessException(
                "DATA_SCOPE_DENIED",
                "目标数据不在当前用户的数据范围内",
                HttpStatus.FORBIDDEN
        );
    }
}
