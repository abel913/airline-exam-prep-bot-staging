import assert from "node:assert/strict";
import { PostgresDatabase } from "../postgres-database.ts";
import { MockUnitOfWork, PostgresMockStore } from "../mock-store.ts";
import { MockError, MockService } from "../mock-service.mjs";
import { PostgresRegistrationStore } from "../postgres-store.ts";

// This integration test refuses every hosted database and only writes to an
// allowlisted disposable local Phase 5 PostgreSQL database.
const databaseUrl = Deno.env.get("EDGE_TEST_DATABASE_URL") ?? "";
const allowedDatabases = new Set([
  "airline_exam_bot_phase5_test",
  "airline_exam_bot_phase5_v20_fresh_test",
  "airline_exam_bot_phase5_v20_final_test",
  "airline_exam_bot_phase5_v20_verify_test",
]);
const parsed = databaseUrl ? new URL(databaseUrl) : null;
if (!parsed || !["postgres:", "postgresql:"].includes(parsed.protocol)
  || !["127.0.0.1", "localhost"].includes(parsed.hostname)
  || parsed.port !== "5432"
  || !allowedDatabases.has(parsed.pathname.slice(1))) {
  throw new Error("EDGE_TEST_DATABASE_URL must target an allowlisted 127.0.0.1:5432 Phase 5 test database");
}

const randomText = () => crypto.randomUUID().replaceAll("-", "");
const fakeHash = () => randomText().repeat(2);

