ALTER TABLE outbox_event ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE outbox_event ADD COLUMN lease_until TIMESTAMP(6);
ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE outbox_event ADD COLUMN last_error_code VARCHAR(80);
ALTER TABLE outbox_event ADD COLUMN last_error_message VARCHAR(500);
ALTER TABLE outbox_event ADD COLUMN published_at TIMESTAMP(6);
ALTER TABLE outbox_event ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
CREATE INDEX ix_outbox_due ON outbox_event(status,next_attempt_at,created_at);
CREATE INDEX ix_outbox_lease ON outbox_event(status,lease_until);

ALTER TABLE detection_task ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE detection_task ADD COLUMN lease_until TIMESTAMP(6);
ALTER TABLE detection_task ADD COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE detection_task ADD COLUMN last_signal_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE detection_task ADD COLUMN model_version VARCHAR(128);
ALTER TABLE detection_task ADD COLUMN result_score DOUBLE;
ALTER TABLE detection_task ADD COLUMN result_threshold DOUBLE;
ALTER TABLE detection_task ADD COLUMN last_error_code VARCHAR(80);
ALTER TABLE detection_task ADD COLUMN last_error_message VARCHAR(500);
ALTER TABLE detection_task ADD COLUMN completed_at TIMESTAMP(6);
ALTER TABLE detection_task ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
CREATE INDEX ix_detection_due ON detection_task(status,next_attempt_at);
CREATE INDEX ix_detection_lease ON detection_task(status,lease_until);

CREATE TABLE detection_attempt (
    task_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    lease_token VARCHAR(36) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    outcome VARCHAR(32) NOT NULL,
    error_code VARCHAR(80),
    PRIMARY KEY(task_id,version),
    CONSTRAINT fk_attempt_task FOREIGN KEY(task_id) REFERENCES detection_task(id)
);
CREATE TABLE detection_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    operator_id BIGINT NOT NULL,
    operation VARCHAR(40) NOT NULL,
    event_id VARCHAR(36),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_operation_task FOREIGN KEY(task_id) REFERENCES detection_task(id)
);
