CREATE TABLE equipment_shift (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    shift_code VARCHAR(64) NOT NULL,
    shift_name VARCHAR(100) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    cross_day_flag TINYINT NOT NULL DEFAULT 0,
    break_minutes INT NOT NULL DEFAULT 0,
    standard_work_minutes INT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_shift_code (tenant_id, shift_code, active_marker),
    KEY idx_equipment_shift_status (tenant_id, status, deleted, sort_order),
    CONSTRAINT chk_equipment_shift_minutes
        CHECK (break_minutes >= 0 AND standard_work_minutes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE生产班次';

CREATE TABLE equipment_calendar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    shift_id BIGINT NOT NULL,
    day_type VARCHAR(32) NOT NULL DEFAULT 'WORKDAY'
        COMMENT 'WORKDAY/HOLIDAY/OVERTIME',
    planned_work_minutes INT NOT NULL,
    planned_downtime_minutes INT NOT NULL DEFAULT 0,
    calendar_status VARCHAR(32) NOT NULL DEFAULT 'ENABLED'
        COMMENT 'ENABLED/DISABLED',
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_calendar
        (tenant_id, organization_id, work_date, shift_id, active_marker),
    KEY idx_equipment_calendar_date
        (tenant_id, work_date, calendar_status, deleted),
    KEY idx_equipment_calendar_shift
        (tenant_id, shift_id, work_date, deleted),
    CONSTRAINT chk_equipment_calendar_minutes
        CHECK (
            planned_work_minutes >= 0
            AND planned_downtime_minutes >= 0
            AND planned_downtime_minutes <= planned_work_minutes
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE生产日历';

CREATE TABLE equipment_oee_target (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    target_name VARCHAR(100) NOT NULL,
    target_level VARCHAR(32) NOT NULL
        COMMENT 'ENTERPRISE/FACTORY/WORKSHOP/LINE/EQUIPMENT',
    organization_id BIGINT NULL,
    equipment_id BIGINT NULL,
    availability_target DECIMAL(10, 6) NOT NULL,
    performance_target DECIMAL(10, 6) NOT NULL,
    quality_target DECIMAL(10, 6) NOT NULL,
    oee_target DECIMAL(10, 6) NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date DATE NULL,
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
    KEY idx_oee_target_scope
        (tenant_id, target_level, organization_id, equipment_id, status, deleted),
    KEY idx_oee_target_effective
        (tenant_id, effective_start_date, effective_end_date, status, deleted),
    CONSTRAINT chk_oee_target_rate
        CHECK (
            availability_target BETWEEN 0 AND 1
            AND performance_target BETWEEN 0 AND 1
            AND quality_target BETWEEN 0 AND 1
            AND oee_target BETWEEN 0 AND 1
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE目标';

CREATE TABLE equipment_loss_reason (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    reason_code VARCHAR(64) NOT NULL,
    reason_name VARCHAR(100) NOT NULL,
    loss_category VARCHAR(32) NOT NULL
        COMMENT 'BREAKDOWN/SETUP_ADJUSTMENT/MINOR_STOPPAGE/REDUCED_SPEED/PROCESS_DEFECT/STARTUP_REJECT/PLANNED_STOP/OTHER',
    affects_metric VARCHAR(32) NOT NULL
        COMMENT 'AVAILABILITY/PERFORMANCE/QUALITY/EXCLUDED',
    planned_flag TINYINT NOT NULL DEFAULT 0,
    color VARCHAR(20) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_loss_reason_code
        (tenant_id, reason_code, active_marker),
    KEY idx_equipment_loss_reason_parent
        (tenant_id, parent_id, status, deleted, sort_order),
    KEY idx_equipment_loss_reason_category
        (tenant_id, loss_category, affects_metric, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE损失原因';

CREATE TABLE equipment_output_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    production_date DATE NOT NULL,
    shift_id BIGINT NOT NULL,
    planned_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    actual_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    good_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    defective_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL/EXCEL/MES/IOT',
    source_reference VARCHAR(200) NULL,
    remark VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_output
        (tenant_id, equipment_id, production_date, shift_id, active_marker),
    KEY idx_equipment_output_org
        (tenant_id, organization_id, production_date, shift_id, deleted),
    CONSTRAINT chk_equipment_output_quantity
        CHECK (
            planned_quantity >= 0
            AND actual_quantity >= 0
            AND good_quantity >= 0
            AND defective_quantity >= 0
            AND good_quantity + defective_quantity <= actual_quantity
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE产量记录';

CREATE TABLE equipment_downtime_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    production_date DATE NOT NULL,
    shift_id BIGINT NOT NULL,
    loss_reason_id BIGINT NOT NULL,
    started_time DATETIME(3) NULL,
    ended_time DATETIME(3) NULL,
    duration_minutes DECIMAL(12, 3) NOT NULL,
    planned_flag TINYINT NOT NULL DEFAULT 0,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL/EXCEL/MES/IOT/STATUS',
    source_reference VARCHAR(200) NULL,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_equipment_downtime_record
        (tenant_id, equipment_id, production_date, shift_id, deleted),
    KEY idx_equipment_downtime_org
        (tenant_id, organization_id, production_date, shift_id, deleted),
    KEY idx_equipment_downtime_reason
        (tenant_id, loss_reason_id, production_date, deleted),
    CONSTRAINT chk_equipment_downtime_duration
        CHECK (
            duration_minutes > 0
            AND (started_time IS NULL OR ended_time IS NULL OR ended_time >= started_time)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE停机与损失记录';

CREATE TABLE equipment_oee_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    production_date DATE NOT NULL,
    shift_id BIGINT NOT NULL,
    standard_cycle_seconds DECIMAL(16, 6) NOT NULL,
    planned_work_minutes DECIMAL(12, 3) NOT NULL,
    planned_downtime_minutes DECIMAL(12, 3) NOT NULL DEFAULT 0,
    loading_time_minutes DECIMAL(12, 3) NOT NULL,
    unplanned_downtime_minutes DECIMAL(12, 3) NOT NULL DEFAULT 0,
    run_time_minutes DECIMAL(12, 3) NOT NULL DEFAULT 0,
    planned_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    actual_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    good_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    defective_quantity DECIMAL(20, 6) NOT NULL DEFAULT 0,
    availability_rate DECIMAL(10, 6) NOT NULL DEFAULT 0,
    performance_rate DECIMAL(10, 6) NOT NULL DEFAULT 0,
    quality_rate DECIMAL(10, 6) NOT NULL DEFAULT 0,
    oee_rate DECIMAL(10, 6) NOT NULL DEFAULT 0,
    target_oee_rate DECIMAL(10, 6) NULL,
    data_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/SUBMITTED/APPROVED/LOCKED',
    anomaly_flag TINYINT NOT NULL DEFAULT 0,
    anomaly_message VARCHAR(1000) NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL/EXCEL/MES/IOT',
    calculated_by BIGINT NULL,
    calculated_time DATETIME(3) NULL,
    approved_by BIGINT NULL,
    approved_time DATETIME(3) NULL,
    locked_by BIGINT NULL,
    locked_time DATETIME(3) NULL,
    created_by BIGINT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    active_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 THEN 1 ELSE NULL END
    ) STORED,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_equipment_oee_record
        (tenant_id, equipment_id, production_date, shift_id, active_marker),
    KEY idx_equipment_oee_org
        (tenant_id, organization_id, production_date, shift_id, data_status, deleted),
    KEY idx_equipment_oee_rate
        (tenant_id, production_date, oee_rate, deleted),
    CONSTRAINT chk_equipment_oee_inputs
        CHECK (
            standard_cycle_seconds > 0
            AND planned_work_minutes >= 0
            AND planned_downtime_minutes >= 0
            AND loading_time_minutes >= 0
            AND unplanned_downtime_minutes >= 0
            AND run_time_minutes >= 0
            AND planned_quantity >= 0
            AND actual_quantity >= 0
            AND good_quantity >= 0
            AND defective_quantity >= 0
        ),
    CONSTRAINT chk_equipment_oee_rates
        CHECK (
            availability_rate BETWEEN 0 AND 1
            AND performance_rate >= 0
            AND quality_rate BETWEEN 0 AND 1
            AND oee_rate >= 0
            AND (target_oee_rate IS NULL OR target_oee_rate BETWEEN 0 AND 1)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备班次OEE记录';

CREATE TABLE equipment_oee_calculation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    oee_record_id BIGINT NOT NULL,
    calculation_version INT NOT NULL,
    trigger_type VARCHAR(32) NOT NULL
        COMMENT 'CREATE/UPDATE/RECALCULATE/IMPORT/APPROVE',
    formula_version VARCHAR(32) NOT NULL DEFAULT 'OEE_V1',
    input_snapshot JSON NOT NULL,
    output_snapshot JSON NOT NULL,
    validation_message VARCHAR(1000) NULL,
    calculated_by BIGINT NOT NULL,
    calculated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_oee_calculation_version
        (tenant_id, oee_record_id, calculation_version),
    KEY idx_oee_calculation_time
        (tenant_id, calculated_time, oee_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OEE计算审计日志';

INSERT INTO system_dictionary_type
    (id, tenant_id, dict_code, dict_name, remark)
VALUES
    (16, 1, 'oee_data_status', 'OEE数据状态', 'OEE审核和锁定状态'),
    (17, 1, 'oee_source_type', 'OEE数据来源', '手工、Excel、MES与IoT'),
    (18, 1, 'oee_loss_category', 'OEE损失分类', 'TPM六大损失及扩展分类'),
    (19, 1, 'oee_day_type', '生产日类型', '工作日、休息日和加班日'),
    (20, 1, 'oee_affects_metric', 'OEE损失影响指标', '可动率、性能率、质量率或剔除');

INSERT INTO system_dictionary_item
    (tenant_id, dict_type_id, item_value, item_label, sort_order)
VALUES
    (1, 16, 'DRAFT', '草稿', 1),
    (1, 16, 'SUBMITTED', '待审核', 2),
    (1, 16, 'APPROVED', '已审核', 3),
    (1, 16, 'LOCKED', '已锁定', 4),
    (1, 17, 'MANUAL', '人工录入', 1),
    (1, 17, 'EXCEL', 'Excel导入', 2),
    (1, 17, 'MES', 'MES采集', 3),
    (1, 17, 'IOT', 'IoT采集', 4),
    (1, 18, 'BREAKDOWN', '设备故障', 1),
    (1, 18, 'SETUP_ADJUSTMENT', '换型与调整', 2),
    (1, 18, 'MINOR_STOPPAGE', '空转与短暂停机', 3),
    (1, 18, 'REDUCED_SPEED', '速度降低', 4),
    (1, 18, 'PROCESS_DEFECT', '过程不良', 5),
    (1, 18, 'STARTUP_REJECT', '启动不良', 6),
    (1, 18, 'PLANNED_STOP', '计划停机', 7),
    (1, 18, 'OTHER', '其他损失', 8),
    (1, 19, 'WORKDAY', '工作日', 1),
    (1, 19, 'HOLIDAY', '休息日', 2),
    (1, 19, 'OVERTIME', '加班日', 3),
    (1, 20, 'AVAILABILITY', '影响时间开动率', 1),
    (1, 20, 'PERFORMANCE', '影响性能开动率', 2),
    (1, 20, 'QUALITY', '影响良品率', 3),
    (1, 20, 'EXCLUDED', '从负荷时间剔除', 4);

INSERT INTO system_parameter
    (tenant_id, parameter_key, parameter_name, parameter_value, value_type,
     group_code, description, built_in)
VALUES
    (1, 'oee.performance-cap-enabled', 'OEE性能率封顶',
     'true', 'BOOLEAN', 'OEE', '性能率超过100%时是否按100%计算并提示异常', 1),
    (1, 'oee.import.max-rows', 'OEE单次导入上限',
     '2000', 'INTEGER', 'OEE', 'OEE Excel 单次导入最大数据行数', 1),
    (1, 'oee.default-target', 'OEE默认目标值',
     '0.850000', 'DECIMAL', 'OEE', '未配置分层目标时使用的默认OEE目标', 1);

INSERT INTO equipment_shift
    (id, tenant_id, shift_code, shift_name, start_time, end_time,
     cross_day_flag, break_minutes, standard_work_minutes, sort_order, description)
VALUES
    (1, 1, 'DAY', '白班', '08:00:00', '20:00:00',
     0, 60, 660, 10, '演示十二小时白班，含一小时休息'),
    (2, 1, 'NIGHT', '夜班', '20:00:00', '08:00:00',
     1, 60, 660, 20, '演示跨日十二小时夜班，含一小时休息');

INSERT INTO equipment_loss_reason
    (id, tenant_id, parent_id, reason_code, reason_name, loss_category,
     affects_metric, planned_flag, color, sort_order, description)
VALUES
    (1, 1, 0, 'BREAKDOWN', '设备故障', 'BREAKDOWN',
     'AVAILABILITY', 0, '#F56C6C', 10, '突发设备故障导致的非计划停机'),
    (2, 1, 0, 'SETUP_ADJUSTMENT', '换型与调整', 'SETUP_ADJUSTMENT',
     'AVAILABILITY', 0, '#E6A23C', 20, '换型、调试和工艺调整损失'),
    (3, 1, 0, 'MINOR_STOPPAGE', '空转与短暂停机', 'MINOR_STOPPAGE',
     'PERFORMANCE', 0, '#F2C94C', 30, '短暂停机和空转损失'),
    (4, 1, 0, 'REDUCED_SPEED', '速度降低', 'REDUCED_SPEED',
     'PERFORMANCE', 0, '#409EFF', 40, '低于标准节拍造成的速度损失'),
    (5, 1, 0, 'PROCESS_DEFECT', '过程不良', 'PROCESS_DEFECT',
     'QUALITY', 0, '#9B51E0', 50, '稳定生产阶段产生的不良品'),
    (6, 1, 0, 'STARTUP_REJECT', '启动不良', 'STARTUP_REJECT',
     'QUALITY', 0, '#7B61FF', 60, '开机和换型启动阶段产生的不良品'),
    (7, 1, 0, 'PLANNED_STOP', '计划停机', 'PLANNED_STOP',
     'EXCLUDED', 1, '#909399', 70, '保养、会议等计划内停机'),
    (8, 1, 0, 'OTHER', '其他损失', 'OTHER',
     'AVAILABILITY', 0, '#606266', 80, '待料、待人和其他非计划时间损失');

INSERT INTO equipment_oee_target
    (id, tenant_id, target_name, target_level, organization_id,
     availability_target, performance_target, quality_target, oee_target,
     effective_start_date, description)
VALUES
    (1, 1, '企业年度OEE目标', 'ENTERPRISE', 1,
     0.900000, 0.950000, 0.995000, 0.850725,
     '2026-01-01', '演示企业目标，由三项分目标精确相乘');

INSERT INTO system_menu
    (id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
     component_path, permission_code, icon, sort_order)
VALUES
    (41, 1, 40, 'MENU', '班次日历', 'OeeCalendar', '/oee/calendar',
     'views/oee/calendar/OeeCalendarView.vue',
     'oee:calendar:view', 'Calendar', 41),
    (42, 1, 40, 'MENU', '损失原因', 'OeeLossReasons', '/oee/loss-reasons',
     'views/oee/loss-reasons/OeeLossReasonView.vue',
     'oee:loss-reason:view', 'Warning', 42),
    (43, 1, 40, 'MENU', 'OEE目标', 'OeeTargets', '/oee/targets',
     'views/oee/targets/OeeTargetView.vue',
     'oee:target:view', 'Aim', 43),
    (44, 1, 40, 'MENU', 'OEE数据维护', 'OeeRecords', '/oee/records',
     'views/oee/records/OeeRecordView.vue',
     'oee:record:view', 'EditPen', 44),
    (45, 1, 40, 'MENU', '产量与停机', 'OeeProduction', '/oee/production',
     'views/oee/production/OeeProductionView.vue',
     'oee:production:view', 'DataLine', 45),
    (46, 1, 40, 'MENU', 'OEE分析', 'OeeAnalysis', '/oee/analysis',
     'views/oee/analysis/OeeAnalysisView.vue',
     'oee:analysis:view', 'TrendCharts', 46),
    (4101, 1, 41, 'BUTTON', '维护班次', NULL, NULL, NULL,
     'oee:shift:manage', NULL, 1),
    (4102, 1, 41, 'BUTTON', '维护生产日历', NULL, NULL, NULL,
     'oee:calendar:manage', NULL, 2),
    (4201, 1, 42, 'BUTTON', '维护损失原因', NULL, NULL, NULL,
     'oee:loss-reason:manage', NULL, 1),
    (4301, 1, 43, 'BUTTON', '维护OEE目标', NULL, NULL, NULL,
     'oee:target:manage', NULL, 1),
    (4401, 1, 44, 'BUTTON', '维护OEE数据', NULL, NULL, NULL,
     'oee:record:manage', NULL, 1),
    (4402, 1, 44, 'BUTTON', '导入OEE数据', NULL, NULL, NULL,
     'oee:record:import', NULL, 2),
    (4403, 1, 44, 'BUTTON', '审核OEE数据', NULL, NULL, NULL,
     'oee:record:approve', NULL, 3),
    (4404, 1, 44, 'BUTTON', '锁定或解锁OEE数据', NULL, NULL, NULL,
     'oee:record:lock', NULL, 4),
    (4405, 1, 44, 'BUTTON', '重新计算OEE', NULL, NULL, NULL,
     'oee:record:recalculate', NULL, 5),
    (4501, 1, 45, 'BUTTON', '维护产量', NULL, NULL, NULL,
     'oee:output:manage', NULL, 1),
    (4502, 1, 45, 'BUTTON', '维护停机', NULL, NULL, NULL,
     'oee:downtime:manage', NULL, 2);

INSERT INTO system_role_menu (tenant_id, role_id, menu_id)
SELECT 1, role_id, menu_id
FROM (
    SELECT 1 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (
          40, 41, 42, 43, 44, 45, 46,
          4101, 4102, 4201, 4301, 4401, 4402, 4403, 4404, 4405,
          4501, 4502
      )
    UNION ALL
    SELECT 2 AS role_id, id AS menu_id
    FROM system_menu
    WHERE tenant_id = 1
      AND id IN (
          40, 41, 42, 43, 44, 45, 46,
          4101, 4102, 4201, 4301, 4401, 4402, 4403, 4404, 4405,
          4501, 4502
      )
) grants
WHERE NOT EXISTS (
    SELECT 1
    FROM system_role_menu existing
    WHERE existing.tenant_id = 1
      AND existing.role_id = grants.role_id
      AND existing.menu_id = grants.menu_id
);
