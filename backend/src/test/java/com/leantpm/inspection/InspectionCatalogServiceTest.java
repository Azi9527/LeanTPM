package com.leantpm.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.dto.FoundationDtos;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
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

    @Test
    void updatingAnItemInvalidatesEveryEditableTaskThatReferencesIt() {
        InspectionDtos.ItemRow before = item(3);
        when(mapper.findItem(1L, 100L)).thenReturn(before);
        when(mapper.updateItem(anyLong(), anyLong(), any(), anyString(), anyLong()))
                .thenReturn(1);

        service.updateItem(100L, withVersion(request(
                "NORMAL_ABNORMAL", "NORMAL", null, null, false, List.of()
        ), 3));

        var orderedWrites = inOrder(mapper);
        orderedWrites.verify(mapper).bumpEditableTaskVersionsForItem(1L, 100L, 7L);
        orderedWrites.verify(mapper).updateItem(anyLong(), anyLong(), any(), anyString(), anyLong());
    }

    @Test
    void returnsSixBusinessCategoryDefaultsWhenDictionaryIsUnavailable() {
        when(mapper.findItemCategories(1L)).thenReturn(List.of());

        assertThat(service.itemCategories())
                .extracting(InspectionDtos.ItemCategoryOption::value)
                .containsExactly(
                        "TRANSMISSION", "LUBRICATION", "FASTENING",
                        "ELECTRICAL", "SAFETY", "OTHER"
                );
    }

    @Test
    void skipsExistingGeneratedSchemeCodeAndUsesNextAutomaticNumber() {
        allowValidSchemeReferences();
        when(numberRuleService.generate(1L, 7L, "INSPECTION_SCHEME"))
                .thenReturn(
                        new FoundationDtos.GeneratedNumber(
                                "INSPECTION_SCHEME", "ISP-2026-000001", 1L
                        ),
                        new FoundationDtos.GeneratedNumber(
                                "INSPECTION_SCHEME", "ISP-2026-000002", 2L
                        )
                );
        when(mapper.countSchemeCode(1L, "ISP-2026-000001", null)).thenReturn(1);
        when(mapper.countSchemeCode(1L, "ISP-2026-000002", null)).thenReturn(0);
        when(mapper.findSchemeIdByCode(1L, "ISP-2026-000002")).thenReturn(42L);
        when(mapper.nextSchemeVersionNumber(1L, 42L)).thenReturn(1);
        when(mapper.findSchemeVersionId(1L, 42L, 1)).thenReturn(101L);

        assertThat(service.createScheme(schemeRequest(null))).isEqualTo(42L);

        verify(numberRuleService, times(2)).generate(1L, 7L, "INSPECTION_SCHEME");
        verify(mapper).insertScheme(
                eq(1L), eq("ISP-2026-000002"), any(InspectionDtos.SaveSchemeRequest.class), eq(7L)
        );
    }

    @Test
    void stillRejectsDuplicateManuallyEnteredSchemeCode() {
        allowValidSchemeReferences();
        when(mapper.countSchemeCode(1L, "ISP-MANUAL-001", null)).thenReturn(1);

        assertThatThrownBy(() -> service.createScheme(schemeRequest("ISP-MANUAL-001")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("INSPECTION_SCHEME_CODE_EXISTS");

        verify(numberRuleService, never()).generate(anyLong(), anyLong(), anyString());
        verify(mapper, never()).insertScheme(anyLong(), anyString(), any(), anyLong());
    }

    @Test
    void rejectsDeletingAnEnabledScheme() {
        when(mapper.findScheme(1L, 42L)).thenReturn(scheme(1, 0));

        assertThatThrownBy(() -> service.deleteScheme(42L, 3))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("INSPECTION_SCHEME_ENABLED");

        verify(mapper, never()).softDeleteScheme(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void rejectsDeletingASchemeWithActivePlans() {
        when(mapper.findScheme(1L, 42L)).thenReturn(scheme(0, 2));

        assertThatThrownBy(() -> service.deleteScheme(42L, 3))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("INSPECTION_SCHEME_IN_USE");

        verify(mapper, never()).softDeleteScheme(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void softDeletesOnlyADisabledSchemeWithoutActivePlans() {
        InspectionDtos.SchemeRow before = scheme(0, 0);
        when(mapper.findScheme(1L, 42L)).thenReturn(before);
        when(mapper.softDeleteScheme(1L, 42L, 3, 7L)).thenReturn(1);

        service.deleteScheme(42L, 3);

        verify(mapper).softDeleteScheme(1L, 42L, 3, 7L);
        verify(changeLogService).record("INSPECTION_SCHEME", 42L, "DELETE", before, null);
    }

    @Test
    void rejectsReactivatingAPlanAfterItsSchemeWasDeleted() {
        when(mapper.findPlan(anyLong(), anyLong(), any())).thenReturn(plan("PAUSED"));
        when(mapper.findScheme(1L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> service.updatePlanStatus(
                88L, new InspectionDtos.UpdatePlanStatusRequest("ACTIVE", null, 2)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("INSPECTION_SCHEME_NOT_FOUND");

        verify(mapper, never()).updatePlanStatus(anyLong(), anyLong(), any(), anyLong());
    }

    private InspectionDtos.SchemeRow scheme(int status, int activePlanCount) {
        return new InspectionDtos.SchemeRow(
                42L, "ISP-001", "测试方案", "DAILY", 101L, 1, "PUBLISHED",
                "DAILY", 1, LocalTime.of(8, 0), 4, 1, activePlanCount,
                status, "测试", 3
        );
    }

    private InspectionDtos.PlanRow plan(String status) {
        return new InspectionDtos.PlanRow(
                88L, 42L, "ISP-001", "测试方案", 1,
                9L, "EQ-001", "测试设备", 10L, "测试部门", "测试位置",
                "DAILY", 1, LocalTime.of(8, 0), 60, null,
                7L, "测试用户", LocalDate.of(2026, 8, 12), null,
                status, "暂停", 2
        );
    }

    private InspectionDtos.ItemRow item(int version) {
        return new InspectionDtos.ItemRow(
                100L, "ITEM-001", "测试点检项目", 10L, "测试部门", "OPERATION",
                "测试部位", "测试点检内容", "目视", "工具", "测试点检标准",
                "NORMAL", null, null, "%", "NORMAL_ABNORMAL", "[]", true,
                false, 0, 9, 10, "image/jpeg,image/png", 82, false, false,
                "MEDIUM", "异常时处理", false, 5, "安全说明", 1, "测试说明", version
        );
    }

    private void allowValidSchemeReferences() {
        when(mapper.findItem(1L, 100L)).thenReturn(item(3));
        when(mapper.countActiveCategory(1L, 10L)).thenReturn(1);
        when(calendarMapper.countActiveCalendar(1L, 1L)).thenReturn(1);
    }

    private InspectionDtos.SaveSchemeRequest schemeRequest(String schemeCode) {
        return new InspectionDtos.SaveSchemeRequest(
                schemeCode, "测试方案", "DAILY", "DAILY", 1,
                null, null, LocalTime.of(8, 0), 60, 1L,
                null, null, List.of(), null,
                false, false, false, 1,
                LocalDate.of(2026, 8, 12), null,
                List.of(new InspectionDtos.SaveSchemeItemRequest(
                        100L, 0, true, false, false, false
                )),
                List.of(10L), List.of(), true, null, null, null
        );
    }

    private InspectionDtos.SaveItemRequest withVersion(
            InspectionDtos.SaveItemRequest request,
            int version
    ) {
        return new InspectionDtos.SaveItemRequest(
                request.itemCode(), request.itemName(), request.organizationId(),
                request.itemCategory(), request.inspectionPart(), request.inspectionContent(),
                request.inspectionMethod(), request.inspectionTool(),
                request.inspectionStandard(), request.standardValue(), request.minimumValue(),
                request.maximumValue(), request.unit(), request.resultType(),
                request.resultOptions(), request.required(), request.photoRequired(),
                request.photoMinCount(), request.photoMaxCount(), request.photoMaxSizeMb(),
                request.photoAllowedTypes(), request.photoCompressionQuality(),
                request.numericRequired(), request.skipAllowed(), request.abnormalSeverity(),
                request.abnormalAdvice(), request.abnormalDefaultStop(),
                request.standardMinutes(), request.safetyNotes(), request.enabled(),
                request.description(), version
        );
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
