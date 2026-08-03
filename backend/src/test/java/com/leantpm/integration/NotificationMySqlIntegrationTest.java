package com.leantpm.integration;

import com.leantpm.notification.NotificationDtos;
import com.leantpm.notification.NotificationService;
import com.leantpm.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${LEANTPM_TEST_DB_URL}",
                "spring.datasource.username=${LEANTPM_TEST_DB_USERNAME:root}",
                "spring.datasource.password=${LEANTPM_TEST_DB_PASSWORD:}",
                "spring.data.redis.repositories.enabled=false",
                "management.health.redis.enabled=false",
                "leantpm.security.jwt-secret=integration-test-secret-at-least-32-characters",
                "leantpm.bootstrap.admin-password=",
                "leantpm.notification.initial-delay-ms=3600000"
        }
)
@DirtiesContext
@Transactional
class NotificationMySqlIntegrationTest {
    private static final long ASSIGNEE_ID = 9301L;
    private static final long TEAM_LEADER_ID = 9302L;
    private static final long WORKSHOP_MANAGER_ID = 9303L;
    private static final long DUE_TASK_ID = 9391L;
    private static final long MANUAL_TASK_ID = 9392L;
    private static final long OVERDUE_TASK_ID = 9393L;
    private static final long NO_MANAGER_TASK_ID = 9394L;

    @Autowired
    private NotificationService service;

    @Autowired
    private JdbcTemplate jdbc;

    private long teamId;
    private long equipmentId;
    private long locationId;

