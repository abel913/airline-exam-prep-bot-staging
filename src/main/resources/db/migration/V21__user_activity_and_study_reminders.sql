-- Track real Telegram activity and reminder delivery state without changing
-- existing user entitlements or learning state. Existing accounts start at
-- migration time, preventing an immediate reminder rollout.
ALTER TABLE bot_users ADD COLUMN last_user_activity_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE bot_users ADD COLUMN last_reminder_sent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE bot_users ADD COLUMN study_reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE bot_users ADD COLUMN last_activity_update_id BIGINT CHECK (last_activity_update_id IS NULL OR last_activity_update_id >= 0);
ALTER TABLE bot_users ADD COLUMN reminder_claim_token UUID;
ALTER TABLE bot_users ADD COLUMN reminder_claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE bot_users ADD COLUMN reminder_attempt_started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE bot_users ADD COLUMN reminder_delivery_blocked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE bot_users ADD COLUMN reminder_retry_after TIMESTAMP WITH TIME ZONE;

CREATE INDEX bot_users_study_reminder_eligibility_idx
    ON bot_users (registration_status, study_reminders_enabled, last_user_activity_at, last_reminder_sent_at);

-- Deployment protection: no existing user is messaged until a controlled
-- staging E2E has passed and this setting is explicitly enabled.
ALTER TABLE app_settings ADD COLUMN study_reminders_globally_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_settings ADD COLUMN study_reminders_test_telegram_user_id BIGINT
    CHECK (study_reminders_test_telegram_user_id IS NULL OR study_reminders_test_telegram_user_id > 0);
