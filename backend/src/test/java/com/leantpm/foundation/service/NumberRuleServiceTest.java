package com.leantpm.foundation.service;

import com.leantpm.foundation.dto.FoundationDtos;
import com.leantpm.foundation.mapper.FoundationMapper;
import com.leantpm.system.audit.ChangeLogService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NumberRuleServiceTest {
    private final FoundationMapper mapper = mock(FoundationMapper.class);
    private final ParameterService parameterService = mock(ParameterService.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final NumberRuleService service =
            new NumberRuleService(mapper, parameterService, changeLogService);

    @Test
    void formatsPrefixDateAndPaddedSequence() {
        var rule = rule("EQUIPMENT", "EQP", "yyyyMMdd", "-", 4, "DAILY");

        String value = service.format(rule, LocalDate.of(2026, 7, 30), 42);

        assertThat(value).isEqualTo("EQP-20260730-0042");
    }

    @Test
    void generatesNumberFromAtomicDatabaseSequence() {
        var rule = rule("WORK_ORDER", "WO", "", "-", 4, "NEVER");
        when(mapper.findNumberRuleByCode(1L, "WORK_ORDER")).thenReturn(rule);
        when(parameterService.getString(1L, "system.timezone", "Asia/Shanghai"))
                .thenReturn("Asia/Shanghai");
        when(mapper.findCurrentSequence(1L, 1L, "ALL")).thenReturn(7L);

        var result = service.generate(1L, 9L, "work_order");

        assertThat(result.businessNumber()).isEqualTo("WO-0007");
        assertThat(result.sequence()).isEqualTo(7L);
        verify(mapper).advanceSequence(1L, 1L, "ALL", 9L);
    }

    private FoundationDtos.NumberRuleRow rule(
            String code,
            String prefix,
            String datePattern,
            String separator,
            int sequenceLength,
            String resetPeriod
    ) {
        return new FoundationDtos.NumberRuleRow(
                1L,
                code,
                "测试规则",
                prefix,
                datePattern,
                separator,
                sequenceLength,
                resetPeriod,
                1,
                null,
                LocalDateTime.of(2026, 7, 30, 10, 0),
                0,
                null
        );
    }
}
