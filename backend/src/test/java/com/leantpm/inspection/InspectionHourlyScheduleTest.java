package com.leantpm.inspection;

import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionHourlyScheduleTest {

    @Test
    void acceptsHourlyAsASchemeCycleType() throws NoSuchMethodException {
        Pattern constraint = InspectionDtos.SaveSchemeRequest.class
                .getDeclaredMethod("cycleType")
                .getAnnotation(Pattern.class);
        String expression = constraint.regexp();

        assertThat("HOURLY").matches(expression);
        assertThat("MINUTELY").doesNotMatch(expression);
    }

    @Test
    void advancesHourlyScheduleWithinTheSameDay() {
        LocalDateTime next = InspectionTaskService.nextOccurrence(
                plan("HOURLY", 1, LocalTime.of(8, 0)),
                LocalDateTime.of(2026, 8, 13, 8, 0)
        );

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 13, 9, 0));
    }

    @Test
    void advancesHourlyScheduleAcrossMidnight() {
        LocalDateTime next = InspectionTaskService.nextOccurrence(
                plan("HOURLY", 2, LocalTime.of(23, 0)),
                LocalDateTime.of(2026, 8, 13, 23, 0)
        );

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 14, 1, 0));
    }

    @Test
    void keepsDailyScheduleAtTheConfiguredTime() {
        LocalDateTime next = InspectionTaskService.nextOccurrence(
                plan("DAILY", 2, LocalTime.of(8, 30)),
                LocalDateTime.of(2026, 8, 13, 8, 30)
        );

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 15, 8, 30));
    }

    @Test
    void skipsOldHourlySlotsWhenANewerSlotIsAlreadyDue() {
        InspectionMapper.GenerationPlan plan = plan(
                "HOURLY", 1, LocalTime.of(8, 0), 60
        );
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 13, 13, 5);

        assertThat(InspectionTaskService.isMissedOccurrence(
                plan, LocalDateTime.of(2026, 8, 13, 8, 0), cutoff
        )).isTrue();
        assertThat(InspectionTaskService.isMissedOccurrence(
                plan, LocalDateTime.of(2026, 8, 13, 14, 0), cutoff
        )).isFalse();
    }

    @Test
    void closesHourlyTaskOneSecondBeforeTheNextOccurrence() {
        LocalDateTime due = InspectionTaskService.taskDueTime(
                plan("HOURLY", 1, LocalTime.of(8, 0)),
                LocalDateTime.of(2026, 8, 13, 16, 30)
        );

        assertThat(due).isEqualTo(LocalDateTime.of(2026, 8, 13, 17, 29, 59));
    }

    @Test
    void closesHourlyTaskAcrossMidnightBeforeTheNextOccurrence() {
        LocalDateTime due = InspectionTaskService.taskDueTime(
                plan("HOURLY", 2, LocalTime.of(23, 0)),
                LocalDateTime.of(2026, 8, 13, 23, 0)
        );

        assertThat(due).isEqualTo(LocalDateTime.of(2026, 8, 14, 0, 59, 59));
    }

    @Test
    void keepsNonHourlyTaskDueAtTheEndOfItsPlannedDay() {
        LocalDateTime due = InspectionTaskService.taskDueTime(
                plan("DAILY", 1, LocalTime.of(8, 0)),
                LocalDateTime.of(2026, 8, 13, 8, 0)
        );

        assertThat(due).isEqualTo(LocalDateTime.of(2026, 8, 13, 23, 59, 59));
    }

    private InspectionMapper.GenerationPlan plan(
            String cycleType,
            int cycleInterval,
            LocalTime scheduledTime
    ) {
        return plan(cycleType, cycleInterval, scheduledTime, 0);
    }

    private InspectionMapper.GenerationPlan plan(
            String cycleType,
            int cycleInterval,
            LocalTime scheduledTime,
            int generationLeadMinutes
    ) {
        return new InspectionMapper.GenerationPlan(
                1L, 2L, 3L, "ISP-HOURLY", "每小时点检", 1,
                "DAILY", 4L, cycleType, cycleInterval, null, null,
                scheduledTime, generationLeadMinutes, 5L,
                "1,2,3,4,5,6,7", 6L, null,
                false, false, LocalDate.of(2026, 8, 13), null,
                LocalDate.of(2026, 8, 13)
        );
    }
}
