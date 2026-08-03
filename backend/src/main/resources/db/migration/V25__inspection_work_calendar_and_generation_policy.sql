CREATE TABLE inspection_work_calendar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    calendar_name VARCHAR(150) NOT NULL,
    work_days VARCHAR(32) NOT NULL DEFAULT '1,2,3,4,5'
        COMMENT 'ISO week day list, 1=Monday, 7=Sunday',
    default_flag TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_work_calendar_name
        (tenant_id, calendar_name, deleted),
    KEY idx_inspection_work_calendar_status
        (tenant_id, status, default_flag, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检工作日历';

CREATE TABLE inspection_calendar_exception (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    calendar_id BIGINT NOT NULL,
    exception_name VARCHAR(150) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    day_type VARCHAR(16) NOT NULL COMMENT 'WORKDAY/RESTDAY',
    priority_value INT NOT NULL DEFAULT 100,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_inspection_calendar_exception_lookup
        (tenant_id, calendar_id, status, start_date, end_date, deleted),
    KEY idx_inspection_calendar_exception_priority
        (tenant_id, calendar_id, priority_value, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检自由日历例外';

ALTER TABLE inspection_scheme_version
    ADD COLUMN generation_lead_minutes INT NOT NULL DEFAULT 60
        COMMENT '计划时点前多少分钟生成任务' AFTER scheduled_time,
    ADD COLUMN work_calendar_id BIGINT NULL
        COMMENT '点检工作日历' AFTER generation_lead_minutes;

ALTER TABLE inspection_plan
    ADD COLUMN generation_lead_minutes INT NOT NULL DEFAULT 60
        AFTER scheduled_time,
    ADD COLUMN work_calendar_id BIGINT NULL
        AFTER generation_lead_minutes;

INSERT INTO inspection_work_calendar
    (id, tenant_id, calendar_name, work_days, default_flag, status,
     description, created_by, updated_by)
VALUES
    (1, 1, '默认工作日历', '1,2,3,4,5', 1, 1,
     '周一至周五工作；自由日历例外优先', 1, 1);

UPDATE inspection_scheme_version
SET work_calendar_id = 1
WHERE tenant_id = 1 AND work_calendar_id IS NULL;

UPDATE inspection_plan
SET work_calendar_id = 1
WHERE tenant_id = 1 AND work_calendar_id IS NULL;

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (28, 1, 20, 'MENU', '点检日历', 'InspectionCalendars',
     '/inspection/calendars',
     'views/inspection/calendars/InspectionCalendarView.vue',
     'inspection:calendar:view', 'Calendar', 28),
    (2801, 1, 28, 'BUTTON', '维护点检日历', NULL, NULL, NULL,
     'inspection:calendar:manage', NULL, 1)
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name),
    component_path = VALUES(component_path),
    permission_code = VALUES(permission_code),
    deleted = 0;

INSERT INTO system_role_menu
    (tenant_id, role_id, menu_id, created_by, updated_by, deleted)
SELECT role.tenant_id, role.id, menu.id, 1, 1, 0
FROM system_role role
JOIN system_menu menu
  ON menu.tenant_id = role.tenant_id
 AND menu.id IN (20, 28, 2801)
WHERE role.tenant_id = 1
  AND role.role_code IN ('ADMIN', 'PLANNER', 'WORKSHOP_MANAGER', 'TEAM_LEADER')
  AND role.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, updated_by = 1;

