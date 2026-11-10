CREATE TABLE demo_chat_state (
    user_id VARCHAR(64) PRIMARY KEY,
    generation BIGINT NOT NULL DEFAULT 0,
    next_sequence BIGINT NOT NULL DEFAULT 1,
    active_request_id CHAR(36) NULL
) CHARACTER SET utf8mb4;
INSERT INTO demo_chat_state(user_id) VALUES ('demo-li-tao');

CREATE TABLE demo_chat_operation (
    id CHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    sequence_number BIGINT NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    job_snapshot_id CHAR(36) NOT NULL,
    analysis_id CHAR(36) NOT NULL,
    scope_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_json LONGTEXT NULL,
    context_json LONGTEXT NULL,
    answer_json LONGTEXT NULL,
    error_code VARCHAR(64) NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_chat_request(user_id,client_request_id),
    UNIQUE KEY uk_chat_order(user_id,generation,sequence_number),
    KEY ix_chat_scope(user_id,generation,scope_fingerprint,sequence_number)
) CHARACTER SET utf8mb4;
