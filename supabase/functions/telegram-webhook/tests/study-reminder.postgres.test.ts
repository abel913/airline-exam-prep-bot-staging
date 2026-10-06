import assert from "node:assert/strict";
import { PostgresDatabase } from "../postgres-database.ts";
import { PostgresRegistrationStore } from "../postgres-store.ts";
import { PostgresStudyReminderStore } from "../study-reminder-store.ts";
import { PostgresStudyReminderSchedulerStore } from "../../send-study-reminders/scheduler-store.ts";

const databaseUrl = Deno.env.get("EDGE_TEST_DATABASE_URL") ?? "";
const allowed = new Set(["airline_exam_bot_phase5_test", "airline_exam_bot_phase5_v20_fresh_test",
  "airline_exam_bot_phase5_v20_final_test", "airline_exam_bot_phase5_v21_test"]);
const parsed = databaseUrl ? new URL(databaseUrl) : null;
if (!parsed || !["postgres:", "postgresql:"].includes(parsed.protocol)
  || !["127.0.0.1", "localhost"].includes(parsed.hostname) || parsed.port !== "5432"
  || !allowed.has(parsed.pathname.slice(1))) {
  throw new Error("EDGE_TEST_DATABASE_URL must target an allowlisted local Phase 5 PostgreSQL test database");
}

