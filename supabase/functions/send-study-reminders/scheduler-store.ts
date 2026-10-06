import { PostgresDatabase } from "../telegram-webhook/postgres-database.ts";

export type ReminderCandidate = {
  id: string | bigint;
  telegram_user_id: string | bigint;
  preferred_language: string | null;
  selected_exam_type_id: string | bigint;
  claim_token: string;
  last_user_activity_at: Date | string;
  last_reminder_sent_at: Date | string | null;
  reminder_retry_after: Date | string | null;
  reminder_delivery_blocked_at: Date | string | null;
  reminder_attempt_started_at: Date | string | null;
  study_reminders_enabled: boolean;
  exam_active: boolean;
  access_level: string;
  practice_limit: number;
  practice_used: number;
  mock_limit: number;
  mocks_used: number;
  latest_mock_status: string | null;
  latest_mock_id: string | bigint | null;
  latest_mock_deadline_at: Date | string | null;
  has_practice: boolean;
  payment_request_id: string | bigint | null;
  payment_pending_review: boolean;
};

export class PostgresStudyReminderSchedulerStore {
  constructor(private readonly database: PostgresDatabase) {}

  async claimBatch(batchSize: number, claimTtlMinutes = 15): Promise<ReminderCandidate[]> {
    return await this.database.withConnection(async (client) => {
      await client.queryArray`BEGIN`;
      try {
        const settings = (await client.queryObject<{ enabled: boolean; startAt: Date | string | null; endAt: Date | string | null }>`
          SELECT study_reminders_globally_enabled AS enabled,
                 study_reminders_start_at AS "startAt",
                 study_reminders_end_at AS "endAt"
          FROM app_settings WHERE id=1 FOR SHARE
        `).rows[0];
        if (!settings) throw new Error("REMINDER_SETTINGS_MISSING");
        const now = Date.now();
        if (!settings.enabled || settings.startAt === null || settings.endAt === null
          || now < new Date(settings.startAt).getTime() || now > new Date(settings.endAt).getTime()) {
          await client.queryArray`COMMIT`;
          return [];
        }
        const rows = await client.queryObject<ReminderCandidate>`
          WITH candidates AS (
            SELECT u.id
            FROM bot_users u
            JOIN access_entitlements ae ON ae.user_id=u.id AND ae.exam_type_id=u.selected_exam_type_id
            CROSS JOIN app_settings s
            WHERE s.id=1
              AND s.study_reminders_globally_enabled=TRUE
              AND s.study_reminders_start_at IS NOT NULL AND s.study_reminders_end_at IS NOT NULL
              AND clock_timestamp() BETWEEN s.study_reminders_start_at AND s.study_reminders_end_at
              AND u.registration_status='COMPLETED' AND u.telegram_user_id IS NOT NULL
              AND u.telegram_user_id>0 AND u.selected_exam_type_id IS NOT NULL AND u.study_reminders_enabled=TRUE
              AND u.reminder_delivery_blocked_at IS NULL
              AND u.last_user_activity_at <= clock_timestamp()-INTERVAL '8 hours'
              AND (u.last_reminder_sent_at IS NULL OR u.last_reminder_sent_at <= clock_timestamp()-INTERVAL '8 hours')
              AND (u.reminder_attempt_started_at IS NULL OR u.reminder_attempt_started_at <= clock_timestamp()-INTERVAL '8 hours')
              AND (u.reminder_retry_after IS NULL OR u.reminder_retry_after <= clock_timestamp())
              AND (u.reminder_claim_token IS NULL OR u.reminder_claimed_at <= clock_timestamp()-(${claimTtlMinutes}*INTERVAL '1 minute'))
            ORDER BY u.last_user_activity_at,u.id
            LIMIT ${batchSize}
            FOR UPDATE OF u SKIP LOCKED
          ), claimed AS (
            UPDATE bot_users u
            SET reminder_claim_token=gen_random_uuid(), reminder_claimed_at=clock_timestamp()
            FROM candidates c WHERE u.id=c.id
            RETURNING u.id,u.telegram_user_id,u.preferred_language,u.selected_exam_type_id,
              u.reminder_claim_token AS claim_token,u.last_user_activity_at,u.last_reminder_sent_at,
              u.reminder_retry_after,u.reminder_delivery_blocked_at,u.study_reminders_enabled,
              u.reminder_attempt_started_at
          )
          SELECT c.*,
            e.active AS exam_active,
            ae.access_level,ae.practice_limit,ae.practice_used,ae.mock_limit,ae.mocks_used,
            m.status AS latest_mock_status,m.id AS latest_mock_id,m.deadline_at AS latest_mock_deadline_at,
            EXISTS(
              SELECT 1 FROM practice_sessions ps
              JOIN practice_deliveries pd ON pd.id=ps.current_delivery_id
              JOIN question_versions qv ON qv.id=pd.version_id
              WHERE ps.user_id=c.id AND qv.exam_type_id=c.selected_exam_type_id
            ) OR EXISTS(
              SELECT 1 FROM practice_usage pu
              JOIN questions q ON q.id=pu.question_id
              JOIN question_versions qv ON qv.id=q.current_version_id
              WHERE pu.user_id=c.id AND qv.exam_type_id=c.selected_exam_type_id
            ) AS has_practice,
            (SELECT p.id FROM payment_requests p WHERE p.open_user_id=c.id
              AND p.target_exam_type_id=c.selected_exam_type_id
              AND p.status IN ('AWAITING_REFERENCE','AWAITING_RECEIPT')
              AND NOT EXISTS (SELECT 1 FROM payment_requests review WHERE review.open_user_id=c.id
                AND review.target_exam_type_id=c.selected_exam_type_id AND review.status='PENDING_REVIEW')
              ORDER BY p.id DESC LIMIT 1) AS payment_request_id,
            EXISTS(SELECT 1 FROM payment_requests p WHERE p.open_user_id=c.id
              AND p.target_exam_type_id=c.selected_exam_type_id AND p.status='PENDING_REVIEW') AS payment_pending_review
          FROM claimed c
          JOIN exam_types e ON e.id=c.selected_exam_type_id
          JOIN access_entitlements ae ON ae.user_id=c.id AND ae.exam_type_id=c.selected_exam_type_id
          LEFT JOIN LATERAL (
            SELECT ma.id,ma.status,ma.deadline_at FROM mock_attempts ma
            WHERE ma.user_id=c.id AND ma.exam_type_id=c.selected_exam_type_id
            ORDER BY ma.id DESC LIMIT 1
          ) m ON TRUE
          ORDER BY c.last_user_activity_at,c.id
        `;
        await client.queryArray`COMMIT`;
        return rows.rows;
      } catch (error) {
        try { await client.queryArray`ROLLBACK`; } catch { /* preserve original error */ }
        throw error;
      }
    });
  }

