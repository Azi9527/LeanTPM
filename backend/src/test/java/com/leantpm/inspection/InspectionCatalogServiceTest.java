package com.leantpm.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectionCatalogServiceTest {
    private final InspectionMapper mapper = mock(InspectionMapper.class);
    private final InspectionCalendarMapper calendarMapper = mock(InspectionCalendarMapper.class);
    private final NumberRuleService numberRuleService = mock(NumberRuleService.class);
    private final DataPermissionService dataPermissionService = mock(DataPermissionService.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final InspectionTaskService taskService = mock(InspectionTaskService.class);
    private final InspectionCatalogService service = new InspectionCatalogService(
            mapper, calendarMapper, numberRuleService, dataPermissionService,
            changeLogService, new ObjectMapper(), taskService
    );

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(
                7L, 1L, "tester", "测试用户", false,
                Set.of("ADMIN"), Set.of("inspection:item:manage"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
        when(dataPermissionService.current()).thenReturn(DataPermission.all(7L));
        when(mapper.findItemIdByCode(1L, "ITEM-001")).thenReturn(100L);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalAbnormalUsesFixedBusinessOptionsAndClearsNumericRules() {
        service.createItem(request(
                "NORMAL_ABNORMAL", "6", BigDecimal.ZERO, BigDecimal.TEN,
                true, List.of("自定义")
        ));

        ArgumentCaptor<InspectionDtos.SaveItemRequest> requestCaptor =
                ArgumentCaptor.forClass(InspectionDtos.SaveItemRequest.class);
        verify(mapper).insertItem(anyLong(), requestCaptor.capture(), anyString(), anyLong());
        InspectionDtos.SaveItemRequest saved = requestCaptor.getValue();
        assertThat(saved.standardValue()).isEqualTo("NORMAL");
        assertThat(saved.resultOptions()).containsExactly("NORMAL", "ABNORMAL");
        assertThat(saved.numericRequired()).isFalse();
        assertThat(saved.minimumValue()).isNull();
        assertThat(saved.maximumValue()).isNull();
    }

    @Test
    void numberResultForcesNumericRuleAndClearsTextStandard() {
        service.createItem(request(
                "NUMBER", "错误的文本标准", new BigDecimal("30"),
                new BigDecimal("80"), false, List.of()
        ));

        ArgumentCaptor<InspectionDtos.SaveItemRequest> requestCaptor =
                ArgumentCaptor.forClass(InspectionDtos.SaveItemRequest.class);
        verify(mapper).insertItem(anyLong(), requestCaptor.capture(), anyString(), anyLong());
        InspectionDtos.SaveItemRequest saved = requestCaptor.getValue();
        assertThat(saved.standardValue()).isNull();
        assertThat(saved.numericRequired()).isTrue();
        assertThat(saved.minimumValue()).isEqualByComparingTo("30");
        assertThat(saved.maximumValue()).isEqualByComparingTo("80");
    }

    @Test
    void numberResultWithoutAnyBoundaryIsRejectedBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.createItem(request(
                "NUMBER", null, null, null, true, List.of()
        )))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("INSPECTION_ITEM_NUMBER_RANGE_REQUIRED");

        verify(mapper, never()).insertItem(anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void createsTenantSharedInspectionStandardWithoutOrganizationScope() {
        InspectionDtos.SaveItemRequest request = request(
                "NORMAL_ABNORMAL", "NORMAL", null, null, false, List.of()
        );
        InspectionDtos.SaveItemRequest shared = new InspectionDtos.SaveItemRequest(
                request.itemCode(), request.itemName(), null, request.itemCategory(),
                request.inspectionPart(), request.inspectionContent(),
                request.inspectionMethod(), request.inspectionTool(),
                request.inspectionStandard(), request.standardValue(),
                request.minimumValue(), request.maximumValue(), request.unit(),
                request.resultType(), request.resultOptions(), request.required(),
                request.photoRequired(), request.photoMinCount(), request.photoMaxCount(),
                request.photoMaxSizeMb(), request.photoAllowedTypes(),
                request.photoCompressionQuality(), request.numericRequired(),
                request.skipAllowed(), request.abnormalSeverity(), request.abnormalAdvice(),
                request.abnormalDefaultStop(), request.standardMinutes(), request.safetyNotes(),
                request.enabled(), request.description(), request.version()
        );

        service.createItem(shared);

        ArgumentCaptor<InspectionDtos.SaveItemRequest> requestCaptor =
                ArgumentCaptor.forClass(InspectionDtos.SaveItemRequest.class);
        verify(mapper).insertItem(anyLong(), requestCaptor.capture(), anyString(), anyLong());
        assertThat(requestCaptor.getValue().organizationId()).isNull();
    }

    private InspectionDtos.SaveItemRequest request(
            String resultType,
            String standardValue,
            BigDecimal minimumValue,
            BigDecimal maximumValue,
            boolean numericRequired,
            List<String> resultOptions
    ) {
        return new InspectionDtos.SaveItemRequest(
                "ITEM-001", "测试点检项目", 10L, "OPERATION",
                "测试部位", "测试点检内容", "目视", "工具",
                "测试点检标准", standardValue, minimumValue, maximumValue, "%",
                resultType, resultOptions, true, false, 0, 9, 10,
                "image/jpeg,image/png", 82, numericRequired, false, "MEDIUM",
                "异常时处理", false, 5, "安全说明", true, "测试说明", null
        );
    }
}
