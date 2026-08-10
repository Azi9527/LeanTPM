package com.leantpm.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import com.leantpm.system.mapper.SystemMapper;
import com.leantpm.system.service.SystemService;
import com.leantpm.system.service.UserImportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserImportServiceSectionManagerTest {
    @Test
    void sectionResponsiblePersonUsesSectionLeaderManagerType() {
        var service = new UserImportService(
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(SystemMapper.class),
                mock(SystemService.class),
                mock(DataPermissionService.class),
                mock(ChangeLogService.class)
        );

        String managerType = ReflectionTestUtils.invokeMethod(
                service, "managerType", "SECTION"
        );

        assertThat(managerType).isEqualTo("LINE_LEADER");
    }
}
