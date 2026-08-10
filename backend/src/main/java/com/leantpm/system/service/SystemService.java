package com.leantpm.system.service;

import com.leantpm.common.api.PageResult;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.security.session.AuthSessionService;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.mapper.SystemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SystemService {
    private static final Set<String> DATA_SCOPE_TYPES = Set.of(
            "ALL", "ORGANIZATION", "ORGANIZATION_AND_CHILDREN", "SELF", "CUSTOM"
    );
    private static final Set<String> ASSIGNABLE_BUSINESS_ROLES = Set.of(
            "WORKSHOP_MANAGER", "TEAM_LEADER", "OPERATOR"
    );

    private final SystemMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final DataPermissionService dataPermissionService;
    private final AuthSessionService sessionService;

    public SystemService(
            SystemMapper mapper,
            PasswordEncoder passwordEncoder,
            DataPermissionService dataPermissionService,
            AuthSessionService sessionService
    ) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.dataPermissionService = dataPermissionService;
        this.sessionService = sessionService;
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
        assertAssignableRoles(request.roleIds());
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
        assertNotProtectedAdministrator(userId);
        assertAssignableRoles(request.roleIds());
        DataPermission scope = dataPermissionService.current();
        assertCanAccessUser(current.tenantId(), userId, scope);
        if (!scope.canCreateIn(request.organizationId())) {
            throw dataAccessDenied();
        }
        if (mapper.updateUser(current.tenantId(), userId, request, scope, current.userId()) == 0) {
            throw optimisticConflict();
        }
        replaceUserRoles(current.tenantId(), userId, request.roleIds(), current.userId());
        sessionService.revokeAllUserSessions(current.tenantId(), userId);
    }

    @Transactional
    public void updateUserStatus(long userId, SystemDtos.StatusRequest request) {
        var current = SecurityUtils.currentUser();
        if (userId == current.userId() && !request.enabled()) {
            throw new BusinessException("CANNOT_DISABLE_SELF", "不能停用当前登录账号");
        }
        assertNotProtectedAdministrator(userId);
        DataPermission scope = dataPermissionService.current();
        assertCanAccessUser(current.tenantId(), userId, scope);
        if (mapper.updateUserStatus(
                current.tenantId(), userId, request.enabled(), request.version(),
                scope, current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        if (!request.enabled()) {
            sessionService.revokeAllUserSessions(current.tenantId(), userId);
        }
    }

    @Transactional
    public void resetPassword(long userId, SystemDtos.ResetPasswordRequest request) {
        var current = SecurityUtils.currentUser();
        assertNotProtectedAdministrator(userId);
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
        sessionService.revokeAllUserSessions(current.tenantId(), userId);
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.RoleRow> roles() {
        var current = SecurityUtils.currentUser();
        return mapper.findRoles(current.tenantId()).stream()
                .filter(role -> isSuperAdministrator()
                        || (role.status() == 1
                        && ASSIGNABLE_BUSINESS_ROLES.contains(role.roleCode())))
                .map(role -> new SystemDtos.RoleRow(
                        role.id(), role.roleCode(), role.roleName(), role.dataScope(), role.status(),
                        role.sortOrder(), role.remark(), role.version(),
                        mapper.findRoleMenuIds(current.tenantId(), role.id()),
                        mapper.findRoleCustomOrganizationIds(current.tenantId(), role.id())
                ))
                .toList();
    }

    private void assertAssignableRoles(List<Long> roleIds) {
        var current = SecurityUtils.currentUser();
        Set<Long> requested = Set.copyOf(roleIds == null ? List.of() : roleIds);
        Set<Long> allowed = mapper.findRoles(current.tenantId()).stream()
                .filter(role -> role.status() == 1)
                .filter(role -> isSuperAdministrator()
                        || ASSIGNABLE_BUSINESS_ROLES.contains(role.roleCode()))
                .map(SystemDtos.RoleRow::id)
                .collect(java.util.stream.Collectors.toSet());
        if (requested.isEmpty() || !allowed.containsAll(requested)) {
            throw new BusinessException(
                    "ROLE_ASSIGNMENT_FORBIDDEN", "不能分配停用角色或超出本人权限的角色",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private void assertNotProtectedAdministrator(long userId) {
        var current = SecurityUtils.currentUser();
        if (!isSuperAdministrator()
                && mapper.countUserAdminRole(current.tenantId(), userId) > 0) {
            throw new BusinessException(
                    "ADMIN_USER_PROTECTED", "只有超级管理员可以修改管理员账号",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private boolean isSuperAdministrator() {
        Set<String> roles = SecurityUtils.currentUser().roles();
        return roles.contains("ADMIN") || roles.contains("SUPER_ADMIN");
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
        List<Long> affectedUserIds = mapper.findUserIdsByRole(current.tenantId(), roleId);
        if (mapper.updateRole(current.tenantId(), roleId, request, current.userId()) == 0) {
            throw optimisticConflict();
        }
        replaceRoleMenus(current.tenantId(), roleId, request.menuIds(), current.userId());
        replaceRoleDataScopes(
                current.tenantId(), roleId, request.dataScope(), request.customOrganizationIds(), current.userId()
        );
        invalidateRoleSessions(current.tenantId(), roleId, affectedUserIds, current.userId());
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.OrganizationNode> organizations() {
        return mapper.findOrganizations(SecurityUtils.currentUser().tenantId());
    }

    @Transactional(readOnly = true)
    public SystemDtos.PersonnelOrganizationSnapshot personnelOrganization() {
        var current = SecurityUtils.currentUser();
        DataPermission scope = dataPermissionService.current();
        var organizations = mapper.findPersonnelOrganizations(current.tenantId()).stream()
                .map(organization -> new SystemDtos.PersonnelOrganizationRow(
                        organization.id(), organization.parentId(), organization.organizationCode(),
                        organization.organizationName(), organization.organizationType(),
                        organization.managerUserId(), organization.managerName(), organization.status(),
                        organization.version(), "TEAM".equals(organization.organizationType())
                                ? mapper.findTeamMemberUserIds(current.tenantId(), organization.id())
                                : List.of(),
                        mapper.findOrganizationManagerUserIds(current.tenantId(), organization.id()),
                        String.join("、", mapper.findOrganizationManagerNames(
                                current.tenantId(), organization.id()
                        ))
                )).toList();
        var users = mapper.findUsers(current.tenantId(), null, null, scope, 0, 1000).stream()
                .map(user -> new SystemDtos.PersonnelUserRow(
                        user.id(), user.username(), user.realName(), user.employeeNo(),
                        user.organizationId(), user.organizationName(), user.status(),
                        mapper.findUserRoleCodes(current.tenantId(), user.id()),
                        mapper.findUserTeamIds(current.tenantId(), user.id()),
                        mapper.findUserPrimaryTeamId(current.tenantId(), user.id())
                )).toList();
        return new SystemDtos.PersonnelOrganizationSnapshot(organizations, users);
    }

    @Transactional
    public void updateOrganizationManager(
            long organizationId,
            SystemDtos.UpdateOrganizationManagerRequest request
    ) {
        var current = SecurityUtils.currentUser();
        var organization = mapper.findPersonnelOrganization(current.tenantId(), organizationId);
        if (organization == null) {
            throw new BusinessException("ORGANIZATION_NOT_FOUND", "组织不存在", HttpStatus.NOT_FOUND);
        }
        if (!Set.of("WORKSHOP", "TEAM").contains(organization.organizationType())) {
            throw new BusinessException(
                    "ORGANIZATION_MANAGER_LEVEL_INVALID", "仅车间和班组可以维护本级负责人"
            );
        }
        if (!dataPermissionService.current().canCreateIn(organization.id())) {
            throw dataAccessDenied();
        }
        List<Long> managerUserIds = new ArrayList<>(
                new LinkedHashSet<>(request.managerUserIds())
        );
        String requiredRole = "WORKSHOP".equals(organization.organizationType())
                ? "WORKSHOP_MANAGER" : "TEAM_LEADER";
        if (!managerUserIds.isEmpty() && mapper.countActiveUsersWithRole(
                current.tenantId(), managerUserIds, requiredRole
        ) != managerUserIds.size()) {
            throw new BusinessException(
                    "ORGANIZATION_MANAGER_ROLE_INVALID",
                    "WORKSHOP".equals(organization.organizationType())
                            ? "车间负责人必须具有车间主任角色"
                            : "班组负责人必须具有班组长角色"
            );
        }
        if (mapper.updateOrganizationManager(
                current.tenantId(), organizationId,
                managerUserIds.isEmpty() ? null : managerUserIds.getFirst(),
                request.version(), current.userId()
        ) == 0) {
            throw optimisticConflict();
        }
        mapper.deleteOrganizationManagers(current.tenantId(), organizationId, current.userId());
        for (int index = 0; index < managerUserIds.size(); index++) {
            mapper.insertOrganizationManager(
                    current.tenantId(), organizationId, managerUserIds.get(index),
                    requiredRole, index, current.userId()
            );
        }
    }

    @Transactional
    public void updateOrganizationMembers(
            long organizationId,
            SystemDtos.UpdateOrganizationMembersRequest request
    ) {
        var current = SecurityUtils.currentUser();
        var organization = mapper.findPersonnelOrganization(current.tenantId(), organizationId);
        if (organization == null || !"TEAM".equals(organization.organizationType())) {
            throw new BusinessException("TEAM_NOT_FOUND", "班组不存在", HttpStatus.NOT_FOUND);
        }
        if (!dataPermissionService.current().canCreateIn(organization.id())) {
            throw dataAccessDenied();
        }
        List<Long> userIds = new ArrayList<>(new LinkedHashSet<>(request.userIds()));
        if (!userIds.isEmpty() && mapper.countActiveUsersWithRole(
                current.tenantId(), userIds, "OPERATOR"
        ) != userIds.size()) {
            throw new BusinessException(
                    "TEAM_MEMBER_ROLE_INVALID", "班组成员必须是已启用的员工账号"
            );
        }
        mapper.deleteTeamMembers(current.tenantId(), organizationId, current.userId());
        for (Long userId : userIds) {
            boolean primary = mapper.findUserPrimaryTeamId(current.tenantId(), userId) == null;
            mapper.insertTeamMember(
                    current.tenantId(), organizationId, userId, primary, current.userId()
            );
        }
    }

    @Transactional
    public void updateTeamRelationships(
            long organizationId,
            SystemDtos.UpdateTeamRelationshipsRequest request
    ) {
        updateOrganizationManager(
                organizationId,
                new SystemDtos.UpdateOrganizationManagerRequest(
                        request.managerUserIds(), request.version()
                )
        );
        updateOrganizationMembers(
                organizationId,
                new SystemDtos.UpdateOrganizationMembersRequest(request.userIds())
        );
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.DataScopeDefinition> dataScopeDefinitions() {
        return mapper.findDataScopeDefinitions(SecurityUtils.currentUser().tenantId());
    }

    @Transactional
    public void updateRoleDataScope(long roleId, SystemDtos.UpdateRoleDataScopeRequest request) {
        var current = SecurityUtils.currentUser();
        validateDataScope(current.tenantId(), request.dataScope(), request.customOrganizationIds());
        List<Long> affectedUserIds = mapper.findUserIdsByRole(current.tenantId(), roleId);
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
        invalidateRoleSessions(current.tenantId(), roleId, affectedUserIds, current.userId());
    }

    private void invalidateRoleSessions(
            long tenantId,
            long roleId,
            List<Long> affectedUserIds,
            long operatorId
    ) {
        mapper.bumpAuthEpochForRole(tenantId, roleId, operatorId);
        affectedUserIds.forEach(userId -> sessionService.revokeAllUserSessions(tenantId, userId));
    }

    @Transactional(readOnly = true)
    public List<SystemDtos.MenuRow> menus() {
        return mapper.findMenus(SecurityUtils.currentUser().tenantId());
    }

    @Transactional
    public int updateMenuStatus(long menuId, SystemDtos.MenuStatusRequest request) {
        var current = SecurityUtils.currentUser();
        List<SystemDtos.MenuRow> menus = mapper.findMenus(current.tenantId());
        if (menus.stream().noneMatch(menu -> menu.id() == menuId)) {
            throw new BusinessException("MENU_NOT_FOUND", "菜单不存在或已删除", HttpStatus.NOT_FOUND);
        }
        LinkedHashSet<Long> affectedIds = new LinkedHashSet<>();
        affectedIds.add(menuId);
        boolean changed;
        do {
            changed = false;
            for (SystemDtos.MenuRow menu : menus) {
                if (!affectedIds.contains(menu.id()) && affectedIds.contains(menu.parentId())) {
                    affectedIds.add(menu.id());
                    changed = true;
                }
            }
        } while (changed);
        List<Long> affectedUserIds = mapper.findUserIdsByMenuIds(
                current.tenantId(), List.copyOf(affectedIds)
        );
        int affected = mapper.updateMenuStatuses(
                current.tenantId(), List.copyOf(affectedIds), request.enabled(), current.userId()
        );
        if (affected != affectedIds.size()) {
            throw new BusinessException(
                    "MENU_STATUS_UPDATE_CONFLICT",
                    "部分菜单状态已发生变化，请刷新后重试",
                    HttpStatus.CONFLICT
            );
        }
        if (!affectedUserIds.isEmpty()) {
            mapper.bumpAuthEpochForUsers(
                    current.tenantId(), affectedUserIds, current.userId()
            );
            affectedUserIds.forEach(userId ->
                    sessionService.revokeAllUserSessions(current.tenantId(), userId)
            );
        }
        return affected;
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
