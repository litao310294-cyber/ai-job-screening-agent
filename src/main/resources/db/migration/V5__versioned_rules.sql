CREATE TABLE demo_rule_version (
    user_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    config_json LONGTEXT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(user_id,version)
) CHARACTER SET utf8mb4;
ALTER TABLE demo_analysis_operation ADD COLUMN rules_json LONGTEXT NULL;
ALTER TABLE demo_analysis_operation ADD COLUMN rule_result_json LONGTEXT NULL;
