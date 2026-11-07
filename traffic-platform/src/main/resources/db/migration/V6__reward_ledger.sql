ALTER TABLE campaign ADD COLUMN budget_reserved DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE campaign ADD COLUMN budget_spent DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE campaign ADD CONSTRAINT ck_campaign_budget_balance CHECK
    (budget_reserved>=0 AND budget_spent>=0 AND budget_reserved+budget_spent<=budget_amount);

CREATE TABLE reward_order (
    id VARCHAR(36) PRIMARY KEY,
    report_id BIGINT NOT NULL UNIQUE,
    decision_id VARCHAR(36) NOT NULL UNIQUE,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_reward_report FOREIGN KEY(report_id) REFERENCES traffic_report(id),
    CONSTRAINT fk_reward_decision FOREIGN KEY(decision_id) REFERENCES review_decision(id),
    CONSTRAINT ck_reward_amount CHECK(amount>0)
);
CREATE INDEX ix_reward_state ON reward_order(state,created_at);
CREATE TABLE budget_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    total_delta DECIMAL(12,2) NOT NULL DEFAULT 0,
    reserved_delta DECIMAL(12,2) NOT NULL DEFAULT 0,
    spent_delta DECIMAL(12,2) NOT NULL DEFAULT 0,
    actor_id BIGINT,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_budget_source UNIQUE(campaign_id,source_key,kind),
    CONSTRAINT fk_budget_campaign FOREIGN KEY(campaign_id) REFERENCES campaign(id)
);
CREATE TABLE wallet_account (
    user_id BIGINT PRIMARY KEY,
    available DECIMAL(12,2) NOT NULL DEFAULT 0,
    frozen DECIMAL(12,2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_wallet_user FOREIGN KEY(user_id) REFERENCES identity_user(id),
    CONSTRAINT ck_wallet_balances CHECK(available>=0 AND frozen>=0)
);
CREATE TABLE wallet_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    available_delta DECIMAL(12,2) NOT NULL,
    frozen_delta DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_wallet_source UNIQUE(user_id,source_id,kind),
    CONSTRAINT fk_entry_wallet FOREIGN KEY(user_id) REFERENCES wallet_account(user_id)
);
CREATE TABLE reward_correction (
    id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_correction_order FOREIGN KEY(order_id) REFERENCES reward_order(id)
);
CREATE TABLE reward_activation (
    intent_id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_activation_intent FOREIGN KEY(intent_id) REFERENCES review_approval_intent(id)
);
CREATE TABLE withdrawal_order (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    region_code VARCHAR(80) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    request_key CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    approver_id BIGINT,
    reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_withdrawal_request UNIQUE(user_id,request_key),
    CONSTRAINT fk_withdrawal_wallet FOREIGN KEY(user_id) REFERENCES wallet_account(user_id),
    CONSTRAINT ck_withdrawal_amount CHECK(amount>0)
);
CREATE INDEX ix_withdrawal_region ON withdrawal_order(region_code,state,created_at);
CREATE TABLE notification (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_event_id VARCHAR(36) NOT NULL,
    title VARCHAR(150) NOT NULL,
    body VARCHAR(500) NOT NULL,
    read_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_notification_event UNIQUE(user_id,source_event_id),
    CONSTRAINT fk_notification_user FOREIGN KEY(user_id) REFERENCES identity_user(id)
);
