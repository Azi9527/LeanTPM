package com.leantpm.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MasterDataOrganizationCodeTest {
    private final MasterDataMapper mapper = mock(MasterDataMapper.class);
    private final MasterDataService service = new MasterDataService(
            mapper,
            mock(DataPermissionService.class),
            mock(ChangeLogService.class),
            new ObjectMapper(),
            mock(JdbcTemplate.class)
    );

    @BeforeEach
    void setUp() {
        CurrentUser admin = new CurrentUser(
                1L,
                1L,
                "admin",
                "系统管理员",
                false,
                Set.of("ADMIN"),
                Set.of("master-data:organization:manage"),
                "organization-code-unit"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, Set.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsPreviouslyUsedOrganizationCodeBeforeInsert() {
        when(mapper.countOrganizationCode(1L, "TEAM-QM-1", null)).thenReturn(1);
        var request = new MasterDataDtos.SaveOrganizationRequest(
                27L,
                "TEAM-QM-1",
                "一班",
                "TEAM",
                null,
                0,
                true,
                null,
                null
        );

        assertThatThrownBy(() -> service.createOrganization(request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("ORGANIZATION_CODE_EXISTS");
                    assertThat(exception.getMessage())
                            .isEqualTo("组织编码已存在或曾被使用，请更换编码");
                });
        verify(mapper, never()).insertOrganization(anyLong(), any(), anyLong());
    }

    @Test
    void mapperCodeCheckIncludesSoftDeletedHistory() throws IOException {
        String mapperXml;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "mapper/masterdata/MasterDataMapper.xml"
        )) {
            if (input == null) {
                throw new AssertionError("MasterDataMapper.xml is missing");
            }
            mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        var matcher = Pattern.compile(
                "<select id=\"countOrganizationCode\".*?</select>",
                Pattern.DOTALL
        ).matcher(mapperXml);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group())
                .contains("tenant_id = #{tenantId}")
                .contains("organization_code = #{code}")
                .doesNotContain("deleted = 0");
    }
}
