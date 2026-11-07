CREATE TABLE maintenance_checkpoint (
    job_name VARCHAR(80) PRIMARY KEY,
    first_cursor BIGINT NOT NULL DEFAULT 0,
    second_cursor BIGINT NOT NULL DEFAULT 0,
    last_success TIMESTAMP(6),
    last_failure TIMESTAMP(6),
    last_result VARCHAR(80)
);
CREATE TABLE ledger_drift (
    target_type VARCHAR(24) NOT NULL,
    target_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    last_checked_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(target_type,target_id)
);
