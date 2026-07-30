package com.leantpm.auth.mapper;

import com.leantpm.auth.domain.UserAccount;
import com.leantpm.auth.dto.UserProfile;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface AuthMapper {
    UserAccount findByUsername(@Param("tenantId") long tenantId, @Param("username") String username);

    UserAccount findById(@Param("tenantId") long tenantId, @Param("userId") long userId);

    Set<String> findRoleCodes(@Param("tenantId") long tenantId, @Param("userId") long userId);

    Set<String> findPermissionCodes(@Param("tenantId") long tenantId, @Param("userId") long userId);

    List<UserProfile.MenuItem> findMenus(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int countRecentFailures(
            @Param("tenantId") long tenantId,
            @Param("username") String username,
            @Param("since") LocalDateTime since
    );

    int insertLoginLog(
            @Param("tenantId") long tenantId,
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("ip") String ip,
            @Param("userAgent") String userAgent,
            @Param("success") boolean success,
            @Param("reason") String reason
    );

    int updateLastLogin(@Param("tenantId") long tenantId, @Param("userId") long userId);

    int updatePassword(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("passwordHash") String passwordHash
    );

    int countUsers(@Param("tenantId") long tenantId);

    int insertBootstrapAdmin(
            @Param("tenantId") long tenantId,
            @Param("username") String username,
            @Param("realName") String realName,
            @Param("passwordHash") String passwordHash
    );

    int assignRole(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("roleId") long roleId
    );
}
