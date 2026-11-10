CREATE TABLE demo_job_snapshot (
    id CHAR(36) NOT NULL PRIMARY KEY,
    fingerprint CHAR(64) NOT NULL,
    job_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) CHARACTER SET utf8mb4;

CREATE TABLE demo_analysis_operation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    job_snapshot_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    trace_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    dependency_json LONGTEXT NOT NULL,
    outcome_json LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_demo_request(user_id, client_request_id),
    UNIQUE KEY uk_demo_task(task_id),
    UNIQUE KEY uk_demo_trace(trace_id),
    FOREIGN KEY(job_snapshot_id) REFERENCES demo_job_snapshot(id)
) CHARACTER SET utf8mb4;

CREATE TABLE demo_trace_record (
    trace_id CHAR(36) NOT NULL,
    sequence_number BIGINT NOT NULL,
    task_id CHAR(36) NOT NULL,
    record_json LONGTEXT NOT NULL,
    PRIMARY KEY(trace_id, sequence_number),
    INDEX ix_demo_trace_task(task_id)
) CHARACTER SET utf8mb4;
