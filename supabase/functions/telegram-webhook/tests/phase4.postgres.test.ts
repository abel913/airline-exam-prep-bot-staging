import assert from "node:assert/strict";
import { PostgresDatabase } from "../postgres-database.ts";
import { PostgresMockStore } from "../mock-store.ts";
import { MockService } from "../mock-service.mjs";
import { PostgresPaymentStore } from "../payment-store.ts";
import { PaymentError, PaymentService } from "../payment-service.mjs";
import { createPaymentFlow } from "../payment-flow.mjs";

// Intentionally accepts only a disposable local database. Never run these
// fixture mutations against a hosted staging or production database.
const databaseUrl = Deno.env.get("EDGE_TEST_DATABASE_URL") ?? "";
const PHASE5_SCHEMA_VERSION = "20";
const allowedDatabases = new Set(["airline_exam_bot_phase5_test", "airline_exam_bot_phase5_v20_fresh_test", "airline_exam_bot_phase5_v20_final_test", "airline_exam_bot_phase5_v20_verify_test"]);
const parsed = databaseUrl ? new URL(databaseUrl) : null;
if (!parsed || !["postgres:", "postgresql:"].includes(parsed.protocol)
  || !["127.0.0.1", "localhost"].includes(parsed.hostname)
  || parsed.port !== "5432"
  || !allowedDatabases.has(parsed.pathname.slice(1))) {
  throw new Error("EDGE_TEST_DATABASE_URL must target an allowlisted 127.0.0.1:5432 Phase 5 test database");
}