    @BeforeEach
    void prepare() {
        teamId = jdbc.queryForObject(
                "SELECT id FROM organization WHERE tenant_id = 1 AND organization_code = 'TEAM-A-1'",
                Long.class
        );
        equipmentId = jdbc.queryForObject(
                "SELECT id FROM equipment WHERE tenant_id = 1 AND equipment_code = 'VIZ-PUMP-01'",
                Long.class
        );
        locationId = jdbc.queryForObject(
                "SELECT location_id FROM equipment WHERE tenant_id = 1 AND id = ?",
                Long.class, equipmentId
        );
        insertUser(ASSIGNEE_ID, "notification_operator", "提醒执行人", teamId, "OPERATOR");
        insertUser(TEAM_LEADER_ID, "notification_team_leader", "提醒班组长", teamId, "TEAM_LEADER");
        insertUser(WORKSHOP_MANAGER_ID, "notification_workshop_manager", "提醒车间主任", 3L, "WORKSHOP_MANAGER");
        insertTask(DUE_TASK_ID, "NOTIFY-DUE", teamId, "TEAM-A-1", "PLAN", LocalDateTime.now().plusMinutes(30));
        insertTask(MANUAL_TASK_ID, "NOTIFY-MANUAL", teamId, "TEAM-A-1", "MANUAL", LocalDateTime.now().plusDays(1));
        insertTask(OVERDUE_TASK_ID, "NOTIFY-OVERDUE", teamId, "TEAM-A-1", "PLAN", LocalDateTime.now().minusMinutes(300));
        insertTask(NO_MANAGER_TASK_ID, "NOTIFY-NO-MANAGER", 1L, null, "PLAN", LocalDateTime.now().minusMinutes(300));
        authenticate(1L, "admin", "系统管理员", Set.of("ADMIN"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void triggersDueManualAndTwoLevelOverdueMessagesWithoutDuplicates() {
        NotificationDtos.ScanResult first = service.scanTenant(1L);

        assertThat(first.createdMessages()).isGreaterThanOrEqualTo(6);
        assertThat(messageCount(DUE_TASK_ID)).isEqualTo(1);
        assertThat(messageCount(MANUAL_TASK_ID)).isEqualTo(1);
        assertThat(messageCount(OVERDUE_TASK_ID)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_message
                WHERE tenant_id = 1 AND business_type = 'INSPECTION'
                  AND business_id = ? AND message_type IN (
                    'INSPECTION_OVERDUE_ASSIGNEE',
                    'INSPECTION_OVERDUE_TEAM',
                    'INSPECTION_OVERDUE_WORKSHOP'
                  )
                """, Long.class, OVERDUE_TASK_ID)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_delivery delivery
                JOIN notification_message message ON message.id = delivery.message_id
                WHERE message.tenant_id = 1 AND message.business_id IN (?, ?, ?)
                """, Long.class, DUE_TASK_ID, MANUAL_TASK_ID, OVERDUE_TASK_ID)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_delivery delivery
                JOIN notification_message message ON message.id = delivery.message_id
                WHERE message.tenant_id = 1 AND message.business_id IN (?, ?, ?)
                  AND ((delivery.channel_code = 'SYSTEM' AND delivery.delivery_status = 'SENT')
                    OR (delivery.channel_code = 'ANDROID' AND delivery.delivery_status = 'READY'))
                """, Long.class, DUE_TASK_ID, MANUAL_TASK_ID, OVERDUE_TASK_ID)).isEqualTo(10L);

        service.scanTenant(1L);
        assertThat(messageCount(DUE_TASK_ID)).isEqualTo(1);
        assertThat(messageCount(MANUAL_TASK_ID)).isEqualTo(1);
        assertThat(messageCount(OVERDUE_TASK_ID)).isEqualTo(3);
    }

    @Test
    void recordsMissingManagerStopsClosedTaskAndSupportsReadAcknowledgement() {
        service.scanTenant(1L);
        assertThat(jdbc.queryForObject("""
                SELECT escalation_status FROM notification_escalation
                WHERE tenant_id = 1 AND business_type = 'INSPECTION' AND business_id = ?
                """, String.class, NO_MANAGER_TASK_ID)).isEqualTo("NO_RECIPIENT");
        assertThat(jdbc.queryForObject("""
                SELECT status_reason FROM notification_escalation
                WHERE tenant_id = 1 AND business_type = 'INSPECTION' AND business_id = ?
                """, String.class, NO_MANAGER_TASK_ID)).contains("车间主任");

        authenticate(ASSIGNEE_ID, "notification_operator", "提醒执行人", Set.of("OPERATOR"));
        NotificationDtos.MessageRow manual = service.messages(false, 1, 100).records().stream()
                .filter(message -> message.businessId() == MANUAL_TASK_ID)
                .findFirst().orElseThrow();
        assertThat(manual.acknowledgeRequired()).isTrue();
        service.acknowledge(manual.id());
        NotificationDtos.MessageRow acknowledged = service.messages(false, 1, 100).records().stream()
                .filter(message -> message.id() == manual.id()).findFirst().orElseThrow();
        assertThat(acknowledged.readTime()).isNotNull();
        assertThat(acknowledged.acknowledgedTime()).isNotNull();

        jdbc.update("UPDATE inspection_task SET task_status = 'COMPLETED' WHERE id = ?", OVERDUE_TASK_ID);
        service.scanTenant(1L);
        assertThat(jdbc.queryForObject("""
                SELECT escalation_status FROM notification_escalation
                WHERE tenant_id = 1 AND business_type = 'INSPECTION' AND business_id = ?
                """, String.class, OVERDUE_TASK_ID)).isEqualTo("STOPPED");
    }

    private void insertUser(long id, String username, String name, long organizationId, String roleCode) {
        jdbc.update("""
                INSERT INTO system_user
                    (id, tenant_id, username, password_hash, real_name,
                     organization_id, status, mobile_enabled, must_change_password)
                VALUES (?, 1, ?, 'not-used', ?, ?, 1, 1, 0)
                """, id, username, name, organizationId);
        jdbc.update("""
                INSERT INTO system_user_role (tenant_id, user_id, role_id)
                SELECT 1, ?, id FROM system_role
                WHERE tenant_id = 1 AND role_code = ? AND deleted = 0
                """, id, roleCode);
    }

    private void insertTask(
            long id, String code, long organizationId, String teamCode,
            String sourceType, LocalDateTime dueTime
    ) {
        jdbc.update("""
                INSERT INTO inspection_task
                    (id, tenant_id, task_code, inspection_type, equipment_id,
                     organization_id, location_id, planned_date, due_time,
                     assignee_user_id, team_code, task_status, source_type, created_by)
                VALUES (?, 1, ?, 'ROUTINE', ?, ?, ?, CURRENT_DATE(), ?, ?, ?, 'PENDING', ?, 1)
                """, id, code, equipmentId, organizationId, locationId, dueTime,
                ASSIGNEE_ID, teamCode, sourceType);
    }

    private long messageCount(long businessId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_message
                WHERE tenant_id = 1 AND business_type = 'INSPECTION' AND business_id = ?
                """, Long.class, businessId);
    }

    private void authenticate(long id, String username, String name, Set<String> roles) {
        CurrentUser user = new CurrentUser(
                id, 1L, username, name, false, roles,
                Set.of("notification:message:view", "mobile:message:view"),
                "notification-it"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Set.of())
        );
    }
}
