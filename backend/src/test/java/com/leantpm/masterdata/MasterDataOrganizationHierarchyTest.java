package com.leantpm.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MasterDataOrganizationHierarchyTest {
    private final MasterDataMapper mapper = mock(MasterDataMapper.class);
    private final DataPermissionService dataPermissionService = mock(DataPermissionService.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final MasterDataService service = new MasterDataService(
            mapper,
            dataPermissionService,
            changeLogService,
            new ObjectMapper(),
            mock(JdbcTemplate.class)
    );

    @BeforeEach
    void setUp() {
        var administrator = new CurrentUser(
                1L, 1L, "admin", "系统管理员", false,
                Set.of("ADMIN"), Set.of("master-data:organization:manage"), "section-test"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(administrator, null, Set.of())
        );
        when(dataPermissionService.current()).thenReturn(DataPermission.all(1L));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsSectionUnderWorkshopAndTeamUnderSection() {
        var workshop = organization(20L, 10L, "WORKSHOP-A", "车间A", "WORKSHOP");
        var section = organization(30L, 20L, "SECTION-A", "工段A", "SECTION");
        when(mapper.findOrganization(1L, 20L)).thenReturn(workshop);
        when(mapper.findOrganizationIdByCode(1L, "SECTION-A")).thenReturn(30L);

        service.createOrganization(request(20L, "SECTION-A", "工段A", "SECTION"));

        verify(mapper).insertOrganization(
                anyLong(), any(MasterDataDtos.SaveOrganizationRequest.class), anyLong()
        );

        when(mapper.findOrganization(1L, 30L)).thenReturn(section);
        when(mapper.findOrganizationIdByCode(1L, "TEAM-A-1")).thenReturn(40L);

        service.createOrganization(request(30L, "TEAM-A-1", "一班", "TEAM"));
    }

    @Test
    void keepsDirectTeamUnderWorkshopCompatible() {
        when(mapper.findOrganization(1L, 20L)).thenReturn(
                organization(20L, 10L, "WORKSHOP-A", "车间A", "WORKSHOP")
        );
        when(mapper.findOrganizationIdByCode(1L, "TEAM-DIRECT")).thenReturn(41L);

        service.createOrganization(request(20L, "TEAM-DIRECT", "直属班组", "TEAM"));

        verify(mapper).insertOrganization(
                anyLong(), any(MasterDataDtos.SaveOrganizationRequest.class), anyLong()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ENTERPRISE", "FACTORY", "DEPARTMENT", "WORKSHOP",
            "LINE", "SECTION", "TEAM"
    })
    void allowsEverySupportedTypeUnderAnyParentType(String organizationType) {
        when(mapper.findOrganization(1L, 10L)).thenReturn(
                organization(10L, 1L, "FACTORY-A", "工厂A", "FACTORY")
        );
        String code = organizationType + "-A";
        when(mapper.findOrganizationIdByCode(1L, code)).thenReturn(31L);

        service.createOrganization(request(10L, code, "任意类型组织", organizationType));

        verify(mapper).insertOrganization(
                anyLong(), any(MasterDataDtos.SaveOrganizationRequest.class), anyLong()
        );
    }

    @Test
    void allowsAnySupportedTypeAsRoot() {
        when(mapper.findOrganizationIdByCode(1L, "TEAM-ROOT")).thenReturn(32L);

        service.createOrganization(request(0L, "TEAM-ROOT", "根班组", "TEAM"));

        verify(mapper).insertOrganization(
                anyLong(), any(MasterDataDtos.SaveOrganizationRequest.class), anyLong()
        );
    }

    @Test
    void stillRejectsOrganizationCycles() {
        var current = organization(30L, 20L, "SECTION-A", "工段A", "SECTION");
        var child = organization(40L, 30L, "TEAM-A", "一班", "TEAM");
        when(mapper.findOrganization(1L, 30L)).thenReturn(current);
        when(mapper.findOrganization(1L, 40L)).thenReturn(child);
        when(mapper.findOrganizations(1L)).thenReturn(List.of(current, child));

        var update = new MasterDataDtos.SaveOrganizationRequest(
                40L, "SECTION-A", "工段A", "FACTORY",
                null, 10, true, null, 0
        );

        assertThatThrownBy(() -> service.updateOrganization(30L, update))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("HIERARCHY_INVALID");
    }

    @Test
    void saveRequestValidationAcceptsSectionType() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(
                    request(20L, "SECTION-A", "工段A", "SECTION")
            )).isEmpty();
        }
    }

    private MasterDataDtos.OrganizationRow organization(
            long id,
            long parentId,
            String code,
            String name,
            String type
    ) {
        return new MasterDataDtos.OrganizationRow(
                id, parentId, code, name, type,
                null, null, 10, 1, null, 0
        );
    }

    private MasterDataDtos.SaveOrganizationRequest request(
            long parentId,
            String code,
            String name,
            String type
    ) {
        return new MasterDataDtos.SaveOrganizationRequest(
                parentId, code, name, type,
                null, 10, true, null, null
        );
    }
}
