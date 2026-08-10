ALTER TABLE system_user
    ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 0 AFTER version;

CREATE TABLE auth_session (
    session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_version BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    real_name VARCHAR(100) NOT NULL DEFAULT '',
    login_ip VARCHAR(64) NOT NULL DEFAULT '',
    user_agent VARCHAR(500) NOT NULL DEFAULT '',
    login_time DATETIME(3) NOT NULL,
    last_active_time DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    refresh_jti_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revoked_at DATETIME(3) NULL,
    revocation_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_id),
    KEY idx_auth_session_tenant_status_expiry (tenant_id, status, expires_at),
    KEY idx_auth_session_tenant_user_status (tenant_id, user_id, status),
    KEY idx_auth_session_expiry (expires_at),
    CONSTRAINT chk_auth_session_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Persistent authentication session and refresh-token rotation state';

CREATE TABLE auth_login_security_state (
    tenant_id BIGINT NOT NULL,
    principal_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NULL,
    failure_count INT NOT NULL DEFAULT 0,
    window_started_at DATETIME(3) NOT NULL,
    locked_until DATETIME(3) NULL,
    last_failure_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, principal_key),
    KEY idx_auth_login_security_user (tenant_id, user_id),
    KEY idx_auth_login_security_expiry (locked_until, last_failure_at),
    CONSTRAINT chk_auth_login_security_failure_count CHECK (failure_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Persistent login failure window and temporary lock state';

CREATE TABLE request_idempotency (
    tenant_id BIGINT NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 1,
    lease_expires_at DATETIME(3) NOT NULL,
    response_status INT NULL,
    response_content_type VARCHAR(100) NULL,
    response_payload MEDIUMBLOB NULL,
    completed_at DATETIME(3) NULL,
    expires_at DATETIME(3) NOT NULL,
    error_classification VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, key_hash),
    KEY idx_request_idempotency_state_expiry (state, expires_at),
    KEY idx_request_idempotency_lease (state, lease_expires_at),
    CONSTRAINT chk_request_idempotency_state
        CHECK (state IN ('PROCESSING', 'COMPLETED', 'UNKNOWN')),
    CONSTRAINT chk_request_idempotency_response_status
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Durable request idempotency ownership, result replay and unknown outcomes';