  async beginDelivery(userId: string, claimToken: string): Promise<boolean> {
    return await this.database.withConnection(async (client) => {
      await client.queryArray`BEGIN`;
      try {
        const settings = (await client.queryObject<{ enabled: boolean; startAt: Date | string | null; endAt: Date | string | null }>`
          SELECT study_reminders_globally_enabled AS enabled,
                 study_reminders_start_at AS "startAt",
                 study_reminders_end_at AS "endAt"
          FROM app_settings WHERE id=1 FOR SHARE
        `).rows[0];
        const now = Date.now();
        if (!settings || settings.enabled !== true || settings.startAt === null || settings.endAt === null
          || now < new Date(settings.startAt).getTime() || now > new Date(settings.endAt).getTime()) {
          await client.queryArray`COMMIT`;
          return false;
        }
        const result = await client.queryObject<{ eligible: boolean }>`
          UPDATE bot_users u SET reminder_attempt_started_at=clock_timestamp()
          WHERE u.id=${userId} AND u.reminder_claim_token=${claimToken}::uuid
            AND u.registration_status='COMPLETED' AND u.study_reminders_enabled=TRUE
            AND u.reminder_delivery_blocked_at IS NULL
            AND u.last_user_activity_at <= clock_timestamp()-INTERVAL '8 hours'
            AND (u.last_reminder_sent_at IS NULL OR u.last_reminder_sent_at <= clock_timestamp()-INTERVAL '8 hours')
            AND (u.reminder_attempt_started_at IS NULL OR u.reminder_attempt_started_at <= clock_timestamp()-INTERVAL '8 hours')
            AND (u.reminder_retry_after IS NULL OR u.reminder_retry_after <= clock_timestamp())
          RETURNING TRUE AS eligible
        `;
        await client.queryArray`COMMIT`;
        return result.rows[0]?.eligible === true;
      } catch (error) {
        try { await client.queryArray`ROLLBACK`; } catch { /* preserve original error */ }
        throw error;
      }
    });
  }

  async finishClaim(userId: string, claimToken: string, result: "sent" | "blocked" | "retry", retrySeconds = 3600): Promise<void> {
    await this.database.withConnection(async (client) => {
      if (result === "sent") {
        await client.queryArray`
          UPDATE bot_users SET last_reminder_sent_at=clock_timestamp(),reminder_claim_token=NULL,reminder_claimed_at=NULL,
            reminder_attempt_started_at=NULL,reminder_retry_after=NULL
          WHERE id=${userId} AND reminder_claim_token=${claimToken}::uuid
        `;
      } else if (result === "blocked") {
        await client.queryArray`
          UPDATE bot_users SET reminder_delivery_blocked_at=clock_timestamp(),reminder_claim_token=NULL,reminder_claimed_at=NULL,
            reminder_attempt_started_at=NULL,reminder_retry_after=NULL
          WHERE id=${userId} AND reminder_claim_token=${claimToken}::uuid
        `;
      } else {
        await client.queryArray`
          UPDATE bot_users SET reminder_claim_token=NULL,reminder_claimed_at=NULL,reminder_attempt_started_at=NULL,
            reminder_retry_after=clock_timestamp()+(${retrySeconds}*INTERVAL '1 second')
          WHERE id=${userId} AND reminder_claim_token=${claimToken}::uuid
        `;
      }
    });
  }
}
