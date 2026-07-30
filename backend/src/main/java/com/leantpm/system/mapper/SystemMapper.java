package com.leantpm.system.mapper;

import com.leantpm.system.dto.SystemDtos;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SystemMapper {
    List<SystemDtos.UserRow> findUsers(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countUsers(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("status") Integer status
    );

    List<Long> findUserRoleIds(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int countUsername(@Param("tenantId") long tenantId, @Param("username") String username);

    int insertUser(
            @Param("tenantId") long tenantId,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("realName") String realName,
            @Param("employeeNo") String employeeNo,
            @Param("mobile") String mobile,
            @Param("email") String email,
            @Param("mobileEnabled") boolean mobileEnabled,
            @Param("operatorId") long operatorId
    );

    Long findUserIdByUsername(@Param("tenantId") long tenantId, @Param("username") String username);

    int updateUser(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("request") SystemDtos.UpdateUserRequest request,
            @Param("operatorId") long operatorId
    );

    int updateUserStatus(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("enabled") boolean enabled,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    int resetUserPassword(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("passwordHash") String passwordHash,
            @Param("operatorId") long operatorId
    );

    int deleteUserRoles(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int insertUserRole(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("roleId") long roleId,
            @Param("operatorId") long operatorId
    );

    List<SystemDtos.RoleRow> findRoles(@Param("tenantId") long tenantId);

    List<Long> findRoleMenuIds(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    int countRoleCode(@Param("tenantId") long tenantId, @Param("roleCode") String roleCode);

    int insertRole(
            @Param("tenantId") long tenantId,
            @Param("request") SystemDtos.SaveRoleRequest request,
            @Param("operatorId") long operatorId
    );

    Long findRoleIdByCode(@Param("tenantId") long tenantId, @Param("roleCode") String roleCode);

    int updateRole(
            @Param("tenantId") long tenantId,
            @Param("roleId") long roleId,
            @Param("request") SystemDtos.SaveRoleRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteRoleMenus(@Param("tenantId") long tenantId, @Param("roleId") long roleId);

    int insertRoleMenu(
            @Param("tenantId") long tenantId,
            @Param("roleId") long roleId,
            @Param("menuId") long menuId,
            @Param("operatorId") long operatorId
    );

    List<SystemDtos.MenuRow> findMenus(@Param("tenantId") long tenantId);

    List<SystemDtos.DictionaryTypeRow> findDictionaryTypes(@Param("tenantId") long tenantId);

    List<SystemDtos.DictionaryItemRow> findDictionaryItems(
            @Param("tenantId") long tenantId,
            @Param("typeId") long typeId
    );

    int countDictionaryCode(@Param("tenantId") long tenantId, @Param("dictCode") String dictCode);

    int insertDictionaryType(
            @Param("tenantId") long tenantId,
            @Param("request") SystemDtos.SaveDictionaryTypeRequest request,
            @Param("operatorId") long operatorId
    );

    Long findDictionaryTypeIdByCode(@Param("tenantId") long tenantId, @Param("dictCode") String dictCode);

    int updateDictionaryType(
            @Param("tenantId") long tenantId,
            @Param("typeId") long typeId,
            @Param("request") SystemDtos.SaveDictionaryTypeRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteDictionaryType(
            @Param("tenantId") long tenantId,
            @Param("typeId") long typeId,
            @Param("operatorId") long operatorId
    );

    int countDictionaryItemValue(
            @Param("tenantId") long tenantId,
            @Param("typeId") long typeId,
            @Param("itemValue") String itemValue
    );

    int insertDictionaryItem(
            @Param("tenantId") long tenantId,
            @Param("typeId") long typeId,
            @Param("request") SystemDtos.SaveDictionaryItemRequest request,
            @Param("operatorId") long operatorId
    );

    int updateDictionaryItem(
            @Param("tenantId") long tenantId,
            @Param("itemId") long itemId,
            @Param("request") SystemDtos.SaveDictionaryItemRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteDictionaryItem(
            @Param("tenantId") long tenantId,
            @Param("itemId") long itemId,
            @Param("operatorId") long operatorId
    );

    List<SystemDtos.LoginLogRow> findLoginLogs(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countLoginLogs(@Param("tenantId") long tenantId, @Param("keyword") String keyword);

    List<SystemDtos.OperationLogRow> findOperationLogs(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countOperationLogs(@Param("tenantId") long tenantId, @Param("keyword") String keyword);

    int insertOperationLog(
            @Param("tenantId") long tenantId,
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("method") String method,
            @Param("path") String path,
            @Param("ip") String ip,
            @Param("success") boolean success,
            @Param("errorMessage") String errorMessage,
            @Param("durationMs") long durationMs
    );

    int insertAttachment(
            @Param("tenantId") long tenantId,
            @Param("businessType") String businessType,
            @Param("businessId") Long businessId,
            @Param("originalName") String originalName,
            @Param("storedName") String storedName,
            @Param("storagePath") String storagePath,
            @Param("contentType") String contentType,
            @Param("extension") String extension,
            @Param("fileSize") long fileSize,
            @Param("sha256") String sha256,
            @Param("operatorId") long operatorId
    );

    Long findAttachmentIdByStoredName(
            @Param("tenantId") long tenantId,
            @Param("storedName") String storedName
    );

    List<AttachmentRecord> findAttachments(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countAttachments(@Param("tenantId") long tenantId, @Param("keyword") String keyword);

    AttachmentRecord findAttachment(@Param("tenantId") long tenantId, @Param("id") long id);

    record AttachmentRecord(
            long id,
            String businessType,
            Long businessId,
            String originalName,
            String storedName,
            String storagePath,
            String contentType,
            String extension,
            long fileSize,
            String sha256,
            java.time.LocalDateTime createdTime
    ) {
    }
}
