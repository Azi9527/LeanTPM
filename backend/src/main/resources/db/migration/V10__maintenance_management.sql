CREATE TABLE maintenance_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(150) NOT NULL,
    item_category VARCHAR(64) NOT NULL,
    maintenance_part VARCHAR(150) NULL,
    maintenance_content VARCHAR(500) NOT NULL,
    maintenance_method VARCHAR(500) NULL,
    maintenance_tool VARCHAR(200) NULL,
    maintenance_standard VARCHAR(500) NOT NULL,
    standard_value VARCHAR(200) NULL,
    minimum_value DECIMAL(20, 6) NULL,
    maximum_value DECIMAL(20, 6) NULL,
    unit VARCHAR(32) NULL,
    result_type VARCHAR(32) NOT NULL
        COMMENT 'NORMAL_ABNORMAL/PASS_FAIL/NUMBER/TEXT/SINGLE_CHOICE/MULTIPLE_CHOICE/IMAGE/ATTACHMENT',
    result_options JSON NULL,
    required_flag TINYINT NOT NULL DEFAULT 1,
    photo_required_flag TINYINT NOT NULL DEFAULT 0,
    attachment_required_flag TINYINT NOT NULL DEFAULT 0,
    numeric_required_flag TINYINT NOT NULL DEFAULT 0,
    skip_allowed_flag TINYINT NOT NULL DEFAULT 0,
    stop_required_flag TINYINT NOT NULL DEFAULT 0,
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
    UNIQUE KEY uk_maintenance_item_code (tenant_id, item_code),
    KEY idx_maintenance_item_category (tenant_id, item_category, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保项目';

CREATE TABLE maintenance_scheme (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_code VARCHAR(64) NOT NULL,
    scheme_name VARCHAR(150) NOT NULL,
    maintenance_type VARCHAR(32) NOT NULL,
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
    UNIQUE KEY uk_maintenance_scheme_code (tenant_id, scheme_code),
    KEY idx_maintenance_scheme_type (tenant_id, maintenance_type, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保方案';

CREATE TABLE maintenance_scheme_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    version_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/PUBLISHED/RETIRED',
    cycle_type VARCHAR(32) NOT NULL
        COMMENT 'DAILY/WEEKLY/MONTHLY/QUARTERLY/HALF_YEARLY/YEARLY/RUNNING_HOURS/PRODUCTION_QUANTITY/MANUAL',
    cycle_interval INT NOT NULL DEFAULT 1,
    trigger_threshold DECIMAL(20, 3) NULL,
    week_days VARCHAR(32) NULL COMMENT '1,2,3,4,5,6,7',
    month_days VARCHAR(64) NULL COMMENT '1..31 comma separated',
    scheduled_time TIME NULL,
    reminder_days INT NOT NULL DEFAULT 3,
    generation_lead_days INT NOT NULL DEFAULT 7,
    shift_code VARCHAR(32) NULL,
    default_assignee_user_id BIGINT NULL,
    default_team_code VARCHAR(64) NULL,
    review_required_flag TINYINT NOT NULL DEFAULT 0,
    backfill_allowed_flag TINYINT NOT NULL DEFAULT 0,
    stop_required_flag TINYINT NOT NULL DEFAULT 0,
    restore_status_code VARCHAR(32) NULL DEFAULT 'IDLE',
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
    UNIQUE KEY uk_maintenance_scheme_version
        (tenant_id, scheme_id, version_number),
    KEY idx_maintenance_scheme_version_status
        (tenant_id, scheme_id, version_status, effective_date, expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保方案版本';

CREATE TABLE maintenance_scheme_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    maintenance_item_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    required_override TINYINT NULL,
    photo_required_override TINYINT NULL,
    attachment_required_override TINYINT NULL,
    skip_allowed_override TINYINT NULL,
    stop_required_override TINYINT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_maintenance_scheme_item
        (tenant_id, scheme_version_id, maintenance_item_id),
    KEY idx_maintenance_scheme_item_order
        (tenant_id, scheme_version_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保方案项目清单';

CREATE TABLE maintenance_scheme_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    equipment_category_id BIGINT NOT NULL,
    include_descendants_flag TINYINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_maintenance_scheme_category
        (tenant_id, scheme_version_id, equipment_category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保方案适用设备分类';

CREATE TABLE maintenance_scheme_equipment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    scheme_version_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_maintenance_scheme_equipment
        (tenant_id, scheme_version_id, equipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保方案适用设备';

CREATE TABLE maintenance_plan (
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
    trigger_threshold DECIMAL(20, 3) NULL,
    current_meter_value DECIMAL(20, 3) NOT NULL DEFAULT 0,
    next_trigger_value DECIMAL(20, 3) NULL,
    meter_updated_time DATETIME(3) NULL,
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
    UNIQUE KEY uk_maintenance_plan
        (tenant_id, scheme_version_id, equipment_id),
    KEY idx_maintenance_plan_generation
        (tenant_id, plan_status, next_generation_date),
    KEY idx_maintenance_plan_equipment
        (tenant_id, equipment_id, plan_status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备维保计划';

CREATE TABLE maintenance_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_code VARCHAR(64) NOT NULL,
    plan_id BIGINT NULL,
    scheme_id BIGINT NULL,
    scheme_version_id BIGINT NULL,
    scheme_code_snapshot VARCHAR(64) NULL,
    scheme_name_snapshot VARCHAR(150) NULL,
    scheme_version_number INT NULL,
    maintenance_type VARCHAR(32) NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    occurrence_key VARCHAR(64) NULL,
    planned_date DATE NOT NULL,
    planned_start_time DATETIME(3) NULL,
    due_time DATETIME(3) NOT NULL,
    assignee_user_id BIGINT NULL,
    team_code VARCHAR(64) NULL,
    task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ASSIGNMENT'
        COMMENT 'PENDING_ASSIGNMENT/PENDING/IN_PROGRESS/PAUSED/PENDING_CONFIRMATION/COMPLETED/OVERDUE/CANCELLED/VOIDED',
    source_type VARCHAR(16) NOT NULL DEFAULT 'PLAN'
        COMMENT 'PLAN/MANUAL/BACKFILL',
    backfill_flag TINYINT NOT NULL DEFAULT 0,
    review_required_flag TINYINT NOT NULL DEFAULT 0,
    stop_required_flag TINYINT NOT NULL DEFAULT 0,
    restore_status_code VARCHAR(32) NULL,
    previous_equipment_status VARCHAR(32) NULL,
    started_time DATETIME(3) NULL,
    paused_time DATETIME(3) NULL,
    submitted_time DATETIME(3) NULL,
    completed_time DATETIME(3) NULL,
    confirmed_time DATETIME(3) NULL,
    total_paused_seconds BIGINT NOT NULL DEFAULT 0,
    effective_work_minutes INT NOT NULL DEFAULT 0,
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
    UNIQUE KEY uk_maintenance_task_code (tenant_id, task_code),
    UNIQUE KEY uk_maintenance_task_occurrence (tenant_id, plan_id, occurrence_key),
    KEY idx_maintenance_task_assignee
        (tenant_id, assignee_user_id, task_status, due_time, deleted),
    KEY idx_maintenance_task_equipment
        (tenant_id, equipment_id, planned_date, task_status, deleted),
    KEY idx_maintenance_task_organization
        (tenant_id, organization_id, planned_date, task_status, deleted),
    KEY idx_maintenance_task_due
        (tenant_id, task_status, due_time, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务';

CREATE TABLE maintenance_task_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    source_item_id BIGINT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(150) NOT NULL,
    item_category VARCHAR(64) NOT NULL,
    maintenance_part VARCHAR(150) NULL,
    maintenance_content VARCHAR(500) NOT NULL,
    maintenance_method VARCHAR(500) NULL,
    maintenance_tool VARCHAR(200) NULL,
    maintenance_standard VARCHAR(500) NOT NULL,
    standard_value VARCHAR(200) NULL,
    minimum_value DECIMAL(20, 6) NULL,
    maximum_value DECIMAL(20, 6) NULL,
    unit VARCHAR(32) NULL,
    result_type VARCHAR(32) NOT NULL,
    result_options JSON NULL,
    required_flag TINYINT NOT NULL DEFAULT 1,
    photo_required_flag TINYINT NOT NULL DEFAULT 0,
    attachment_required_flag TINYINT NOT NULL DEFAULT 0,
    numeric_required_flag TINYINT NOT NULL DEFAULT 0,
    skip_allowed_flag TINYINT NOT NULL DEFAULT 0,
    stop_required_flag TINYINT NOT NULL DEFAULT 0,
    abnormal_severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    abnormal_advice VARCHAR(500) NULL,
    standard_minutes INT NOT NULL DEFAULT 0,
    safety_notes VARCHAR(1000) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_maintenance_task_item_code (tenant_id, task_id, item_code),
    KEY idx_maintenance_task_item_order (tenant_id, task_id, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务项目快照';

CREATE TABLE maintenance_task_result (
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
    UNIQUE KEY uk_maintenance_task_result (tenant_id, task_item_id),
    KEY idx_maintenance_task_result_task
        (tenant_id, task_id, result_status, abnormal_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务结果';

CREATE TABLE maintenance_task_collaborator (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_maintenance_task_collaborator (tenant_id, task_id, user_id),
    KEY idx_maintenance_collaborator_user (tenant_id, user_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务协同人员';

CREATE TABLE maintenance_task_pause (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    pause_reason VARCHAR(500) NOT NULL,
    paused_by BIGINT NOT NULL,
    paused_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resumed_by BIGINT NULL,
    resumed_time DATETIME(3) NULL,
    duration_seconds BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_maintenance_task_pause (tenant_id, task_id, paused_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务暂停履历';

CREATE TABLE maintenance_material_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    material_code VARCHAR(64) NOT NULL,
    material_name VARCHAR(150) NOT NULL,
    specification VARCHAR(150) NULL,
    quantity DECIMAL(20, 3) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    unit_cost DECIMAL(20, 4) NULL,
    total_cost DECIMAL(20, 4) NULL,
    batch_number VARCHAR(64) NULL,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_maintenance_material_task (tenant_id, task_id, deleted, id),
    KEY idx_maintenance_material_code (tenant_id, material_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务备件耗用';

CREATE TABLE maintenance_abnormal (
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
    UNIQUE KEY uk_maintenance_abnormal_code (tenant_id, abnormal_code),
    UNIQUE KEY uk_maintenance_abnormal_result (tenant_id, task_result_id),
    KEY idx_maintenance_abnormal_responsible
        (tenant_id, responsible_user_id, abnormal_status, due_time, deleted),
    KEY idx_maintenance_abnormal_equipment
        (tenant_id, equipment_id, abnormal_status, created_time, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保异常';

CREATE TABLE maintenance_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    task_result_id BIGINT NULL,
    abnormal_id BIGINT NULL,
    attachment_id BIGINT NOT NULL,
    attachment_type VARCHAR(32) NOT NULL
        COMMENT 'BEFORE_PHOTO/AFTER_PHOTO/RESULT_ATTACHMENT/ABNORMAL_PHOTO/ABNORMAL_ATTACHMENT',
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_maintenance_attachment
        (tenant_id, attachment_id, attachment_type),
    KEY idx_maintenance_attachment_result
        (tenant_id, task_result_id, attachment_type),
    KEY idx_maintenance_attachment_abnormal
        (tenant_id, abnormal_id, attachment_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保附件关系';

CREATE TABLE maintenance_task_event (
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
    KEY idx_maintenance_task_event
        (tenant_id, task_id, event_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保任务状态事件';

INSERT INTO system_number_rule
    (id, tenant_id, rule_code, rule_name, prefix, date_pattern,
     separator_value, sequence_length, reset_period, description)
VALUES
    (6, 1, 'MAINTENANCE_SCHEME', '维保方案编号', 'MPS', 'yyyy',
     '-', 6, 'YEARLY', '维保方案业务编号'),
    (7, 1, 'MAINTENANCE_ABNORMAL', '维保异常编号', 'MAB', 'yyyyMMdd',
     '-', 6, 'DAILY', '维保异常业务编号');

INSERT INTO system_dictionary_type
    (id, tenant_id, dict_code, dict_name, remark)
VALUES
    (10, 1, 'maintenance_result_type', '维保结果类型', '维保项目可配置结果类型'),
    (11, 1, 'maintenance_level', '维保等级', '维保方案等级'),
    (12, 1, 'maintenance_task_status', '维保任务状态', '维保任务统一状态'),
    (13, 1, 'maintenance_abnormal_severity', '维保异常等级', '异常严重程度'),
    (14, 1, 'maintenance_abnormal_status', '维保异常状态', '维保异常闭环状态'),
    (15, 1, 'maintenance_cycle_type', '维保周期类型', '维保计划触发策略');

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, sort_order)
VALUES
    (1, 10, 'NORMAL_ABNORMAL', '正常/异常', 1),
    (1, 10, 'PASS_FAIL', '合格/不合格', 2),
    (1, 10, 'NUMBER', '数值', 3),
    (1, 10, 'TEXT', '文本', 4),
    (1, 10, 'SINGLE_CHOICE', '单选', 5),
    (1, 10, 'MULTIPLE_CHOICE', '多选', 6),
    (1, 10, 'IMAGE', '图片', 7),
    (1, 10, 'ATTACHMENT', '附件', 8),
    (1, 11, 'DAILY', '日常保养', 1),
    (1, 11, 'LEVEL_1', '一级保养', 2),
    (1, 11, 'LEVEL_2', '二级保养', 3),
    (1, 11, 'LEVEL_3', '三级保养', 4),
    (1, 11, 'SPECIAL', '专项保养', 5),
    (1, 11, 'ANNUAL', '年度保养', 6),
    (1, 12, 'PENDING_ASSIGNMENT', '待派工', 1),
    (1, 12, 'PENDING', '待执行', 2),
    (1, 12, 'IN_PROGRESS', '执行中', 3),
    (1, 12, 'PAUSED', '已暂停', 4),
    (1, 12, 'PENDING_CONFIRMATION', '待确认', 5),
    (1, 12, 'COMPLETED', '已完成', 6),
    (1, 12, 'OVERDUE', '已逾期', 7),
    (1, 12, 'CANCELLED', '已取消', 8),
    (1, 12, 'VOIDED', '已作废', 9),
    (1, 13, 'LOW', '低', 1),
    (1, 13, 'MEDIUM', '中', 2),
    (1, 13, 'HIGH', '高', 3),
    (1, 13, 'CRITICAL', '紧急', 4),
    (1, 14, 'OPEN', '待处理', 1),
    (1, 14, 'PROCESSING', '处理中', 2),
    (1, 14, 'PENDING_VERIFY', '待验证', 3),
    (1, 14, 'CLOSED', '已关闭', 4),
    (1, 15, 'DAILY', '按日', 1),
    (1, 15, 'WEEKLY', '按周', 2),
    (1, 15, 'MONTHLY', '按月', 3),
    (1, 15, 'QUARTERLY', '按季度', 4),
    (1, 15, 'HALF_YEARLY', '按半年', 5),
    (1, 15, 'YEARLY', '按年', 6),
    (1, 15, 'RUNNING_HOURS', '累计运行小时', 7),
    (1, 15, 'PRODUCTION_QUANTITY', '累计生产数量', 8),
    (1, 15, 'MANUAL', '手工触发', 9);

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'maintenance.generation.lookahead-days', '维保任务提前生成天数',
     '7', 'INTEGER', 'MAINTENANCE', '计划任务生成器向前生成的天数', 1),
    (1, 'maintenance.overdue.scan-minutes', '维保逾期扫描间隔',
     '5', 'INTEGER', 'MAINTENANCE', '维保任务逾期扫描间隔分钟数', 1),
    (1, 'maintenance.restore-equipment-status', '维保完成恢复设备状态',
     'true', 'BOOLEAN', 'MAINTENANCE', '确认维保完成后按方案恢复设备状态', 1);

INSERT INTO maintenance_item
    (id, tenant_id, item_code, item_name, item_category, maintenance_part,
     maintenance_content, maintenance_method, maintenance_tool, maintenance_standard,
     minimum_value, maximum_value, unit, result_type, required_flag,
     photo_required_flag, attachment_required_flag, numeric_required_flag,
     stop_required_flag, abnormal_severity, abnormal_advice,
     standard_minutes, safety_notes)
VALUES
    (1, 1, 'CNC-LUBE-CHANGE', '主轴润滑油更换', 'LUBRICATION', '主轴润滑系统',
     '排放旧油、清洁油箱并加入规定牌号润滑油', '按设备保养作业指导书执行',
     '扭矩扳手、接油盘', '油位处于规定范围且无渗漏',
     30, 80, '%', 'NUMBER', 1, 1, 0, 1, 1, 'HIGH',
     '发现污染或金属屑时隔离设备并上报', 45, '执行上锁挂牌并确认设备断能'),
    (2, 1, 'CNC-GUARD-MAINT', '安全联锁保养', 'SAFETY', '防护门及联锁',
     '清洁、紧固并测试防护门联锁和急停装置', '逐项测试并记录结果', NULL,
     '防护完整、联锁有效、急停动作正常', NULL, NULL, NULL,
     'PASS_FAIL', 1, 1, 0, 1, 1, 'CRITICAL',
     '任一联锁失效时禁止恢复设备运行', 30, '测试前清空加工区并执行断能'),
    (3, 1, 'CNC-BELT-TENSION', '主轴皮带张力检查', 'TRANSMISSION', '主轴传动',
     '检查皮带磨损并测量张力', '使用张力计按三点测量', '皮带张力计',
     '张力 45～55 N，皮带无裂纹和缺口', 45, 55, 'N',
     'NUMBER', 1, 0, 1, 1, 1, 'HIGH',
     '超差时更换皮带并复测', 25, '拆卸防护罩前执行上锁挂牌');

INSERT INTO maintenance_scheme
    (id, tenant_id, scheme_code, scheme_name, maintenance_type,
     current_version_id, description)
VALUES
    (1, 1, 'MPS-DEMO-CNC-L1', '数控设备一级保养方案', 'LEVEL_1',
     1, 'M3 演示方案，用于数控加工设备周期保养');

INSERT INTO maintenance_scheme_version
    (id, tenant_id, scheme_id, version_number, version_status, cycle_type,
     cycle_interval, scheduled_time, reminder_days, generation_lead_days,
     review_required_flag, backfill_allowed_flag, stop_required_flag,
     restore_status_code, effective_date, published_by, published_time, change_summary)
VALUES
    (1, 1, 1, 1, 'PUBLISHED', 'MONTHLY', 1, '08:00:00', 3, 7,
     1, 1, 1, 'IDLE', CURRENT_DATE, 0, CURRENT_TIMESTAMP(3), '初始化演示版本');

INSERT INTO maintenance_scheme_item
    (tenant_id, scheme_version_id, maintenance_item_id, sort_order)
VALUES
    (1, 1, 1, 10),
    (1, 1, 2, 20),
    (1, 1, 3, 30);

INSERT INTO maintenance_scheme_category
    (tenant_id, scheme_version_id, equipment_category_id,
     include_descendants_flag)
VALUES
    (1, 1, 3, 1);

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (31, 1, 30, 'MENU', '维保项目', 'MaintenanceItems', '/maintenance/items',
     'views/maintenance/items/MaintenanceItemView.vue',
     'maintenance:item:view', 'List', 21),
    (32, 1, 30, 'MENU', '维保方案', 'MaintenanceSchemes', '/maintenance/schemes',
     'views/maintenance/schemes/MaintenanceSchemeView.vue',
     'maintenance:scheme:view', 'DocumentChecked', 22),
    (33, 1, 30, 'MENU', '维保计划', 'MaintenancePlans', '/maintenance/plans',
     'views/maintenance/plans/MaintenancePlanView.vue',
     'maintenance:plan:view', 'Calendar', 23),
    (34, 1, 30, 'MENU', '维保任务', 'MaintenanceTasks', '/maintenance/tasks',
     'views/maintenance/tasks/MaintenanceTaskView.vue',
     'maintenance:task:view', 'Tickets', 24),
    (35, 1, 30, 'MENU', '我的维保', 'MyMaintenanceTasks', '/maintenance/my-tasks',
     'views/maintenance/mobile/MyMaintenanceTaskView.vue',
     'maintenance:my-task:view', 'Finished', 25),
    (36, 1, 30, 'MENU', '维保异常', 'MaintenanceAbnormal', '/maintenance/abnormal',
     'views/maintenance/abnormal/MaintenanceAbnormalView.vue',
     'maintenance:abnormal:view', 'Warning', 26),
    (37, 1, 30, 'MENU', '维保统计', 'MaintenanceStatistics', '/maintenance/statistics',
     'views/maintenance/statistics/MaintenanceStatisticsView.vue',
     'maintenance:statistics:view', 'DataAnalysis', 27),
    (3101, 1, 31, 'BUTTON', '维护维保项目', NULL, NULL, NULL,
     'maintenance:item:manage', NULL, 1),
    (3102, 1, 31, 'BUTTON', '删除维保项目', NULL, NULL, NULL,
     'maintenance:item:delete', NULL, 2),
    (3201, 1, 32, 'BUTTON', '维护维保方案', NULL, NULL, NULL,
     'maintenance:scheme:manage', NULL, 1),
    (3202, 1, 32, 'BUTTON', '发布维保方案', NULL, NULL, NULL,
     'maintenance:scheme:publish', NULL, 2),
    (3301, 1, 33, 'BUTTON', '维护维保计划', NULL, NULL, NULL,
     'maintenance:plan:manage', NULL, 1),
    (3302, 1, 33, 'BUTTON', '生成维保任务', NULL, NULL, NULL,
     'maintenance:plan:generate', NULL, 2),
    (3303, 1, 33, 'BUTTON', '维护设备累计值', NULL, NULL, NULL,
     'maintenance:plan:meter', NULL, 3),
    (3401, 1, 34, 'BUTTON', '创建维保任务', NULL, NULL, NULL,
     'maintenance:task:create', NULL, 1),
    (3402, 1, 34, 'BUTTON', '维保任务派工', NULL, NULL, NULL,
     'maintenance:task:assign', NULL, 2),
    (3403, 1, 34, 'BUTTON', '确认维保任务', NULL, NULL, NULL,
     'maintenance:task:confirm', NULL, 3),
    (3404, 1, 34, 'BUTTON', '取消或作废任务', NULL, NULL, NULL,
     'maintenance:task:cancel', NULL, 4),
    (3405, 1, 34, 'BUTTON', '维护协同人员', NULL, NULL, NULL,
     'maintenance:task:collaborate', NULL, 5),
    (3501, 1, 35, 'BUTTON', '执行维保任务', NULL, NULL, NULL,
     'maintenance:task:execute', NULL, 1),
    (3601, 1, 36, 'BUTTON', '处理维保异常', NULL, NULL, NULL,
     'maintenance:abnormal:handle', NULL, 1),
    (3602, 1, 36, 'BUTTON', '验证维保异常', NULL, NULL, NULL,
     'maintenance:abnormal:verify', NULL, 2);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (
          30, 31, 32, 33, 34, 35, 36, 37,
          3101, 3102, 3201, 3202, 3301, 3302, 3303,
          3401, 3402, 3403, 3404, 3405, 3501, 3601, 3602
      )
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (
          30, 31, 32, 33, 34, 35, 36, 37,
          3101, 3201, 3202, 3301, 3302, 3303,
          3401, 3402, 3403, 3404, 3405, 3501, 3601, 3602, 951
      )
    UNION ALL
    SELECT 4 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (30, 34, 35, 36, 3501, 3601, 951)
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
