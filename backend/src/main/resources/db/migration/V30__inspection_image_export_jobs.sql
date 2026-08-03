CREATE TABLE inspection_export_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    export_code VARCHAR(64) NOT NULL,
    job_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    include_images TINYINT(1) NOT NULL DEFAULT 1,
    query_json LONGTEXT NOT NULL,
    data_scope_json LONGTEXT NOT NULL,
    requested_by BIGINT NOT NULL,
    task_count INT NOT NULL DEFAULT 0,
    result_count INT NOT NULL DEFAULT 0,
    image_count INT NOT NULL DEFAULT 0,
    estimated_image_bytes BIGINT NOT NULL DEFAULT 0,
    file_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_time DATETIME(3) NULL,
    completed_time DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_export_job_code (tenant_id, export_code),
    KEY idx_inspection_export_job_pending (job_status, created_time),
    KEY idx_inspection_export_job_requester (tenant_id, requested_by, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='点检结果图片导出任务';

CREATE TABLE inspection_export_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    export_job_id BIGINT NOT NULL,
    part_number INT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1000) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    image_count INT NOT NULL DEFAULT 0,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_export_file_part (tenant_id, export_job_id, part_number),
    KEY idx_inspection_export_file_job (tenant_id, export_job_id),
    CONSTRAINT fk_inspection_export_file_job
        FOREIGN KEY (export_job_id) REFERENCES inspection_export_job (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='点检结果图片导出分卷';
