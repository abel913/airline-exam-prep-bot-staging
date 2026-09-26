ALTER TABLE question_versions ADD COLUMN tags VARCHAR(263) NOT NULL DEFAULT '';
ALTER TABLE app_settings ADD COLUMN maintenance_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Date windows and bounded per-user history used by Phase 10 queries.
CREATE INDEX practice_answer_time_idx ON practice_deliveries(answered_at,user_id);
CREATE INDEX practice_usage_time_idx ON practice_usage(created_at,user_id);
CREATE INDEX mock_activity_time_idx ON mock_attempts(created_at,user_id);
CREATE INDEX registration_time_idx ON bot_users(registration_completed_at);
CREATE INDEX payment_review_time_idx ON payment_requests(reviewed_at,status);
CREATE INDEX practice_version_answer_idx ON practice_deliveries(version_id,selected_option,answered_at);
CREATE INDEX mock_item_version_idx ON mock_items(version_id,selected_option,attempt_id);