Deno.test("PostgreSQL reminders: exact inactivity boundary, throttle, activity idempotency, and opt-out", async () => {
  const database = new PostgresDatabase(() => databaseUrl);
  const secondDatabase = new PostgresDatabase(() => databaseUrl);
  const registration = new PostgresRegistrationStore(database, () => "unused-test-key");
  const reminders = new PostgresStudyReminderStore(database);
  const scheduler = new PostgresStudyReminderSchedulerStore(database);
  const overlappingScheduler = new PostgresStudyReminderSchedulerStore(secondDatabase);
  const suffix = crypto.randomUUID().replaceAll("-", "").slice(0, 8);
  const examCode = `phase4-${suffix}`;
  const telegramId = String(BigInt(Date.now()) * 1000n + BigInt(Math.floor(Math.random() * 1000)));
  let examId: string | null = null;
  let userId: string | null = null;
  let attemptId: string | null = null;
  let originalReminderSettings: { enabled: boolean; testTelegramId: string | bigint | null } | null = null;
  try {
    originalReminderSettings = await database.withConnection(async (client) =>
      (await client.queryObject<{ enabled: boolean; testTelegramId: string | bigint | null }>`
        SELECT study_reminders_globally_enabled AS enabled,
          study_reminders_test_telegram_user_id AS "testTelegramId" FROM app_settings WHERE id=1
      `).rows[0]);
    await database.transaction(async (client) => {
      const exam = await client.queryObject<{ id: bigint }>`
        INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examCode},'Synthetic Phase 4 Test Exam','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
      `;
      examId = String(exam.rows[0].id);
      const hash = crypto.randomUUID().replaceAll("-", "").repeat(2);
      const user = await client.queryObject<{ id: bigint }>`
        INSERT INTO bot_users(telegram_user_id,preferred_language,selected_exam_type_id,registration_status,
          phone_identity_hash,registration_completed_at,created_at,updated_at)
        VALUES(${telegramId},'en',${examId},'COMPLETED',${hash},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        RETURNING id
      `;
      userId = String(user.rows[0].id);
      await client.queryArray`
        INSERT INTO access_entitlements(user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,
          questions_per_mock,practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
        VALUES(${userId},${examId},${hash},'FREE',10,2,5,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
      `;
      const attempt = await client.queryObject<{ id: bigint }>`
        INSERT INTO mock_attempts(user_id,active_user_id,exam_type_id,creation_key,status,question_count,duration_minutes,
          cursor_position,created_at,started_at,deadline_at)
        VALUES(${userId},${userId},${examId},${`synthetic-reminder-${suffix}`},'IN_PROGRESS',5,120,2,
          CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '2 hours') RETURNING id
      `;
      attemptId = String(attempt.rows[0].id);
      await client.queryArray`UPDATE bot_users SET last_user_activity_at='2026-01-01T00:00:00Z' WHERE id=${userId}`;
    });

    const t0 = new Date("2026-01-01T00:00:00Z");
    assert.equal(await reminders.eligibleAt(telegramId, new Date(t0.getTime() + 7 * 60 * 60 * 1000 + 59 * 60 * 1000)), false);
    assert.equal(await reminders.eligibleAt(telegramId, new Date(t0.getTime() + 8 * 60 * 60 * 1000)), true);

    await database.withConnection(async (client) => {
      await client.queryArray`UPDATE app_settings SET study_reminders_globally_enabled=FALSE,
        study_reminders_test_telegram_user_id=${telegramId} WHERE id=1`;
    });
    const [firstClaims, overlappingClaims] = await Promise.all([
      scheduler.claimBatch(10), overlappingScheduler.claimBatch(10),
    ]);
    assert.equal(firstClaims.length + overlappingClaims.length, 1, "overlapping scheduler claims one account exactly once");
    const candidate = firstClaims[0] ?? overlappingClaims[0];
    assert.equal(String(candidate.id), userId);
    assert.equal(candidate.latest_mock_status, "IN_PROGRESS");
    assert.equal(String(candidate.latest_mock_id), attemptId);
    assert.equal(await scheduler.beginDelivery(userId!, candidate.claim_token), true);
    const activityBeforeSend = await database.withConnection(async (client) =>
      (await client.queryObject<{ at: Date }>`SELECT last_user_activity_at AS at FROM bot_users WHERE id=${userId}`).rows[0].at);
    const deadlineBeforeSend = await database.withConnection(async (client) =>
      (await client.queryObject<{ deadline_at: Date }>`SELECT deadline_at FROM mock_attempts WHERE id=${attemptId}`).rows[0].deadline_at);
    await scheduler.finishClaim(userId!, candidate.claim_token, "sent");
    const afterReminder = await database.withConnection(async (client) =>
      (await client.queryObject<{ activity: Date; sent: Date | null }>`
        SELECT last_user_activity_at AS activity,last_reminder_sent_at AS sent FROM bot_users WHERE id=${userId}
      `).rows[0]);
    assert.equal(new Date(afterReminder.activity).getTime(), new Date(activityBeforeSend).getTime(), "sending does not count as activity");
    assert.ok(afterReminder.sent);
    const afterDeadline = await database.withConnection(async (client) =>
      (await client.queryObject<{ deadline_at: Date }>`SELECT deadline_at FROM mock_attempts WHERE id=${attemptId}`).rows[0].deadline_at);
    assert.equal(new Date(afterDeadline).getTime(), new Date(deadlineBeforeSend).getTime(), "reminder leaves mock deadline unchanged");

    await database.withConnection(async (client) => {
      await client.queryArray`UPDATE bot_users SET last_reminder_sent_at='2026-01-01T08:00:00Z' WHERE id=${userId}`;
    });
    assert.equal(await reminders.eligibleAt(telegramId, new Date("2026-01-01T08:59:59Z")), false);
    assert.equal(await reminders.eligibleAt(telegramId, new Date("2026-01-01T16:00:00Z")), true);

    const beforeActivity = await database.withConnection(async (client) =>
      (await client.queryObject<{ at: Date }>`SELECT last_user_activity_at AS at FROM bot_users WHERE id=${userId}`).rows[0].at);
    await registration.recordUserActivity(telegramId, "9000001");
    const afterActivity = await database.withConnection(async (client) =>
      (await client.queryObject<{ at: Date; updateId: bigint | null }>`
        SELECT last_user_activity_at AS at,last_activity_update_id AS "updateId" FROM bot_users WHERE id=${userId}
      `).rows[0]);
    assert.ok(new Date(afterActivity.at).getTime() > new Date(beforeActivity).getTime());
    assert.equal(String(afterActivity.updateId), "9000001");
    await registration.recordUserActivity(telegramId, "9000001");
    const afterReplay = await database.withConnection(async (client) =>
      (await client.queryObject<{ at: Date }>`SELECT last_user_activity_at AS at FROM bot_users WHERE id=${userId}`).rows[0].at);
    assert.equal(new Date(afterReplay).getTime(), new Date(afterActivity.at).getTime());
    assert.equal(await reminders.eligibleAt(telegramId, new Date(Date.now() + 60 * 60 * 1000)), false);

    assert.deepEqual(await reminders.setEnabled(telegramId, false), { language: "en", enabled: false });
    assert.equal(await reminders.eligibleAt(telegramId, new Date(Date.now() + 24 * 60 * 60 * 1000)), false);
    assert.deepEqual(await reminders.setEnabled(telegramId, true), { language: "en", enabled: true });
  } finally {
    if (originalReminderSettings) await database.withConnection(async (client) => {
      await client.queryArray`UPDATE app_settings SET study_reminders_globally_enabled=${originalReminderSettings!.enabled},
        study_reminders_test_telegram_user_id=${originalReminderSettings!.testTelegramId} WHERE id=1`;
    });
    if (userId) await database.transaction(async (client) => {
      if (attemptId) await client.queryArray`DELETE FROM mock_attempts WHERE id=${attemptId}`;
      await client.queryArray`DELETE FROM access_entitlements WHERE user_id=${userId}`;
      await client.queryArray`DELETE FROM bot_users WHERE id=${userId}`;
    });
    if (examId) await database.transaction(async (client) => {
      await client.queryArray`DELETE FROM exam_types WHERE id=${examId}`;
    });
    await database.close();
    await secondDatabase.close();
  }
});
