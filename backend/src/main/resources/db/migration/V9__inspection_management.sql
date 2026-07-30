CREATE TABLE inspection_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(150) NOT NULL,
    item_category VARCHAR(64) NOT NULL,
    inspection_part VARCHAR(150) NULL,
    inspection_content VARCHAR(500) NOT NULL,
    inspection_method VARCHAR(500) NULL,
    inspection_tool VARCHAR(200) NULL,
    inspection_standard VARCHAR(500) NOT NULL,
    standard_value VARCHAR(200) NULL,
    minimum_value DECIMAL(20, 6) NULL,
    maximum_value DECIMAL(20, 6) NULL,
    unit VARCHAR(32) NULL,
    result_type VARCHAR(32) NOT NULL
        COMMENT 'NORMAL_ABNORMAL/PASS_FAIL/NUMBER/TEXT/SINGLE_CHOICE/MULTIPLE_CHOICE/IMAGE/ATTACHMENT',
    result_options JSON NULL,
    required_flag TINYINT NOT NULL DEFAULT 1,
    photo_required_flag TINYINT NOT NULL DEFAULT 0,
    numeric_required_flag TINYINT NOT NULL DEFAULT 0,
    skip_allowed_flag TINYINT NOT NULL DEFAULT 0,
    abnormal_severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM'
        COMMENT 'LOW/MEDIUM/HIGH/CRITICAL',
    abnormal_advice VARCHAR(500) NULL,
    standard_minutes INT NOT NULL DEFAULT 0,
    safety_notes VARCHAR(1000) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_item_code (tenant_id, item_code),
    KEY idx_inspection_item_category (tenant_id, item_category, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检项目';

CREATE TABLE inspection_scheme (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_code VARCHAR(64) NOT NULL,
    scheme_name VARCHAR(150) NOT NULL,
    inspection_type VARCHAR(32) NOT NULL,
    current_version_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_scheme_code (tenant_id, scheme_code),
    KEY idx_inspection_scheme_type (tenant_id, inspection_type, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检方案';

CREATE TABLE inspection_scheme_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    version_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/PUBLISHED/RETIRED',
    cycle_type VARCHAR(32) NOT NULL
        COMMENT 'DAILY/WEEKLY/MONTHLY/INTERVAL_DAYS',
    cycle_interval INT NOT NULL DEFAULT 1,
    week_days VARCHAR(32) NULL COMMENT '1,2,3,4,5,6,7',
    month_days VARCHAR(64) NULL COMMENT '1..31 comma separated',
    scheduled_time TIME NULL,
    shift_code VARCHAR(32) NULL,
    default_assignee_user_id BIGINT NULL,
    default_team_code VARCHAR(64) NULL,
    review_required_flag TINYINT NOT NULL DEFAULT 0,
    backfill_allowed_flag TINYINT NOT NULL DEFAULT 0,
    effective_date DATE NOT NULL,
    expiry_date DATE NULL,
    published_by BIGINT NULL,
    published_time DATETIME(3) NULL,
    change_summary VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_scheme_version
        (tenant_id, scheme_id, version_number),
    KEY idx_inspection_scheme_version_status
        (tenant_id, scheme_id, version_status, effective_date, expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检方案版本';

CREATE TABLE inspection_scheme_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    inspection_item_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    required_override TINYINT NULL,
    photo_required_override TINYINT NULL,
    skip_allowed_override TINYINT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_scheme_item
        (tenant_id, scheme_version_id, inspection_item_id),
    KEY idx_inspection_scheme_item_order
        (tenant_id, scheme_version_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检方案项目清单';

CREATE TABLE inspection_scheme_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    equipment_category_id BIGINT NOT NULL,
    include_descendants_flag TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_scheme_category
        (tenant_id, scheme_version_id, equipment_category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检方案适用设备分类';

CREATE TABLE inspection_scheme_equipment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_scheme_equipment
        (tenant_id, scheme_version_id, equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检方案适用设备';

CREATE TABLE inspection_plan (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    cycle_type VARCHAR(32) NOT NULL,
    cycle_interval INT NOT NULL DEFAULT 1,
    week_days VARCHAR(32) NULL,
    month_days VARCHAR(64) NULL,
    scheduled_time TIME NULL,
    shift_code VARCHAR(32) NULL,
    assignee_user_id BIGINT NULL,
    team_code VARCHAR(64) NULL,
    next_generation_date DATE NOT NULL,
    last_generation_date DATE NULL,
    plan_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE/PAUSED/CANCELLED',
    paused_reason VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_plan
        (tenant_id, scheme_version_id, equipment_id),
    KEY idx_inspection_plan_generation
        (tenant_id, plan_status, next_generation_date),
    KEY idx_inspection_plan_equipment
        (tenant_id, equipment_id, plan_status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备点检计划';

CREATE TABLE inspection_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_code VARCHAR(64) NOT NULL,
    plan_id BIGINT NULL,
    scheme_id BIGINT NULL,
    scheme_version_id BIGINT NULL,
    scheme_code_snapshot VARCHAR(64) NULL,
    scheme_name_snapshot VARCHAR(150) NULL,
    scheme_version_number INT NULL,
    inspection_type VARCHAR(32) NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    occurrence_key VARCHAR(64) NULL,
    planned_date DATE NOT NULL,
    planned_start_time DATETIME(3) NULL,
    due_time DATETIME(3) NOT NULL,
    assignee_user_id BIGINT NULL,
    team_code VARCHAR(64) NULL,
    task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/IN_PROGRESS/PENDING_REVIEW/COMPLETED/OVERDUE/CANCELLED/VOIDED',
    source_type VARCHAR(16) NOT NULL DEFAULT 'PLAN'
        COMMENT 'PLAN/MANUAL/BACKFILL',
    backfill_flag TINYINT NOT NULL DEFAULT 0,
    review_required_flag TINYINT NOT NULL DEFAULT 0,
    started_time DATETIME(3) NULL,
    submitted_time DATETIME(3) NULL,
    completed_time DATETIME(3) NULL,
    reviewer_user_id BIGINT NULL,
    reviewed_time DATETIME(3) NULL,
    review_comment VARCHAR(500) NULL,
    cancel_reason VARCHAR(500) NULL,
    void_reason VARCHAR(500) NULL,
    execution_remark VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_task_code (tenant_id, task_code),
    UNIQUE KEY uk_inspection_task_occurrence (tenant_id, plan_id, occurrence_key),
    KEY idx_inspection_task_assignee
        (tenant_id, assignee_user_id, task_status, due_time, deleted),
    KEY idx_inspection_task_equipment
        (tenant_id, equipment_id, planned_date, task_status, deleted),
    KEY idx_inspection_task_organization
        (tenant_id, organization_id, planned_date, task_status, deleted),
    KEY idx_inspection_task_due
        (tenant_id, task_status, due_time, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检任务';

CREATE TABLE inspection_task_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    source_item_id BIGINT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(150) NOT NULL,
    item_category VARCHAR(64) NOT NULL,
    inspection_part VARCHAR(150) NULL,
    inspection_content VARCHAR(500) NOT NULL,
    inspection_method VARCHAR(500) NULL,
    inspection_tool VARCHAR(200) NULL,
    inspection_standard VARCHAR(500) NOT NULL,
    standard_value VARCHAR(200) NULL,
    minimum_value DECIMAL(20, 6) NULL,
    maximum_value DECIMAL(20, 6) NULL,
    unit VARCHAR(32) NULL,
    result_type VARCHAR(32) NOT NULL,
    result_options JSON NULL,
    required_flag TINYINT NOT NULL DEFAULT 1,
    photo_required_flag TINYINT NOT NULL DEFAULT 0,
    numeric_required_flag TINYINT NOT NULL DEFAULT 0,
    skip_allowed_flag TINYINT NOT NULL DEFAULT 0,
    abnormal_severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    abnormal_advice VARCHAR(500) NULL,
    standard_minutes INT NOT NULL DEFAULT 0,
    safety_notes VARCHAR(1000) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_task_item_code (tenant_id, task_id, item_code),
    KEY idx_inspection_task_item_order (tenant_id, task_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检任务项目快照';

CREATE TABLE inspection_task_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    task_item_id BIGINT NOT NULL,
    result_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/SUBMITTED',
    result_code VARCHAR(32) NULL
        COMMENT 'NORMAL/ABNORMAL/PASS/FAIL/SKIPPED',
    numeric_value DECIMAL(20, 6) NULL,
    text_value VARCHAR(2000) NULL,
    selected_value VARCHAR(500) NULL,
    selected_values JSON NULL,
    abnormal_flag TINYINT NOT NULL DEFAULT 0,
    abnormal_description VARCHAR(1000) NULL,
    skipped_flag TINYINT NOT NULL DEFAULT 0,
    skip_reason VARCHAR(500) NULL,
    executed_by BIGINT NOT NULL,
    executed_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    submitted_time DATETIME(3) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_task_result (tenant_id, task_item_id),
    KEY idx_inspection_task_result_task
        (tenant_id, task_id, result_status, abnormal_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检任务结果';

CREATE TABLE inspection_abnormal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    abnormal_code VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    task_result_id BIGINT NULL,
    equipment_id BIGINT NOT NULL,
    task_item_id BIGINT NULL,
    abnormal_title VARCHAR(200) NOT NULL,
    abnormal_description VARCHAR(2000) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    abnormal_status VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        COMMENT 'OPEN/PROCESSING/PENDING_VERIFY/CLOSED',
    responsible_user_id BIGINT NULL,
    due_time DATETIME(3) NULL,
    temporary_action VARCHAR(2000) NULL,
    final_result VARCHAR(2000) NULL,
    requested_equipment_status VARCHAR(32) NULL,
    maintenance_request_id BIGINT NULL,
    closed_by BIGINT NULL,
    closed_time DATETIME(3) NULL,
    verified_by BIGINT NULL,
    verified_time DATETIME(3) NULL,
    verification_comment VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_abnormal_code (tenant_id, abnormal_code),
    UNIQUE KEY uk_inspection_abnormal_result (tenant_id, task_result_id),
    KEY idx_inspection_abnormal_responsible
        (tenant_id, responsible_user_id, abnormal_status, due_time, deleted),
    KEY idx_inspection_abnormal_equipment
        (tenant_id, equipment_id, abnormal_status, created_time, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检异常';

CREATE TABLE inspection_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    task_result_id BIGINT NULL,
    abnormal_id BIGINT NULL,
    attachment_id BIGINT NOT NULL,
    attachment_type VARCHAR(32) NOT NULL
        COMMENT 'RESULT_PHOTO/RESULT_ATTACHMENT/ABNORMAL_PHOTO/ABNORMAL_ATTACHMENT',
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_attachment
        (tenant_id, attachment_id, attachment_type),
    KEY idx_inspection_attachment_result
        (tenant_id, task_result_id, attachment_type),
    KEY idx_inspection_attachment_abnormal
        (tenant_id, abnormal_id, attachment_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检附件关系';

CREATE TABLE inspection_task_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    event_remark VARCHAR(1000) NULL,
    operator_id BIGINT NOT NULL,
    event_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_inspection_task_event
        (tenant_id, task_id, event_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检任务状态事件';

INSERT INTO system_number_rule
    (id, tenant_id, rule_code, rule_name, prefix, date_pattern,
     separator_value, sequence_length, reset_period, description)
VALUES
    (4, 1, 'INSPECTION_SCHEME', '点检方案编号', 'ISP', 'yyyy',
     '-', 6, 'YEARLY', '点检方案业务编号'),
    (5, 1, 'INSPECTION_ABNORMAL', '点检异常编号', 'ABN', 'yyyyMMdd',
     '-', 6, 'DAILY', '点检异常业务编号');

INSERT INTO system_dictionary_type
    (id, tenant_id, dict_code, dict_name, remark)
VALUES
    (5, 1, 'inspection_result_type', '点检结果类型', '点检项目可配置结果类型'),
    (6, 1, 'inspection_type', '点检类型', '点检方案类型'),
    (7, 1, 'inspection_task_status', '点检任务状态', '点检任务统一状态'),
    (8, 1, 'inspection_abnormal_severity', '点检异常等级', '异常严重程度'),
    (9, 1, 'inspection_abnormal_status', '点检异常状态', '点检异常闭环状态');

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, sort_order)
VALUES
    (1, 5, 'NORMAL_ABNORMAL', '正常/异常', 1),
    (1, 5, 'PASS_FAIL', '合格/不合格', 2),
    (1, 5, 'NUMBER', '数值', 3),
    (1, 5, 'TEXT', '文本', 4),
    (1, 5, 'SINGLE_CHOICE', '单选', 5),
    (1, 5, 'MULTIPLE_CHOICE', '多选', 6),
    (1, 5, 'IMAGE', '图片', 7),
    (1, 5, 'ATTACHMENT', '附件', 8),
    (1, 6, 'DAILY', '日常点检', 1),
    (1, 6, 'PRE_SHIFT', '班前点检', 2),
    (1, 6, 'POST_SHIFT', '班后点检', 3),
    (1, 6, 'PROFESSIONAL', '专业点检', 4),
    (1, 6, 'PRECISION', '精密点检', 5),
    (1, 6, 'SAFETY', '安全点检', 6),
    (1, 6, 'SPECIAL', '专项点检', 7),
    (1, 7, 'PENDING', '待执行', 1),
    (1, 7, 'IN_PROGRESS', '执行中', 2),
    (1, 7, 'PENDING_REVIEW', '待确认', 3),
    (1, 7, 'COMPLETED', '已完成', 4),
    (1, 7, 'OVERDUE', '已逾期', 5),
    (1, 7, 'CANCELLED', '已取消', 6),
    (1, 7, 'VOIDED', '已作废', 7),
    (1, 8, 'LOW', '低', 1),
    (1, 8, 'MEDIUM', '中', 2),
    (1, 8, 'HIGH', '高', 3),
    (1, 8, 'CRITICAL', '紧急', 4),
    (1, 9, 'OPEN', '待处理', 1),
    (1, 9, 'PROCESSING', '处理中', 2),
    (1, 9, 'PENDING_VERIFY', '待验证', 3),
    (1, 9, 'CLOSED', '已关闭', 4);

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'inspection.generation.lookahead-days', '点检任务提前生成天数',
     '7', 'INTEGER', 'INSPECTION', '计划任务生成器向前生成的天数', 1),
    (1, 'inspection.overdue.scan-minutes', '点检逾期扫描间隔',
     '5', 'INTEGER', 'INSPECTION', '点检任务逾期扫描间隔分钟数', 1);

INSERT INTO inspection_item
    (id, tenant_id, item_code, item_name, item_category, inspection_part,
     inspection_content, inspection_method, inspection_tool, inspection_standard,
     minimum_value, maximum_value, unit, result_type, required_flag,
     numeric_required_flag, abnormal_severity, abnormal_advice,
     standard_minutes, safety_notes)
VALUES
    (1, 1, 'CNC-LUBRICATION', '润滑油液位', 'LUBRICATION', '润滑系统',
     '检查主轴润滑油箱液位', '观察液位计', '手电筒', '液位处于刻度范围内',
     30, 80, '%', 'NUMBER', 1, 1, 'HIGH',
     '低于下限时停止设备并补充规定牌号润滑油', 3, '设备运转时不得打开油箱'),
    (2, 1, 'CNC-GUARD', '安全防护装置', 'SAFETY', '设备防护门',
     '检查防护门、联锁和急停装置', '目视并执行功能测试', NULL,
     '防护完整、联锁有效、急停动作正常', NULL, NULL, NULL,
     'PASS_FAIL', 1, 1, 'CRITICAL',
     '立即停止使用并上报班组长', 5, '功能测试前确认设备无加工任务'),
    (3, 1, 'CNC-ABNORMAL-NOISE', '异常声音与振动', 'OPERATION', '主轴及进给系统',
     '检查运行声音和振动', '空载低速运行并观察', NULL,
     '无异常声音、冲击或明显振动', NULL, NULL, NULL,
     'NORMAL_ABNORMAL', 1, 0, 'HIGH',
     '停机并通知设备维修人员', 5, '保持安全距离，禁止触碰旋转部件');

INSERT INTO inspection_scheme
    (id, tenant_id, scheme_code, scheme_name, inspection_type,
     current_version_id, description)
VALUES
    (1, 1, 'ISP-DEMO-CNC-DAILY', '数控设备日常点检方案', 'DAILY',
     1, 'M2 演示方案，用于数控加工设备日常点检');

INSERT INTO inspection_scheme_version
    (id, tenant_id, scheme_id, version_number, version_status, cycle_type,
     cycle_interval, scheduled_time, review_required_flag,
     backfill_allowed_flag, effective_date, published_by, published_time,
     change_summary)
VALUES
    (1, 1, 1, 1, 'PUBLISHED', 'DAILY', 1, '08:00:00',
     1, 1, CURRENT_DATE, 0, CURRENT_TIMESTAMP(3), '初始化演示版本');

INSERT INTO inspection_scheme_item
    (tenant_id, scheme_version_id, inspection_item_id, sort_order)
VALUES
    (1, 1, 1, 10),
    (1, 1, 2, 20),
    (1, 1, 3, 30);

INSERT INTO inspection_scheme_category
    (tenant_id, scheme_version_id, equipment_category_id,
     include_descendants_flag)
VALUES
    (1, 1, 3, 1);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (21, 1, 20, 'MENU', '点检项目', 'InspectionItems', '/inspection/items',
     'views/inspection/items/InspectionItemView.vue',
     'inspection:item:view', 'List', 21),
    (22, 1, 20, 'MENU', '点检方案', 'InspectionSchemes', '/inspection/schemes',
     'views/inspection/schemes/InspectionSchemeView.vue',
     'inspection:scheme:view', 'DocumentChecked', 22),
    (23, 1, 20, 'MENU', '点检计划', 'InspectionPlans', '/inspection/plans',
     'views/inspection/plans/InspectionPlanView.vue',
     'inspection:plan:view', 'Calendar', 23),
    (24, 1, 20, 'MENU', '点检任务', 'InspectionTasks', '/inspection/tasks',
     'views/inspection/tasks/InspectionTaskView.vue',
     'inspection:task:view', 'Tickets', 24),
    (25, 1, 20, 'MENU', '我的点检', 'MyInspectionTasks', '/inspection/my-tasks',
     'views/inspection/mobile/MyInspectionTaskView.vue',
     'inspection:my-task:view', 'Finished', 25),
    (26, 1, 20, 'MENU', '点检异常', 'InspectionAbnormal', '/inspection/abnormal',
     'views/inspection/abnormal/InspectionAbnormalView.vue',
     'inspection:abnormal:view', 'Warning', 26),
    (27, 1, 20, 'MENU', '点检统计', 'InspectionStatistics', '/inspection/statistics',
     'views/inspection/statistics/InspectionStatisticsView.vue',
     'inspection:statistics:view', 'DataAnalysis', 27),
    (2101, 1, 21, 'BUTTON', '维护点检项目', NULL, NULL, NULL,
     'inspection:item:manage', NULL, 1),
    (2102, 1, 21, 'BUTTON', '删除点检项目', NULL, NULL, NULL,
     'inspection:item:delete', NULL, 2),
    (2201, 1, 22, 'BUTTON', '维护点检方案', NULL, NULL, NULL,
     'inspection:scheme:manage', NULL, 1),
    (2202, 1, 22, 'BUTTON', '发布点检方案', NULL, NULL, NULL,
     'inspection:scheme:publish', NULL, 2),
    (2301, 1, 23, 'BUTTON', '维护点检计划', NULL, NULL, NULL,
     'inspection:plan:manage', NULL, 1),
    (2302, 1, 23, 'BUTTON', '生成点检任务', NULL, NULL, NULL,
     'inspection:plan:generate', NULL, 2),
    (2401, 1, 24, 'BUTTON', '创建点检任务', NULL, NULL, NULL,
     'inspection:task:create', NULL, 1),
    (2402, 1, 24, 'BUTTON', '点检任务派工', NULL, NULL, NULL,
     'inspection:task:assign', NULL, 2),
    (2403, 1, 24, 'BUTTON', '审核点检任务', NULL, NULL, NULL,
     'inspection:task:review', NULL, 3),
    (2404, 1, 24, 'BUTTON', '取消或作废任务', NULL, NULL, NULL,
     'inspection:task:cancel', NULL, 4),
    (2501, 1, 25, 'BUTTON', '执行点检任务', NULL, NULL, NULL,
     'inspection:task:execute', NULL, 1),
    (2601, 1, 26, 'BUTTON', '处理点检异常', NULL, NULL, NULL,
     'inspection:abnormal:handle', NULL, 1),
    (2602, 1, 26, 'BUTTON', '验证点检异常', NULL, NULL, NULL,
     'inspection:abnormal:verify', NULL, 2);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (
          20, 21, 22, 23, 24, 25, 26, 27,
          2101, 2102, 2201, 2202, 2301, 2302,
          2401, 2402, 2403, 2404, 2501, 2601, 2602
      )
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (
          20, 21, 22, 23, 24, 25, 26, 27,
          2101, 2201, 2202, 2301, 2302,
          2401, 2402, 2403, 2404, 2501, 2601, 2602, 951
      )
    UNION ALL
    SELECT 3 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (20, 24, 25, 26, 2501, 2601, 951)
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
