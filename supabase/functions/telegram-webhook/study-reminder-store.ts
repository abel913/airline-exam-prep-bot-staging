import { PostgresDatabase } from "./postgres-database.ts";

export type ReminderPreference = { language: "en" | "am"; enabled: boolean };

export class PostgresStudyReminderStore {
  constructor(private readonly database: PostgresDatabase) {}

  async preference(telegramId: string): Promise<ReminderPreference | null> {
    return await this.database.withConnection(async (client) => {
      const result = await client.queryObject<{ language: string | null; enabled: boolean }>`
        SELECT preferred_language AS language, study_reminders_enabled AS enabled
        FROM bot_users WHERE telegram_user_id=${telegramId} AND registration_status='COMPLETED'
      `;
      const row = result.rows[0];
      return row ? { language: row.language === "am" ? "am" : "en", enabled: row.enabled } : null;
    });
  }

  async setEnabled(telegramId: string, enabled: boolean): Promise<ReminderPreference | null> {
    return await this.database.withConnection(async (client) => {
      const result = await client.queryObject<{ language: string | null; enabled: boolean }>`
        UPDATE bot_users
        SET study_reminders_enabled=${enabled}, reminder_claim_token=NULL, reminder_claimed_at=NULL
        WHERE telegram_user_id=${telegramId} AND registration_status='COMPLETED'
        RETURNING preferred_language AS language, study_reminders_enabled AS enabled
      `;
      const row = result.rows[0];
      return row ? { language: row.language === "am" ? "am" : "en", enabled: row.enabled } : null;
    });
  }

  async eligibleAt(telegramId: string, now: Date): Promise<boolean> {
    return await this.database.withConnection(async (client) => {
      const at = now.toISOString();
      const result = await client.queryObject<{ eligible: boolean }>`
        SELECT EXISTS (
          SELECT 1 FROM bot_users u
          WHERE u.telegram_user_id=${telegramId} AND u.registration_status='COMPLETED'
            AND u.study_reminders_enabled=TRUE AND u.reminder_delivery_blocked_at IS NULL
            AND u.last_user_activity_at <= ${at}::timestamptz-INTERVAL '8 hours'
            AND (u.last_reminder_sent_at IS NULL OR u.last_reminder_sent_at <= ${at}::timestamptz-INTERVAL '8 hours')
            AND (u.reminder_attempt_started_at IS NULL OR u.reminder_attempt_started_at <= ${at}::timestamptz-INTERVAL '8 hours')
            AND (u.reminder_retry_after IS NULL OR u.reminder_retry_after <= ${at}::timestamptz)
        ) AS eligible
      `;
      return result.rows[0]?.eligible === true;
    });
  }
}
