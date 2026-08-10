package com.leantpm.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import com.leantpm.system.dto.SystemDtos;
import com.leantpm.system.dto.UserImportDtos;
import com.leantpm.system.mapper.SystemMapper;
import com.leantpm.system.service.SystemService;
import com.leantpm.system.service.UserImportService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserImportServiceUnifiedManagerTest {
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private SystemMapper mapper;
    @Mock
    private SystemService systemService;
    @Mock
    private DataPermissionService dataPermissionService;
    @Mock
    private ChangeLogService changeLogService;

    private UserImportService service;
    private SystemDtos.OrganizationNode line;

    @BeforeEach
    void setUp() {
        service = new UserImportService(
                jdbc, new ObjectMapper(), mapper, systemService,
                dataPermissionService, changeLogService
        );
        line = new SystemDtos.OrganizationNode(
                30L, 20L, "LINE-FX", "浮选工段", "LINE", 1
        );
        lenient().when(systemService.organizations()).thenReturn(List.of(line));
        lenient().when(dataPermissionService.current()).thenReturn(DataPermission.all(1L));
        lenient().when(systemService.roles()).thenReturn(List.of(new SystemDtos.RoleRow(
                8L, "LINE_LEADER", "工段长", "ORGANIZATION_AND_CHILDREN",
                1, 8, null, 0, List.of(), List.of()
        )));
        CurrentUser administrator = new CurrentUser(
                1L, 1L, "admin", "系统管理员", false,
                Set.of("ADMIN"), Set.of("system:user:import"), "test-session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(administrator, null, Set.of())
        );
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void templateUsesOneResponsibleOrganizationColumn() throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.template()))) {
            var header = workbook.getSheet("用户导入").getRow(0);

            assertThat(header.getCell(12).getStringCellValue())
                    .isEqualTo("负责组织编码");
        }
    }

    @Test
    void legacyHeaderAcceptsOneLineOrganizationAsUnifiedManager() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.template()));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("用户导入");
            sheet.getRow(0).getCell(12).setCellValue("负责组织编码列表");
            var row = sheet.getRow(1);
            row.getCell(0).setCellValue("line_manager_01");
            row.getCell(1).setCellValue("浮选工段长");
            row.getCell(2).setCellValue("LM-001");
            row.getCell(5).setCellValue("LINE-FX");
            row.getCell(6).setCellValue("LINE_LEADER");
            row.getCell(8).setCellValue("888888");
            row.getCell(10).setCellValue("");
            row.getCell(11).setCellValue("");
            row.createCell(12).setCellValue("LINE-FX");
            workbook.write(output);
            content = output.toByteArray();
        }

        var result = service.validate(new MockMultipartFile(
                "file", "legacy-users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content
        ));

        assertThat(result.status())
                .withFailMessage("validation errors: %s", result.errors())
                .isEqualTo("VALIDATED");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsMoreThanOneResponsibleOrganizationInOneCell() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.template()));
             var output = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("用户导入").getRow(1);
            row.getCell(0).setCellValue("line_manager_02");
            row.getCell(1).setCellValue("重复负责组织");
            row.getCell(2).setCellValue("LM-002");
            row.getCell(5).setCellValue("LINE-FX");
            row.getCell(6).setCellValue("LINE_LEADER");
            row.getCell(8).setCellValue("888888");
            row.getCell(10).setCellValue("");
            row.getCell(11).setCellValue("");
            row.createCell(12).setCellValue("LINE-FX,LINE-QM");
            workbook.write(output);
            content = output.toByteArray();
        }

        var result = service.validate(new MockMultipartFile(
                "file", "multiple-responsible-organizations.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content
        ));

        assertThat(result.status()).isEqualTo("INVALID");
        assertThat(result.errors()).anySatisfy(error -> {
            assertThat(error.column()).isEqualTo("负责组织编码");
            assertThat(error.message()).contains("只能填写一个");
        });
    }

    @Test
    void assignmentReplacesHistoricalManagersWithOneUnifiedManager() {
        var input = new UserImportDtos.UserInput(
                2, "line_manager_01", "浮选工段长", "LM-001",
                null, null, "LINE-FX", List.of("LINE_LEADER"), true,
                "888888", "ADD_ONLY", List.of(), null, List.of("LINE-FX")
        );
        when(mapper.findPersonnelOrganization(1L, 30L)).thenReturn(
                new SystemDtos.PersonnelOrganizationRow(
                        30L, 20L, "LINE-FX", "浮选工段", "LINE",
                        null, null, 1, 4, List.of(), List.of(), ""
                )
        );
        when(mapper.updateOrganizationManager(1L, 30L, 77L, 4, 1L)).thenReturn(1);

        ReflectionTestUtils.invokeMethod(
                service, "synchronizeOrganizationRelationships",
                1L, 77L, input, Map.of("LINE-FX", line), 1L
        );

        verify(mapper).updateOrganizationManager(1L, 30L, 77L, 4, 1L);
        verify(mapper).deleteOrganizationManagers(1L, 30L, 1L);
        verify(mapper).insertOrganizationManager(
                1L, 30L, 77L, "LINE_LEADER", 0, 1L
        );
    }
}
