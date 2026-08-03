package com.leantpm.inspection;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface InspectionCalendarMapper {
    List<InspectionCalendarDtos.CalendarRow> findCalendars(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            @Param("status") Integer status
    );

    InspectionCalendarDtos.CalendarRow findCalendar(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int countCalendarName(
            @Param("tenantId") long tenantId,
            @Param("calendarName") String calendarName,
            @Param("excludeId") Long excludeId
    );

    int clearDefault(@Param("tenantId") long tenantId, @Param("operatorId") long operatorId);

    int insertCalendar(
            @Param("tenantId") long tenantId,
            @Param("request") InspectionCalendarDtos.SaveCalendarRequest request,
            @Param("operatorId") long operatorId
    );

    Long findCalendarIdByName(
            @Param("tenantId") long tenantId,
            @Param("calendarName") String calendarName
    );

    Long findDefaultCalendarId(@Param("tenantId") long tenantId);

    int countActiveCalendar(@Param("tenantId") long tenantId, @Param("id") long id);

    String findEffectiveDayType(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId,
            @Param("date") LocalDate date
    );

    int updateCalendar(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("request") InspectionCalendarDtos.SaveCalendarRequest request,
            @Param("operatorId") long operatorId
    );

    int countCalendarReferences(@Param("tenantId") long tenantId, @Param("id") long id);

    int deleteCalendar(
            @Param("tenantId") long tenantId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );

    List<InspectionCalendarDtos.CalendarExceptionRow> findExceptions(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId
    );

    InspectionCalendarDtos.CalendarExceptionRow findException(
            @Param("tenantId") long tenantId,
            @Param("id") long id
    );

    int countConflictingExceptions(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId,
            @Param("excludeId") Long excludeId,
            @Param("request") InspectionCalendarDtos.SaveExceptionRequest request
    );

    int insertException(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId,
            @Param("request") InspectionCalendarDtos.SaveExceptionRequest request,
            @Param("operatorId") long operatorId
    );

    Long findLatestExceptionId(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId,
            @Param("operatorId") long operatorId
    );

    int updateException(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId,
            @Param("id") long id,
            @Param("request") InspectionCalendarDtos.SaveExceptionRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteException(
            @Param("tenantId") long tenantId,
            @Param("calendarId") long calendarId,
            @Param("id") long id,
            @Param("version") int version,
            @Param("operatorId") long operatorId
    );
}
