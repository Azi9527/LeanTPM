package com.leantpm.equipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.foundation.service.NumberRuleService;
import com.leantpm.foundation.service.ParameterService;
import com.leantpm.masterdata.MasterDataMapper;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.datascope.DataPermission;
import com.leantpm.security.datascope.DataPermissionService;
import com.leantpm.system.audit.ChangeLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipmentServiceTest {
    private final EquipmentMapper mapper = mock(EquipmentMapper.class);
    private final MasterDataMapper masterDataMapper = mock(MasterDataMapper.class);
    private final DataPermissionService dataPermissionService = mock(DataPermissionService.class);
    private final NumberRuleService numberRuleService = mock(NumberRuleService.class);
    private final ParameterService parameterService = mock(ParameterService.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final EquipmentService service = new EquipmentService(
            mapper,
            masterDataMapper,
            dataPermissionService,
            numberRuleService,
            parameterService,
            changeLogService,
            new ObjectMapper()
    );

    @BeforeEach
    void authenticate() {
        CurrentUser user = new CurrentUser(
                7L, 1L, "tester", "测试用户", false,
                Set.of("ADMIN"), Set.of("equipment:status:update"), "session"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
        when(dataPermissionService.current()).thenReturn(DataPermission.all(7L));
        when(mapper.findEquipment(eq(1L), eq(100L), any())).thenReturn(equipment());
        when(mapper.countStatusCode(eq(1L), any())).thenReturn(1);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsIllegalStatusTransitionBeforeWritingHistory() {
        when(mapper.findCurrentStatus(1L, 100L)).thenReturn(
                new EquipmentMapper.CurrentStatus(
                        1L, "SCRAPPED", LocalDateTime.now().minusHours(1),
                        null, "MANUAL", 3
                )
        );

        assertThatThrownBy(() -> service.changeStatus(
                100L,
                new EquipmentDtos.ChangeStatusRequest("RUNNING", "非法跳转", "MANUAL", 3)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("STATUS_TRANSITION_INVALID");

        verify(mapper, never()).updateCurrentStatus(
                anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong()
        );
    }

    @Test
    void recordsLegalStatusTransitionWithOptimisticVersion() {
        EquipmentMapper.CurrentStatus current = new EquipmentMapper.CurrentStatus(
                1L, "RUNNING", LocalDateTime.now().minusHours(1),
                null, "MANUAL", 3
        );
        EquipmentMapper.CurrentStatus updated = new EquipmentMapper.CurrentStatus(
                1L, "STOPPED", LocalDateTime.now(), "异常停机", "MANUAL", 4
        );
        when(mapper.findCurrentStatus(1L, 100L)).thenReturn(current, updated);
        when(mapper.updateCurrentStatus(
                eq(1L), eq(100L), eq("STOPPED"), any(), eq("异常停机"),
                eq("MANUAL"), eq(3), eq(7L)
        )).thenReturn(1);

        service.changeStatus(
                100L,
                new EquipmentDtos.ChangeStatusRequest(
                        "STOPPED", "异常停机", "MANUAL", 3
                )
        );

        verify(mapper).closeOpenStatusHistory(eq(1L), eq(100L), any());
        verify(mapper).insertStatusHistory(
                eq(1L), eq(100L), eq("RUNNING"), eq("STOPPED"), any(),
                eq("异常停机"), eq("MANUAL"), eq(7L)
        );
        verify(changeLogService).record(
                eq("EQUIPMENT_STATUS"), eq(100L), eq("UPDATE"), eq(current), eq(updated)
        );
    }

    @Test
    void rendersCustomerApprovedPremiumBlueEquipmentLabel() {
        BufferedImage qrCode = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = qrCode.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, qrCode.getWidth(), qrCode.getHeight());
        graphics.dispose();

        BufferedImage label = EquipmentService.withPremiumEquipmentLabel(
                qrCode,
                "大宝山设备管理系统",
                "循环泵站一号",
                "VIZ-PUMP-01"
        );

        assertThat(label.getWidth()).isEqualTo(240);
        assertThat(label.getHeight()).isEqualTo(320);
        assertThat(label.getRGB(8, 8) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        assertThat(label.getRGB(8, 150) & 0xFFFFFF).isNotEqualTo(0xFFFFFF);
        assertThat(label.getRGB(120, 302) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void keepsStyledQrDecodableWhenCenterLogoIsApplied() throws Exception {
        String content = "https://equipment.example/m/e/scan-safe-token";
        BufferedImage logo = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var graphics = logo.createGraphics();
        graphics.setColor(new Color(0, 160, 136));
        graphics.fillRoundRect(0, 0, 64, 64, 12, 12);
        graphics.dispose();

        BufferedImage qrCode = EquipmentService.renderStyledQr(content, 480, logo);
        int[] pixels = qrCode.getRGB(
                0, 0, qrCode.getWidth(), qrCode.getHeight(), null, 0, qrCode.getWidth()
        );
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(qrCode.getWidth(), qrCode.getHeight(), pixels)
        ));

        assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(content);
        Color center = new Color(qrCode.getRGB(240, 240), true);
        assertThat(center.getGreen()).isGreaterThan(center.getRed());
    }

    private EquipmentDtos.EquipmentRow equipment() {
        LocalDateTime now = LocalDateTime.now();
        return new EquipmentDtos.EquipmentRow(
                100L,
                "EQ-100",
                "测试设备",
                10L,
                "PUMP",
                "泵",
                "M1",
                null,
                null,
                null,
                null,
                null,
                null,
                20L,
                "WORKSHOP-A",
                "一车间",
                30L,
                "SITE-A",
                "A工位",
                7L,
                "tester",
                "测试用户",
                null,
                "IN_SERVICE",
                false,
                false,
                true,
                1,
                null,
                "RUNNING",
                now.minusHours(1),
                3600L,
                3,
                null,
                null,
                now.minusDays(1),
                now,
                2
        );
    }
}