let nextTelegramId = BigInt(Date.now()) * 1000n;
const tg = () => String(nextTelegramId++);
const fakeHash = () => crypto.randomUUID().replaceAll("-", "").repeat(2);
Deno.test("Phase 4 PostgreSQL: mocks and payments preserve transactional state", async () => {
  const dbs = [new PostgresDatabase(() => databaseUrl), new PostgresDatabase(() => databaseUrl)];
  const [database, secondDb] = dbs;
  const mock = new MockService(new PostgresMockStore(database));
  const mockSecond = new MockService(new PostgresMockStore(secondDb));
  const payment = new PaymentService(new PostgresPaymentStore(database), { adminId: "987654321" });
  const prefix = `phase4-${crypto.randomUUID().slice(0, 8)}`;
  const examCode = prefix;
  const originalSettings = await database.withConnection(async (c) => (await c.queryObject<{
    payment_enabled: boolean; manual_payment_enabled: boolean; mock_duration_minutes: number | null;
  }>`SELECT payment_enabled,manual_payment_enabled,mock_duration_minutes FROM app_settings WHERE id=1`).rows[0]);
  let examId = "", userId = "", otherId = "", methodId = "", telebirrMethodId = "", inactiveMethodId = "";
  const questionIds: string[] = [];
  const telegramIds: string[] = [];

  try {
    const migration = await database.withConnection(async (c) => (await c.queryObject<{ version: string; success: boolean }>`
      SELECT version,success FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank DESC LIMIT 1`).rows[0]);
    assert.equal(migration?.version, PHASE5_SCHEMA_VERSION, "local disposable database must be migrated through the Phase 5 schema");
    const requiredJavaMigrations = await database.withConnection(async (c) => (await c.queryObject<{ count: bigint }>`
      SELECT COUNT(*) AS count FROM flyway_schema_history
      WHERE success=TRUE AND version IN ('16','17','18','19')`).rows[0].count);
    assert.equal(Number(requiredJavaMigrations), 4, "V16/V17/V18/V19 Java migrations must all be applied before SQL V20");
    await database.healthCheck();
    // Keep each fixture statement tagged so the driver performs parameterization.
    await database.transaction(async (c) => {
      const exam = await c.queryObject<{ id: bigint }>`INSERT INTO exam_types
        (code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examCode},'Synthetic Phase 4 Test Exam','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      examId = String(exam.rows[0].id);
      const category1 = await c.queryObject<{ id: bigint }>`INSERT INTO categories
        (exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examId},'mock-a','Synthetic Mock Category A','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      const category2 = await c.queryObject<{ id: bigint }>`INSERT INTO categories
        (exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examId},'mock-b','Synthetic Mock Category B','',TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      const createUser = async () => {
        const hash = fakeHash();
        const telegramId = tg();
        telegramIds.push(telegramId);
        const row = await c.queryObject<{ id: bigint }>`INSERT INTO bot_users
          (telegram_user_id,preferred_language,selected_exam_type_id,registration_status,phone_identity_hash,
           registration_completed_at,created_at,updated_at)
          VALUES(${telegramId},'en',${examId},'COMPLETED',${hash},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
        const id = String(row.rows[0].id);
        await c.queryArray`INSERT INTO access_entitlements
          (user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,practice_used,mocks_used,
           grant_source,granted_at,created_at,updated_at)
          VALUES(${id},${examId},${hash},'FREE',10,2,3,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`;
        return id;
      };
      userId = await createUser();
      otherId = await createUser();
      for (let i = 0; i < 4; i++) {
        const q = await c.queryObject<{ id: bigint }>`INSERT INTO questions(status,created_by,updated_by,created_at,updated_at)
          VALUES('DRAFT','phase4-test','phase4-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
        const questionId = String(q.rows[0].id);
        questionIds.push(questionId);
        const category = i % 2 === 0 ? String(category1.rows[0].id) : String(category2.rows[0].id);
        const v = await c.queryObject<{ id: bigint }>`INSERT INTO question_versions
          (question_id,version_number,exam_type_id,category_id,exam_name,category_name,question_text,explanation,difficulty,
           source_type,source_title,source_reference,source_notes,use_status,free_pool,premium_pool,mock_pool,
           fingerprint,stem_fingerprint,created_by,created_at,updated_at)
          VALUES(${questionId},1,${examId},${category},'Synthetic Phase 4 Test Exam',${i % 2 === 0 ? 'Synthetic Mock Category A' : 'Synthetic Mock Category B'},
            ${`Synthetic question ${i+1}`},'Synthetic explanation','EASY','Original','Synthetic Phase 4 test','','','ORIGINAL',
            TRUE,${i===3},TRUE,${fakeHash()},${fakeHash()},'phase4-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
        await c.queryArray`INSERT INTO question_options(version_id,position,option_text,correct)
          VALUES(${String(v.rows[0].id)},0,'Synthetic correct',TRUE),(${String(v.rows[0].id)},1,'Synthetic incorrect',FALSE)`;
        await c.queryArray`UPDATE questions SET current_version_id=${String(v.rows[0].id)},status='PUBLISHED' WHERE id=${questionId}`;
      }
      await c.queryArray`UPDATE app_settings SET mock_duration_minutes=1,payment_enabled=FALSE,manual_payment_enabled=FALSE WHERE id=1`;
      const method = await c.queryObject<{ id: bigint }>`INSERT INTO payment_methods
        (type,display_name,account_name,destination,instructions,active,display_order,revision,created_at,updated_at)
        VALUES('BANK_TRANSFER','Synthetic CBE','TEST ONLY','NO REAL DESTINATION','Synthetic test instructions',TRUE,1,0,
          CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      methodId = String(method.rows[0].id);
      const telebirr = await c.queryObject<{ id: bigint }>`INSERT INTO payment_methods
        (type,display_name,account_name,destination,instructions,active,display_order,revision,created_at,updated_at)
        VALUES('TELEBIRR','Synthetic Telebirr','TEST ONLY','NO REAL DESTINATION','Synthetic test instructions',TRUE,0,0,
          CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      telebirrMethodId = String(telebirr.rows[0].id);
      const inactive = await c.queryObject<{ id: bigint }>`INSERT INTO payment_methods
        (type,display_name,account_name,destination,instructions,active,display_order,revision,created_at,updated_at)
        VALUES('BANK_TRANSFER','Synthetic Inactive','TEST ONLY','NO REAL DESTINATION','Synthetic test instructions',FALSE,2,0,
          CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      inactiveMethodId = String(inactive.rows[0].id);
    });
    testTelegramIds.set(userId, telegramIds[0]);
    testTelegramIds.set(otherId, telegramIds[1]);

    const mockCount = async () => await database.withConnection(async (c) => Number((await c.queryObject<{ n: bigint }>`
      SELECT mocks_used n FROM access_entitlements WHERE user_id=${userId}`).rows[0].n));
    const ready = await mock.prepare(tgLookup(userId), crypto.randomUUID());
    const attemptId = ready.attempt.id;
    assert.equal(ready.attempt.status, "READY");
    assert.equal(await mockCount(), 0, "creation must not consume an attempt");
    const frozen = await database.withConnection(async (c) => (await c.queryObject<{ version_id: bigint }>`
      SELECT version_id FROM mock_items WHERE attempt_id=${attemptId} ORDER BY sequence_number`).rows.map((r) => String(r.version_id)));
    assert.equal(frozen.length, 3);
    assert.equal(new Set(frozen).size, 3);
    const opened = await mock.open(tgLookup(userId), attemptId, 0);
    assert.equal(opened.secondsRemaining !== null, true);
    assert.equal(await mockCount(), 0, "opening must not consume an attempt");
    const revision = opened.item.revision;
    await Promise.all([
      mock.answer(tgLookup(userId), attemptId, 0, 0, revision),
      mockSecond.answer(tgLookup(userId), attemptId, 0, 0, revision),
    ]);
    assert.equal(await mockCount(), 1, "concurrent duplicate first answers charge once");
    const active = (await mock.intro(tgLookup(userId))).active!;
    assert.equal(active.id, attemptId, "resume returns same attempt");
    const resumedVersions = await database.withConnection(async (c) => (await c.queryObject<{ version_id: bigint }>`
      SELECT version_id FROM mock_items WHERE attempt_id=${active.id} ORDER BY sequence_number`).rows.map((r) => String(r.version_id)));
    assert.deepEqual(resumedVersions, frozen, "resume preserves frozen question ordering");
    await mock.answer(tgLookup(userId), attemptId, 1, 1, 0);
    assert.equal(await mockCount(), 1, "later answers do not charge again");
    await database.transaction(async (c) => {
      await c.queryArray`UPDATE questions SET status='ARCHIVED' WHERE id=${questionIds[0]}`;
    });
    const result = await mock.submit(tgLookup(userId), attemptId);
    assert.equal(result.attempt.status, "SUBMITTED");
    assert.equal(result.score.total, 3);
    assert.equal(result.score.correct + result.score.incorrect + result.score.unanswered, 3);
    const historical = await mock.open(tgLookup(userId), attemptId, 0, true);
    assert.equal(historical.question.versionId, frozen[0], "review preserves exact frozen version after archive");
    assert.equal((await mock.history(tgLookup(userId))).rows.length, 1);
    await assert.rejects(mock.open(tgLookup(otherId), attemptId, 0), /student.invalid/);

    // Force expiry using database time instead of sleeping through the full timer.
    const zeroAnswer = await mock.prepare(tgLookup(userId), crypto.randomUUID());
    await mock.open(tgLookup(userId), zeroAnswer.attempt.id, 0);
    await database.transaction(async (c) => { await c.queryArray`UPDATE mock_attempts SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=${zeroAnswer.attempt.id}`; });
    const expired = await mock.answer(tgLookup(userId), zeroAnswer.attempt.id, 0, 0, 0);
    assert.equal(expired.attempt.status, "EXPIRED");
    assert.equal(await mockCount(), 1, "zero-answer expiry must not consume another attempt");
    const answeredThenExpired = await mock.prepare(tgLookup(userId), crypto.randomUUID());
    await mock.open(tgLookup(userId), answeredThenExpired.attempt.id, 0);
    await mock.answer(tgLookup(userId), answeredThenExpired.attempt.id, 0, 0, 0);
    await database.transaction(async (c) => { await c.queryArray`UPDATE mock_attempts SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=${answeredThenExpired.attempt.id}`; });
    const late = await mock.answer(tgLookup(userId), answeredThenExpired.attempt.id, 1, 0, 0);
    assert.equal(late.attempt.status, "EXPIRED");
    assert.equal(await mockCount(), 2, "answered attempt remains charged once at expiry");
    await database.transaction(async (c) => { await c.queryArray`UPDATE access_entitlements SET access_level='LIFETIME' WHERE user_id=${userId}`; });
    const lifetimeMock = await mock.prepare(tgLookup(userId), crypto.randomUUID());
    await mock.open(tgLookup(userId), lifetimeMock.attempt.id, 0);
    await mock.answer(tgLookup(userId), lifetimeMock.attempt.id, 0, 0, 0);
    assert.equal(await mockCount(), 2, "lifetime answers do not consume free mock allowance");
    await database.transaction(async (c) => { await c.queryArray`UPDATE access_entitlements SET access_level='FREE' WHERE user_id=${userId}`; });
    await database.transaction(async (c) => { await c.queryArray`UPDATE mock_attempts SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=${lifetimeMock.attempt.id}`; });
    await mock.answer(tgLookup(userId), lifetimeMock.attempt.id, 1, 0, 0);

    await assert.rejects(payment.start(tgLookup(userId), examId, crypto.randomUUID()), (e) => e instanceof PaymentError && e.key === "payment.disabled");
    await database.transaction(async (c) => { await c.queryArray`UPDATE app_settings SET payment_enabled=TRUE,manual_payment_enabled=TRUE WHERE id=1`; });
    const pending = await payment.start(tgLookup(userId), examId, crypto.randomUUID());
    assert.deepEqual(pending.methods.map((m: { type: string; display_name: string }) => `${m.type}:${m.display_name}`),
      ["TELEBIRR:Synthetic Telebirr", "BANK_TRANSFER:Synthetic CBE"],
      "payment start must query active PostgreSQL boolean rows in display_order order and exclude inactive rows");
    const reqId = pending.request.id;
    await assert.rejects(payment.select(tgLookup(userId), reqId, "999999"), (e) => e instanceof PaymentError);
    await payment.select(tgLookup(userId), reqId, methodId);
    await payment.reference(tgLookup(userId), reqId, "PHASE4-TEST-001");
    const duplicateReferenceReplay = await Promise.all([
      payment.reference(tgLookup(userId), reqId, "PHASE4-TEST-001"),
      payment.reference(tgLookup(userId), reqId, "phase4-test-001"),
    ]);
    assert.deepEqual(duplicateReferenceReplay.map((v) => v.request.id), [reqId, reqId]);
    await assert.rejects(payment.reference(tgLookup(userId), reqId, "bad"), (e) => e instanceof PaymentError);
    const otherRequest = await payment.start(tgLookup(otherId), examId, crypto.randomUUID());
    await payment.select(tgLookup(otherId), otherRequest.request.id, methodId);
    await assert.rejects(payment.reference(tgLookup(otherId), otherRequest.request.id, "phase4-test-001"),
      (e) => e instanceof PaymentError && e.key === "payment.duplicateReference");
    const fullProof = "Dear Customer,\nA debit transaction of ETB 50.00 occurred.\nhttps://provider.example/receipt?id=synthetic";
    const proofMessages: string[] = [];
    const proofFlow = createPaymentFlow(payment, { sendMessage: async (_chat: string, text: string) => { proofMessages.push(text); } } as never);
    const proofUpdate = { text: fullProof };
    await proofFlow.message("synthetic-chat", tgLookup(otherId), proofUpdate, "phase4-proof-update-1");
    await proofFlow.message("synthetic-chat", tgLookup(otherId), proofUpdate, "phase4-proof-update-1");
    const proofRequest = await database.withConnection(async (c) => (await c.queryObject<{
      status: string; payment_proof_text: string; reference: string | null; normalized_reference: string | null;
      receipt_file_id: string | null; receipt_unique_id: string | null; target_exam_type_id: bigint;
    }>`SELECT status,payment_proof_text,reference,normalized_reference,receipt_file_id,receipt_unique_id,target_exam_type_id
      FROM payment_requests WHERE id=${otherRequest.request.id}`).rows[0]);
    assert.equal(proofRequest.status, "PENDING_REVIEW");
    assert.equal(proofRequest.payment_proof_text, fullProof);
    assert.equal(proofRequest.reference, null, "full messages are not parsed or truncated into the legacy reference field");
    assert.equal(proofRequest.normalized_reference, null);
    assert.equal(proofRequest.receipt_file_id, null);
    assert.equal(proofRequest.receipt_unique_id, null);
    assert.equal(String(proofRequest.target_exam_type_id), examId);
    assert.equal(proofMessages.some((text) => text.includes("provider.example")), false, "proof text is not echoed back to the student");
    const proofCounts = await database.withConnection(async (c) => (await c.queryObject<{ notifications: bigint; audits: bigint }>`
      SELECT (SELECT COUNT(*) FROM payment_notifications WHERE request_id=${otherRequest.request.id} AND kind='ADMIN_PENDING') notifications,
        (SELECT COUNT(*) FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id=${otherRequest.request.id}
          AND action='PAYMENT_SUBMITTED_FOR_REVIEW') audits`).rows[0]);
    assert.equal(Number(proofCounts.notifications), 1);
    assert.equal(Number(proofCounts.audits), 1, "replayed Telegram update does not duplicate review audit");
    await assert.rejects(database.transaction(async (c) => {
      await c.queryArray`UPDATE payment_requests SET status='PENDING_REVIEW',submitted_at=CURRENT_TIMESTAMP,
        payment_proof_text=NULL,receipt_file_id=NULL,receipt_unique_id=NULL WHERE id=${otherRequest.request.id}`;
    }), (e) => String(e).includes("payment_requests_reference_proof_check"));
    const receipt = { fileId: "synthetic-file-id", uniqueId: "synthetic-file-unique-id", type: "PHOTO", filename: null, mime: "image/jpeg", size: 1024 };
    const submitted = await payment.receipt(tgLookup(userId), reqId, receipt);
    assert.equal(submitted.request.status, "PENDING_REVIEW");
    const sent: string[] = [];
    const paymentFlow = createPaymentFlow(payment, { sendMessage: async (_chat: string, text: string) => { sent.push(text); } } as never);
    const duplicateUpdate = { photo: [{ file_id: "synthetic-file", file_unique_id: "synthetic-unique", width: 16, height: 16, file_size: 128 }] };
    await paymentFlow.message("synthetic-chat", tgLookup(userId), duplicateUpdate, "phase4-update-1");
    await paymentFlow.message("synthetic-chat", tgLookup(userId), duplicateUpdate, "phase4-update-1");
    // Replaying the same Telegram receipt update keeps one request and one admin notification.
    assert.equal((await payment.status(tgLookup(userId))).request.id, reqId);
    const outbox = await database.withConnection(async (c) => (await c.queryObject<{ status: string; count: bigint }>`
      SELECT status,COUNT(*) OVER() count FROM payment_notifications WHERE request_id=${reqId} AND kind='ADMIN_PENDING'`).rows[0]);
    assert.equal(outbox.status, "NEW");
    assert.equal(Number(outbox.count), 1, "receipt replay must not duplicate admin outbox entry");
    await assert.rejects(payment.select(tgLookup(otherId), reqId, methodId),
      (e) => e instanceof PaymentError && e.key === "payment.notFound");
    const grants = await database.withConnection(async (c) => Number((await c.queryObject<{ n: bigint }>`
      SELECT COUNT(*) n FROM lifetime_access_grants WHERE payment_request_id=${reqId}`).rows[0].n));
    assert.equal(grants, 0, "student payment flow cannot approve or grant lifetime access");
  } finally {
    try {
      await database.transaction(async (c) => {
        await c.queryArray`DELETE FROM payment_notifications WHERE request_id IN (SELECT id FROM payment_requests WHERE user_id IN (${userId || "0"},${otherId || "0"}))`;
        await c.queryArray`DELETE FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id IN
          (SELECT id FROM payment_requests WHERE user_id IN (${userId || "0"},${otherId || "0"}))`;
        await c.queryArray`DELETE FROM lifetime_access_grants WHERE user_id IN (${userId || "0"},${otherId || "0"})`;
        await c.queryArray`DELETE FROM payment_requests WHERE user_id IN (${userId || "0"},${otherId || "0"})`;
        await c.queryArray`DELETE FROM payment_methods WHERE id=${methodId || "0"}`;
        await c.queryArray`DELETE FROM payment_methods WHERE id=${telebirrMethodId || "0"}`;
        await c.queryArray`DELETE FROM payment_methods WHERE id=${inactiveMethodId || "0"}`;
        await c.queryArray`DELETE FROM mock_items WHERE attempt_id IN (SELECT id FROM mock_attempts WHERE user_id IN (${userId || "0"},${otherId || "0"}))`;
        await c.queryArray`DELETE FROM mock_attempts WHERE user_id IN (${userId || "0"},${otherId || "0"})`;
        await c.queryArray`DELETE FROM access_entitlements WHERE user_id IN (${userId || "0"},${otherId || "0"})`;
        await c.queryArray`DELETE FROM bot_users WHERE id IN (${userId || "0"},${otherId || "0"})`;
        for (const id of questionIds) {
          // The schema requires every non-DRAFT logical question to retain a current version.
          // Return this synthetic row to DRAFT atomically while breaking the version FK for cleanup.
          await c.queryArray`UPDATE questions SET current_version_id=NULL,status='DRAFT' WHERE id=${id}`;
          await c.queryArray`DELETE FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id=${id})`;
          await c.queryArray`DELETE FROM question_versions WHERE question_id=${id}`;
          await c.queryArray`DELETE FROM questions WHERE id=${id}`;
        }
        // question_versions reference categories, and categories reference the
        // exam type. Delete only this fixture's two categories after its question
        // versions are gone, then remove the fixture exam parent.
        if (examId) {
          await c.queryArray`DELETE FROM categories WHERE exam_type_id=${examId}
            AND ((code='mock-a' AND name='Synthetic Mock Category A')
              OR (code='mock-b' AND name='Synthetic Mock Category B'))`;
          await c.queryArray`DELETE FROM exam_types WHERE id=${examId} AND code=${examCode}
            AND name='Synthetic Phase 4 Test Exam'`;
        }
        if (originalSettings) await c.queryArray`UPDATE app_settings SET mock_duration_minutes=${originalSettings.mock_duration_minutes},
          payment_enabled=${originalSettings.payment_enabled},manual_payment_enabled=${originalSettings.manual_payment_enabled} WHERE id=1`;
      });
    } finally { for (const db of dbs) await db.close(); }
    testTelegramIds.delete(userId);
    testTelegramIds.delete(otherId);
  }
});

// The fixture writes synthetic test telegram IDs on setup and resolves each
// test identity by its internal row, never by an external or real account.
const testTelegramIds = new Map<string, string>();
function tgLookup(userId: string): string {
  const value = testTelegramIds.get(userId);
  if (!value) throw new Error("synthetic test identity missing");
  return value;
}
