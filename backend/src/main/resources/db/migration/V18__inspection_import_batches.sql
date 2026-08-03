CREATE TABLE inspection_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    batch_code CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    import_status VARCHAR(24) NOT NULL,
    payload_json JSON NULL,
    errors_json JSON NULL,
    result_json JSON NULL,
    item_rows INT NOT NULL DEFAULT 0,
    scheme_rows INT NOT NULL DEFAULT 0,
    relation_rows INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    committed_by BIGINT NULL,
    committed_time DATETIME(3) NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_import_batch_code (tenant_id, batch_code),
    KEY idx_inspection_import_batch_status (tenant_id, import_status, created_time),
    CONSTRAINT fk_inspection_import_batch_tenant
        FOREIGN KEY (tenant_id) REFERENCES system_tenant(id),
    CONSTRAINT fk_inspection_import_batch_creator
        FOREIGN KEY (created_by) REFERENCES system_user(id),
    CONSTRAINT fk_inspection_import_batch_committer
        FOREIGN KEY (committed_by) REFERENCES system_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
