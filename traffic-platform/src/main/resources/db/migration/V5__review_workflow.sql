CREATE TABLE review_task (
    id VARCHAR(36) PRIMARY KEY,
    report_id BIGINT NOT NULL,
    source_event_id VARCHAR(36) NOT NULL,
    source_detection_version BIGINT NOT NULL,
    region_code VARCHAR(80) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    assignee_id BIGINT,
    preferred_reviewer_id BIGINT,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(6),
    primary_reviewer_id BIGINT,
    transfer_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at TIMESTAMP(6),
    CONSTRAINT uk_review_report UNIQUE(report_id),
    CONSTRAINT fk_review_report FOREIGN KEY(report_id) REFERENCES traffic_report(id)
);
CREATE INDEX ix_review_ready ON review_task(region_code,status,stage,created_at);
CREATE INDEX ix_review_assignee ON review_task(assignee_id,status);
CREATE INDEX ix_review_lease ON review_task(status,lease_until);

CREATE TABLE review_decision (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    task_version BIGINT NOT NULL,
    stage VARCHAR(24) NOT NULL,
    actor_id BIGINT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    lease_token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_review_decision UNIQUE(task_id,task_version),
    CONSTRAINT fk_decision_review FOREIGN KEY(task_id) REFERENCES review_task(id)
);
CREATE TABLE review_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    actor_id BIGINT,
    from_version BIGINT NOT NULL,
    to_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_audit_review FOREIGN KEY(task_id) REFERENCES review_task(id)
);
CREATE TABLE message_inbox (
    consumer_name VARCHAR(80) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(consumer_name,event_id)
);
CREATE TABLE review_approval_intent (
    id VARCHAR(36) PRIMARY KEY,
    decision_id VARCHAR(36) NOT NULL,
    report_id BIGINT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    task_version BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(48) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_approval_decision UNIQUE(decision_id),
    CONSTRAINT uk_approval_report UNIQUE(report_id),
    CONSTRAINT fk_approval_review FOREIGN KEY(task_id) REFERENCES review_task(id),
    CONSTRAINT fk_approval_decision FOREIGN KEY(decision_id) REFERENCES review_decision(id)
);
CREATE TABLE review_appeal (
    id VARCHAR(36) PRIMARY KEY,
    report_id BIGINT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    appellant_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at TIMESTAMP(6),
    CONSTRAINT uk_review_appeal_report UNIQUE(report_id),
    CONSTRAINT fk_appeal_review FOREIGN KEY(task_id) REFERENCES review_task(id)
);
