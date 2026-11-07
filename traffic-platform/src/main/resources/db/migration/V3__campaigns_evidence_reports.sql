CREATE TABLE campaign (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    region_code VARCHAR(80) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    reward_amount DECIMAL(12,2) NOT NULL,
    budget_amount DECIMAL(12,2) NOT NULL,
    state VARCHAR(24) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_campaign_region FOREIGN KEY (region_code) REFERENCES identity_region (code),
    CONSTRAINT fk_campaign_creator FOREIGN KEY (created_by) REFERENCES identity_user (id),
    CONSTRAINT ck_campaign_window CHECK (starts_at < ends_at),
    CONSTRAINT ck_campaign_amount CHECK (reward_amount > 0 AND budget_amount >= reward_amount)
);
CREATE INDEX ix_campaign_region_state ON campaign (region_code, state, id);

CREATE TABLE evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    object_key VARCHAR(100) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    media_type VARCHAR(30) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidence_owner FOREIGN KEY (owner_id) REFERENCES identity_user (id),
    CONSTRAINT uk_evidence_object UNIQUE (object_key)
);
CREATE INDEX ix_evidence_owner ON evidence (owner_id, id);

CREATE TABLE traffic_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    evidence_id BIGINT NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    region_code VARCHAR(80) NOT NULL,
    location VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    capture_time TIMESTAMP NOT NULL,
    reward_amount DECIMAL(12,2) NOT NULL,
    detection_status VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    reward_status VARCHAR(32) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_request UNIQUE (user_id, request_key),
    CONSTRAINT uk_report_evidence UNIQUE (campaign_id, user_id, evidence_sha256),
    CONSTRAINT fk_report_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT fk_report_user FOREIGN KEY (user_id) REFERENCES identity_user (id),
    CONSTRAINT fk_report_evidence FOREIGN KEY (evidence_id) REFERENCES evidence (id)
);
CREATE INDEX ix_report_user ON traffic_report (user_id, id);
CREATE INDEX ix_report_region ON traffic_report (region_code, id);

CREATE TABLE detection_task (
    id VARCHAR(36) PRIMARY KEY,
    report_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_detection_report UNIQUE (report_id),
    CONSTRAINT fk_detection_report FOREIGN KEY (report_id) REFERENCES traffic_report (id)
);

CREATE TABLE outbox_event (
    event_id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_outbox_pending ON outbox_event (status, created_at);
