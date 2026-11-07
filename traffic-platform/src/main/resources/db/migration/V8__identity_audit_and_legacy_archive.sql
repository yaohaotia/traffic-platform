CREATE TABLE identity_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    operation VARCHAR(48) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE legacy_import_batch (
    id VARCHAR(36) PRIMARY KEY,
    source_hash CHAR(64) NOT NULL UNIQUE,
    mapping_hash CHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    user_count INT NOT NULL,
    report_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE legacy_user_mapping (
    legacy_id VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_legacy_user FOREIGN KEY(user_id) REFERENCES identity_user(id),
    CONSTRAINT fk_legacy_mapping_batch FOREIGN KEY(batch_id) REFERENCES legacy_import_batch(id)
);
CREATE TABLE legacy_report_archive (
    legacy_id VARCHAR(80) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    original_status INT NOT NULL,
    mapped_state VARCHAR(64) NOT NULL,
    location VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_legacy_archive_user FOREIGN KEY(user_id) REFERENCES identity_user(id),
    CONSTRAINT fk_legacy_archive_batch FOREIGN KEY(batch_id) REFERENCES legacy_import_batch(id)
);
CREATE INDEX ix_legacy_archive_owner ON legacy_report_archive(user_id,created_at);
