package com.leantpm.mobile;

import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.datascope.DataPermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class MobileService {
    private final MobileMapper mapper;
    private final DataPermissionService dataPermissionService;

    public MobileService(
            MobileMapper mapper,
            DataPermissionService dataPermissionService
    ) {
        this.mapper = mapper;
        this.dataPermissionService = dataPermissionService;
    }

    @Transactional(readOnly = true)
    public MobileDtos.Bootstrap bootstrap() {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        return new MobileDtos.Bootstrap(
                LocalDateTime.now(),
                parameter(
                        current.tenantId(), "mobile.draft-retention-days",
                        7, 1, 30
                ),
                parameter(
                        current.tenantId(), "mobile.max-upload-mb",
                        10, 1, 100
                ),
                safeCount(mapper.inspectionCount(current.tenantId(), current.userId())),
                safeCount(mapper.maintenanceCount(current.tenantId(), current.userId())),
                List.copyOf(mapper.messages(
                        current.tenantId(), current.userId(), 30
                ))
        );
    }

    @Transactional(readOnly = true)
    public MobileDtos.EquipmentContext equipment(String token) {
        var current = SecurityUtils.currentUser();
        assertMobileEnabled(current.tenantId(), current.userId());
        MobileDtos.EquipmentBase equipment = mapper.equipmentByToken(
                current.tenantId(),
                token.toLowerCase(Locale.ROOT),
                dataPermissionService.current()
        );
        if (equipment == null) {
            throw new BusinessException(
                    "MOBILE_EQUIPMENT_NOT_FOUND",
                    "设备二维码无效、已停用或无权访问",
                    HttpStatus.NOT_FOUND
            );
        }
        return new MobileDtos.EquipmentContext(
                equipment,
                List.copyOf(mapper.activeTasks(
                        current.tenantId(), equipment.equipmentId(), current.userId()
                ))
        );
    }

    private void assertMobileEnabled(long tenantId, long userId) {
        if (!Boolean.TRUE.equals(mapper.mobileEnabled(tenantId, userId))) {
            throw new BusinessException(
                    "MOBILE_ACCESS_DISABLED",
                    "当前账号未启用移动端使用权限",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private MobileDtos.WorkCount safeCount(MobileDtos.WorkCount count) {
        return count == null ? new MobileDtos.WorkCount(0, 0, 0, 0) : count;
    }

    private int parameter(
            long tenantId,
            String key,
            int fallback,
            int minimum,
            int maximum
    ) {
        Integer value = mapper.integerParameter(tenantId, key);
        if (value == null) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
