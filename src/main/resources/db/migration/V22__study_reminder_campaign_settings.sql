ALTER TABLE app_settings
    ADD COLUMN study_reminders_start_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE app_settings
    ADD COLUMN study_reminders_end_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE app_settings
    ADD COLUMN study_reminders_updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE app_settings
    ADD COLUMN study_reminders_updated_by VARCHAR(120);

ALTER TABLE app_settings
    ADD CONSTRAINT app_settings_study_reminder_window_check
    CHECK (
        (study_reminders_start_at IS NULL AND study_reminders_end_at IS NULL)
        OR (study_reminders_start_at IS NOT NULL AND study_reminders_end_at IS NOT NULL
            AND study_reminders_end_at > study_reminders_start_at)
    );
