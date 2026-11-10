CREATE TABLE demo_resume_import (
    id CHAR(36) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    semantic_fingerprint CHAR(64) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    original_import_id CHAR(36) NULL,
    provider_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    confirmed_revision BIGINT NULL,
    profile_artifact_id BIGINT NULL,
    file_bytes LONGBLOB NULL,
    raw_text MEDIUMTEXT NULL,
    page_count INT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    confirmed_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_resume_content(user_id,content_sha256,semantic_fingerprint,source_kind),
    FOREIGN KEY(profile_artifact_id) REFERENCES candidate_profile_artifact(id)
) CHARACTER SET utf8mb4;

CREATE TABLE demo_profile_guard (
    user_id VARCHAR(64) PRIMARY KEY
) CHARACTER SET utf8mb4;
INSERT INTO demo_profile_guard(user_id) VALUES ('demo-li-tao');

CREATE TABLE profile_vector_artifact (
    user_id VARCHAR(255) NOT NULL,
    profile_version BIGINT NOT NULL,
    records_json LONGTEXT NOT NULL,
    PRIMARY KEY(user_id,profile_version)
) CHARACTER SET utf8mb4;

CREATE TABLE demo_model_call (
    id CHAR(36) PRIMARY KEY,
    role VARCHAR(128) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    input_characters INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    finish_reason VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) CHARACTER SET utf8mb4;
