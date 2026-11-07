CREATE INDEX ix_correction_user_state ON reward_correction(user_id,state);
CREATE INDEX ix_notification_recipient ON notification(user_id,created_at);
CREATE INDEX ix_wallet_entries_user ON wallet_entry(user_id,id);
