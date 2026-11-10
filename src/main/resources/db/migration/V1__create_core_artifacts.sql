CREATE TABLE candidate_profile_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    profile_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    resume_fingerprint CHAR(64) NOT NULL,
    parser_dependency_fingerprint CHAR(64) NOT NULL,
    structured_facts_json LONGTEXT NOT NULL,
    context_only_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMP NULL,
    UNIQUE KEY uk_candidate_profile_user_version (user_id, profile_version)
) CHARACTER SET utf8mb4;

CREATE TABLE candidate_profile_current (
    user_id VARCHAR(255) NOT NULL PRIMARY KEY,
    profile_artifact_id BIGINT NOT NULL,
    profile_version BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) CHARACTER SET utf8mb4;

CREATE TABLE profile_fact (
    profile_artifact_id BIGINT NOT NULL,
    fact_id VARCHAR(64) NOT NULL,
    fact_type VARCHAR(128) NOT NULL,
    normalized_text TEXT NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    source_title VARCHAR(1024) NOT NULL,
    source_section VARCHAR(1024) NOT NULL,
    source_text TEXT NOT NULL,
    PRIMARY KEY (profile_artifact_id, fact_id)
) CHARACTER SET utf8mb4;

CREATE TABLE profile_chunk_projection (
    profile_artifact_id BIGINT NOT NULL,
    chunk_id VARCHAR(128) NOT NULL,
    fact_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    source_title VARCHAR(1024) NOT NULL,
    source_section VARCHAR(1024) NOT NULL,
    source_text TEXT NOT NULL,
    metadata_json LONGTEXT NOT NULL,
    embedding_json LONGTEXT NOT NULL,
    embedding_model_id VARCHAR(255) NOT NULL,
    embedding_model_version VARCHAR(255) NULL,
    embedding_dimension INT NOT NULL,
    PRIMARY KEY (profile_artifact_id, chunk_id)
) CHARACTER SET utf8mb4;

CREATE TABLE requirement_set (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_fingerprint CHAR(64) NOT NULL,
    parser_dependency_fingerprint CHAR(64) NOT NULL,
    structured_constraints_json LONGTEXT NOT NULL,
    ignored_context_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_requirement_set_identity (job_fingerprint, parser_dependency_fingerprint)
) CHARACTER SET utf8mb4;

CREATE TABLE atomic_requirement (
    requirement_set_id BIGINT NOT NULL,
    requirement_id VARCHAR(64) NOT NULL,
    requirement_type VARCHAR(128) NOT NULL,
    normalized_text TEXT NOT NULL,
    priority VARCHAR(32) NOT NULL,
    source_text TEXT NOT NULL,
    PRIMARY KEY (requirement_set_id, requirement_id)
) CHARACTER SET utf8mb4;

CREATE TABLE analysis_canonical_result (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_fingerprint CHAR(64) NOT NULL,
    analysis_dependency_fingerprint CHAR(64) NOT NULL,
    dependency_snapshot_json LONGTEXT NOT NULL,
    result_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_analysis_canonical_identity (job_fingerprint, analysis_dependency_fingerprint)
) CHARACTER SET utf8mb4;