Deno.test("PostgreSQL: mock auto-next, server timer, expiry/restart and user+exam question uniqueness", async () => {
  const databases = [new PostgresDatabase(() => databaseUrl), new PostgresDatabase(() => databaseUrl)];
  const [database, secondDatabase] = databases;
  const mocks = [new MockService(new PostgresMockStore(database)), new MockService(new PostgresMockStore(secondDatabase))];
  const prefix = `phase5mock-${randomText().slice(0, 10)}`;
  let examA = "", examB = "", categoryA = "", categoryB = "", userA = "", userOther = "";
  let telegramA = "", telegramOther = "", hashA = "";
  const questionIds: string[] = [];
  let originalDuration: number | null = null;
  let hasOriginalDuration = false;

  const addVersion = async (questionId: string, examId: string, categoryId: string, versionNumber: number, text: string) => {
    return await database.transaction(async (c) => {
      const version = await c.queryObject<{ id: bigint }>`INSERT INTO question_versions
        (question_id,version_number,exam_type_id,category_id,exam_name,category_name,question_text,explanation,difficulty,
         source_type,source_title,source_reference,source_notes,use_status,free_pool,premium_pool,mock_pool,
         fingerprint,stem_fingerprint,created_by,created_at,updated_at)
        VALUES(${questionId},${versionNumber},${examId},${categoryId},'Synthetic exam','Synthetic mock category',${text},
          'Synthetic explanation','EASY','Original','Synthetic Phase 5 mock fixture','','','ORIGINAL',TRUE,FALSE,TRUE,
          ${randomText()},${randomText()},'phase5-mock-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      const id = String(version.rows[0].id);
      await c.queryArray`INSERT INTO question_options(version_id,position,option_text,correct)
        VALUES(${id},0,'Synthetic correct',TRUE),(${id},1,'Synthetic incorrect',FALSE)`;
      await c.queryArray`UPDATE questions SET current_version_id=${id},status='PUBLISHED' WHERE id=${questionId}`;
      return id;
    });
  };

  const addQuestion = async (examId: string, categoryId: string, text: string) => {
    const id = await database.transaction(async (c) => String((await c.queryObject<{ id: bigint }>`INSERT INTO questions
      (status,created_by,updated_by,created_at,updated_at)
      VALUES('DRAFT','phase5-mock-test','phase5-mock-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`).rows[0].id));
    questionIds.push(id);
    const versionId = await addVersion(id, examId, categoryId, 1, text);
    return { id, versionId };
  };

  const used = async (userId: string, examId: string) => await database.withConnection(async (c) => Number((await c.queryObject<{ used: number }>`
    SELECT mocks_used AS used FROM access_entitlements WHERE user_id=${userId} AND exam_type_id=${examId}`).rows[0].used));
  const frozen = async (attemptId: string) => await database.withConnection(async (c) => (await c.queryObject<{
    question_id: bigint; version_id: bigint; sequence_number: number;
  }>`SELECT question_id,version_id,sequence_number FROM mock_items WHERE attempt_id=${attemptId} ORDER BY sequence_number`).rows
    .map((item) => ({ questionId: String(item.question_id), versionId: String(item.version_id), sequence: Number(item.sequence_number) })));
  const attemptSnapshot = async (attemptId: string) => await database.withConnection(async (c) => {
    const attempt = (await c.queryObject<{ status: string; cursor: number; started: Date; deadline: Date }>`
      SELECT status,cursor_position AS cursor,started_at AS started,deadline_at AS deadline FROM mock_attempts WHERE id=${attemptId}`).rows[0];
    const items = (await c.queryObject<{ sequence_number: number; question_id: bigint; version_id: bigint; selected_option: number | null; answer_revision: number | null }>`
      SELECT sequence_number,question_id,version_id,selected_option,answer_revision FROM mock_items WHERE attempt_id=${attemptId} ORDER BY sequence_number`).rows;
    return { status: attempt.status, cursor: Number(attempt.cursor), started: attempt.started.toISOString(), deadline: attempt.deadline.toISOString(),
      items: items.map((item) => ({ sequence: Number(item.sequence_number), questionId: String(item.question_id), versionId: String(item.version_id),
        selected: item.selected_option, revision: item.answer_revision })) };
  });
  const expireInDatabase = async (attemptId: string) => await database.transaction(async (c) => {
    await c.queryArray`UPDATE mock_attempts SET deadline_at=clock_timestamp()-INTERVAL '1 second' WHERE id=${attemptId}`;
  });

  try {
    const latest = await database.withConnection(async (c) => (await c.queryObject<{ version: string }>`
      SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank DESC LIMIT 1`).rows[0]?.version);
    assert.equal(latest, "20", "isolated database must be at V20");
    originalDuration = await database.withConnection(async (c) => (await c.queryObject<{ duration: number | null }>`
      SELECT mock_duration_minutes AS duration FROM app_settings WHERE id=1`).rows[0].duration);
    hasOriginalDuration = true;

    await database.transaction(async (c) => {
      await c.queryArray`UPDATE app_settings SET mock_duration_minutes=120 WHERE id=1`;
      examA = String((await c.queryObject<{ id: bigint }>`INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${prefix},'Synthetic Phase 5 Mock Exam A','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`).rows[0].id);
      examB = String((await c.queryObject<{ id: bigint }>`INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${prefix+'-b'},'Synthetic Phase 5 Mock Exam B','',TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`).rows[0].id);
      categoryA = String((await c.queryObject<{ id: bigint }>`INSERT INTO categories(exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examA},'phase5-mock-a','Synthetic Phase 5 Mock A Category','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`).rows[0].id);
      categoryB = String((await c.queryObject<{ id: bigint }>`INSERT INTO categories(exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examB},'phase5-mock-b','Synthetic Phase 5 Mock B Category','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`).rows[0].id);
      const createUser = async (examId: string) => {
        const telegramId = String(BigInt(Date.now()) * 100000n + BigInt(Math.floor(Math.random() * 99999)));
        const hash = fakeHash();
        const id = String((await c.queryObject<{ id: bigint }>`INSERT INTO bot_users
          (telegram_user_id,preferred_language,selected_exam_type_id,registration_status,phone_identity_hash,
           registration_completed_at,created_at,updated_at)
          VALUES(${telegramId},'en',${examId},'COMPLETED',${hash},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`).rows[0].id);
        await c.queryArray`INSERT INTO access_entitlements
          (user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,
           practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
          VALUES(${id},${examId},${hash},'FREE',10,20,3,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`;
        return { id, telegramId, hash };
      };
      const first = await createUser(examA), second = await createUser(examA);
      userA = first.id; telegramA = first.telegramId; hashA = first.hash;
      userOther = second.id; telegramOther = second.telegramId;
    });

    for (let i = 0; i < 13; i++) await addQuestion(examA, categoryA, `Synthetic Exam A question ${i + 1}`);
    const premiumOnly = await addQuestion(examA, categoryA, "Synthetic premium-only question");
    await database.transaction(async (c) => {
      await c.queryArray`UPDATE question_versions SET free_pool=FALSE,premium_pool=TRUE WHERE id=${premiumOnly.versionId}`;
    });
    const freeStudent = await database.withConnection(async (c) => new MockUnitOfWork(c).student(telegramA));
    assert.ok(freeStudent);
    assert.equal(await database.withConnection(async (c) => new MockUnitOfWork(c).eligibleCount(freeStudent!)), 13,
      "FREE mocks exclude premium-only questions while retaining mock_pool eligibility");

    const first = await mocks[0].prepare(telegramA, "phase5-mock-first");
    const firstId = first.attempt.id;
    assert.equal(first.attempt.duration, 120);
    assert.equal(await used(userA, examA), 0, "preparation consumes no free mock allowance");
    const firstFrozen = await frozen(firstId);
    const firstQuestionIds = firstFrozen.map((item) => item.questionId);
    assert.equal(firstFrozen.length, 3);
    assert.equal(new Set(firstQuestionIds).size, 3, "mock_items use unique logical question_id values");

    const opened = await mocks[0].open(telegramA, firstId, 0);
    assert.ok(opened.secondsRemaining !== null && opened.secondsRemaining! <= 7200 && opened.secondsRemaining! >= 7190,
      "fresh mock shows approximately 02:00:00 remaining");
    const timer = await database.withConnection(async (c) => (await c.queryObject<{ seconds: number; deadline: Date; started: Date }>`
      SELECT EXTRACT(EPOCH FROM(deadline_at-started_at))::integer AS seconds,deadline_at AS deadline,started_at AS started
      FROM mock_attempts WHERE id=${firstId}`).rows[0]);
    assert.equal(timer.seconds, 7200);
    await database.transaction(async (c) => {
      await c.queryArray`UPDATE mock_attempts SET deadline_at=deadline_at-INTERVAL '1 minute' WHERE id=${firstId}`;
    });
    const remainingAfterDelay = await database.withConnection(async (c) => Number((await c.queryObject<{ seconds: number }>`
      SELECT GREATEST(0,FLOOR(EXTRACT(EPOCH FROM(deadline_at-clock_timestamp())))::integer) AS seconds
      FROM mock_attempts WHERE id=${firstId}`).rows[0].seconds));
    assert.ok(remainingAfterDelay < opened.secondsRemaining! - 50 && remainingAfterDelay > opened.secondsRemaining! - 70,
      "countdown follows server time without wall-clock sleeps");
    await database.transaction(async (c) => {
      await c.queryArray`UPDATE mock_attempts SET deadline_at=${timer.deadline} WHERE id=${firstId}`;
    });
    const q1 = await Promise.all([
      mocks[0].answer(telegramA, firstId, 0, 0, opened.item.revision),
      mocks[1].answer(telegramA, firstId, 0, 0, opened.item.revision),
    ]);
    assert.equal(q1.filter((view) => view.item?.sequence === 1).length, 1, "one accepted callback returns question 2 once");
    assert.equal(q1.filter((view) => view.duplicate === true).length, 1, "the duplicate callback is acknowledged without rendering another question");
    assert.equal(await used(userA, examA), 1, "the first accepted answer consumes exactly one free attempt");
    const afterQ1 = await database.withConnection(async (c) => (await c.queryObject<{ cursor: number; answered: bigint }>`
      SELECT a.cursor_position AS cursor,(SELECT COUNT(*) FROM mock_items i WHERE i.attempt_id=a.id AND i.selected_option IS NOT NULL) AS answered
      FROM mock_attempts a WHERE a.id=${firstId}`).rows[0]);
    assert.equal(afterQ1.cursor, 1);
    assert.equal(Number(afterQ1.answered), 1, "Telegram callback replay cannot save a duplicate answer");
    const reviewQ1 = await mocks[0].open(telegramA, firstId, 0);
    const changedQ1 = await mocks[0].answer(telegramA, firstId, 0, 1, reviewQ1.item.revision);
    assert.equal(changedQ1.item.sequence, 1, "changing a previous answer returns to the persisted next question");
    assert.equal(await used(userA, examA), 1, "changing an answer does not charge another mock attempt");
    const beforeLanguageChange = await attemptSnapshot(firstId);

    const sharedQuestionId = firstQuestionIds[0];
    await addVersion(sharedQuestionId, examA, categoryA, 2, "Synthetic newer same-exam logical question version");
    const examBVersion = await addVersion(sharedQuestionId, examB, categoryB, 3, "Synthetic shared logical question in exam B");
    for (let i = 0; i < 3; i++) await addQuestion(examB, categoryB, `Synthetic Exam B question ${i + 1}`);
    const language = new PostgresRegistrationStore(database, () => "unused-synthetic-test-key");
    await language.language(telegramA, "am");
    const frozenReview = await mocks[0].open(telegramA, firstId, 0, true);
    assert.equal(String(frozenReview.question.versionId), firstFrozen[0].versionId, "question content remains pinned to its frozen version");
    const resumed = await mocks[0].open(telegramA, firstId, 1);
    assert.equal(resumed.student.language, "am");
    assert.equal(new Date(resumed.attempt.deadline!).getTime(), new Date(opened.attempt.deadline!).getTime(), "language change and resume do not reset expiry");
    assert.ok(examBVersion);
    assert.deepEqual(await attemptSnapshot(firstId), beforeLanguageChange,
      "language changes, review, and resume do not mutate the frozen attempt or answers");

    const nextAnswer = await mocks[0].answer(telegramA, firstId, 1, 0, resumed.item.revision);
    assert.equal(nextAnswer.item.sequence, 2, "answering question 2 automatically advances to question 3");
    assert.equal(await used(userA, examA), 1, "later answers do not consume additional attempts");
    const lastQuestion = await mocks[0].open(telegramA, firstId, 2);
    const lastAnswer = await mocks[0].answer(telegramA, firstId, 2, 0, lastQuestion.item.revision);
    assert.equal(lastAnswer.completion, true, "last answer opens completion instead of re-rendering a question");
    assert.deepEqual(lastAnswer.counts, { answered: 3, unanswered: 0 });
    assert.equal(lastAnswer.attempt.status, "IN_PROGRESS", "completion does not auto-submit");
    assert.equal(new Date(lastAnswer.attempt.deadline!).getTime(), new Date(opened.attempt.deadline!).getTime());
    await language.language(telegramA, "en");
    const submitted = await mocks[0].submit(telegramA, firstId);
    assert.equal(submitted.attempt.status, "SUBMITTED");
    assert.equal((await mocks[1].submit(telegramA, firstId)).attempt.status, "SUBMITTED", "submission replay is idempotent");
    assert.equal(await used(userA, examA), 1);

    const second = await mocks[0].prepare(telegramA, "phase5-mock-second");
    const secondIds = (await frozen(second.attempt.id)).map((item) => item.questionId);
    assert.equal(firstQuestionIds.some((id) => secondIds.includes(id)), false, "same user and exam cannot receive an already frozen logical question");
    const concurrentPrepare = await Promise.all([
      mocks[0].prepare(telegramA, "phase5-mock-concurrent-a"),
      mocks[1].prepare(telegramA, "phase5-mock-concurrent-b"),
    ]);
    assert.equal(concurrentPrepare[0].attempt.id, concurrentPrepare[1].attempt.id);
    assert.equal((await frozen(second.attempt.id)).length, 3, "concurrent preparation creates no duplicate attempt items");
    await mocks[0].open(telegramA, second.attempt.id, 0);
    await expireInDatabase(second.attempt.id);
    const zeroExpired = await mocks[0].answer(telegramA, second.attempt.id, 0, 0, 0);
    assert.equal(zeroExpired.attempt.status, "EXPIRED");
    assert.equal(await used(userA, examA), 1, "zero-answer expiry does not consume or refund allowance");
    assert.equal((await mocks[0].submit(telegramA, second.attempt.id)).attempt.status, "EXPIRED");

    const third = await mocks[0].prepare(telegramA, "phase5-mock-restart");
    assert.notEqual(third.attempt.id, second.attempt.id, "restart creates a new attempt");
    const thirdIds = (await frozen(third.attempt.id)).map((item) => item.questionId);
    assert.equal([...firstQuestionIds, ...secondIds].some((id) => thirdIds.includes(id)), false, "restart excludes every previously frozen question");
    assert.equal(secondIds.includes(sharedQuestionId), false, "adding a newer version in the same exam cannot bypass seen-question exclusion");
    const thirdOpened = await mocks[0].open(telegramA, third.attempt.id, 0);
    const thirdFirstAnswer = await mocks[0].answer(telegramA, third.attempt.id, 0, 0, thirdOpened.item.revision);
    assert.equal(thirdFirstAnswer.item.sequence, 1);
    assert.equal(await used(userA, examA), 2, "answering the second mock consumes exactly one additional attempt");
    await expireInDatabase(third.attempt.id);
    const thirdExpiry = await mocks[0].answer(telegramA, third.attempt.id, 1, 0, 0);
    assert.equal(thirdExpiry.attempt.status, "EXPIRED", "an answered attempt expires at its deadline");
    const expiredSnapshot = await attemptSnapshot(third.attempt.id);
    assert.equal((await mocks[0].open(telegramA, third.attempt.id, 2)).attempt.status, "EXPIRED", "expired Next navigation is rejected");
    assert.equal((await mocks[0].open(telegramA, third.attempt.id, 0)).attempt.status, "EXPIRED", "expired Previous navigation is rejected");
    assert.equal((await mocks[0].submit(telegramA, third.attempt.id)).attempt.status, "EXPIRED", "expired submission is rejected");
    assert.deepEqual(await attemptSnapshot(third.attempt.id), expiredSnapshot, "expired actions do not mutate answers or cursor");
    assert.equal(await used(userA, examA), 2, "answered expiry remains charged exactly once");
    const fourth = await mocks[0].prepare(telegramA, "phase5-mock-after-expiry");
    assert.notEqual(fourth.attempt.id, third.attempt.id, "restart after expiry creates a new attempt");
    const fourthIds = (await frozen(fourth.attempt.id)).map((item) => item.questionId);
    assert.equal([...firstQuestionIds, ...secondIds, ...thirdIds].some((id) => fourthIds.includes(id)), false,
      "an abandoned READY mock also excludes every previously frozen question");
    assert.equal(await used(userA, examA), 2, "preparing an abandoned mock does not consume an attempt");
    await expireInDatabase(fourth.attempt.id);
    assert.equal((await mocks[0].open(telegramA, fourth.attempt.id, 0)).attempt.status, "EXPIRED",
      "an unanswered abandoned mock can expire without removing its frozen questions from history");
    assert.equal(await used(userA, examA), 2, "zero-answer expiry remains free");
    const beforeShortage = await database.withConnection(async (c) => Number((await c.queryObject<{ n: bigint }>`SELECT COUNT(*) AS n FROM mock_attempts WHERE user_id=${userA}`).rows[0].n));
    await assert.rejects(mocks[0].prepare(telegramA, "phase5-mock-shortage"), (e) => e instanceof MockError
      && e.key === "mock.insufficient" && e.args[0] === 1 && e.args[1] === 3);
    const afterShortage = await database.withConnection(async (c) => Number((await c.queryObject<{ n: bigint }>`SELECT COUNT(*) AS n FROM mock_attempts WHERE user_id=${userA}`).rows[0].n));
    assert.equal(afterShortage, beforeShortage, "insufficient pool creates no partial attempt");
    assert.equal(await database.withConnection(async (c) => Number((await c.queryObject<{ n: bigint }>`
      SELECT COUNT(*) AS n FROM mock_attempts WHERE id IN (${second.attempt.id},${third.attempt.id},${fourth.attempt.id}) AND status='EXPIRED'`).rows[0].n)), 3,
    "expired attempts remain in mock history");

    await database.transaction(async (c) => {
      await c.queryArray`INSERT INTO access_entitlements
        (user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,
         practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
        VALUES(${userA},${examB},${hashA},'FREE',10,20,4,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`;
      await c.queryArray`UPDATE bot_users SET selected_exam_type_id=${examB} WHERE id=${userA}`;
    });
    const otherExam = await mocks[0].prepare(telegramA, "phase5-mock-other-exam");
    const otherExamItems = await frozen(otherExam.attempt.id);
    assert.equal(otherExamItems.length, 4);
    assert.ok(otherExamItems.some((item) => item.questionId === sharedQuestionId), "same logical question history in exam A does not exclude it from exam B");
    assert.ok(otherExamItems.every((item) => [...questionIds].includes(item.questionId)), "exam B selection is limited to its eligible questions");
    const versionsBelongToExamB = await database.withConnection(async (c) => Number((await c.queryObject<{ n: bigint }>`
      SELECT COUNT(*) AS n FROM mock_items i JOIN question_versions v ON v.id=i.version_id
      WHERE i.attempt_id=${otherExam.attempt.id} AND v.exam_type_id=${examB}`).rows[0].n));
    assert.equal(versionsBelongToExamB, 4);

    const otherStudent = await database.withConnection(async (c) => new MockUnitOfWork(c).student(telegramOther));
    assert.ok(otherStudent);
    assert.equal(await database.withConnection(async (c) => new MockUnitOfWork(c).eligibleCount(otherStudent!)), 13,
      "another user retains the full exam A question pool despite this user's frozen history");
    const otherUserMock = await mocks[0].prepare(telegramOther, "phase5-mock-other-user");
    const otherUserItems = await frozen(otherUserMock.attempt.id);
    assert.equal(otherUserItems.length, 3);
    assert.ok(otherUserItems.some((item) => [...firstQuestionIds, ...secondIds, ...thirdIds, ...fourthIds].includes(item.questionId)),
      "a different user has independent history and may receive questions frozen by this user");
  } finally {
    try {
      await database.transaction(async (c) => {
        if (userA || userOther) {
          await c.queryArray`DELETE FROM mock_items WHERE attempt_id IN (SELECT id FROM mock_attempts WHERE user_id IN (${userA || "0"},${userOther || "0"}))`;
          await c.queryArray`DELETE FROM mock_attempts WHERE user_id IN (${userA || "0"},${userOther || "0"})`;
          await c.queryArray`DELETE FROM access_entitlements WHERE user_id IN (${userA || "0"},${userOther || "0"})`;
          await c.queryArray`DELETE FROM bot_users WHERE id IN (${userA || "0"},${userOther || "0"})`;
        }
        if (questionIds.length) {
          await c.queryArray`UPDATE questions SET current_version_id=NULL,status='DRAFT' WHERE id=ANY(${questionIds.map(Number)})`;
          await c.queryArray`DELETE FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id=ANY(${questionIds.map(Number)}))`;
          await c.queryArray`DELETE FROM question_versions WHERE question_id=ANY(${questionIds.map(Number)})`;
          await c.queryArray`DELETE FROM questions WHERE id=ANY(${questionIds.map(Number)})`;
        }
        if (examA || examB) await c.queryArray`DELETE FROM categories WHERE exam_type_id IN (${examA || "0"},${examB || "0"})`;
        if (examA || examB) await c.queryArray`DELETE FROM exam_types WHERE id IN (${examA || "0"},${examB || "0"})`;
        if (hasOriginalDuration) {
          await c.queryArray`UPDATE app_settings SET mock_duration_minutes=${originalDuration} WHERE id=1`;
        }
      });
    } finally { for (const db of databases) await db.close(); }
  }
});
