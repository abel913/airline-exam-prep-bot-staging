import assert from "node:assert/strict";
import { PostgresDatabase, type QueryClient } from "../postgres-database.ts";
import { PostgresPracticeStore, PracticeUnitOfWork } from "../practice-store.ts";
import { PracticeService, PracticeError } from "../practice-service.mjs";

// This suite deliberately refuses staging/remote URLs. Flyway must first migrate this disposable DB.
const databaseUrl = Deno.env.get("EDGE_TEST_DATABASE_URL") ?? "";
const allowedDatabases = new Set(["airline_exam_bot_phase5_test", "airline_exam_bot_phase5_v20_fresh_test", "airline_exam_bot_phase5_v20_final_test", "airline_exam_bot_phase5_v21_test"]);
const parsed = new URL(databaseUrl);
if (!['127.0.0.1', 'localhost'].includes(parsed.hostname) || parsed.port !== '5432'
  || !allowedDatabases.has(parsed.pathname.slice(1))) {
  throw new Error("EDGE_TEST_DATABASE_URL must identify an allowlisted 127.0.0.1:5432 Phase 5 test database");
}
let sequence = BigInt(Date.now()) * 1000n;
const update = () => String(sequence++);
const fakeHash = () => crypto.randomUUID().replaceAll("-", "").repeat(2);
const invalid = (error: unknown) => error instanceof PracticeError && error.key === "student.invalid";
const limit = (error: unknown) => error instanceof PracticeError && error.key === "practice.limit";

function idQuery(client: QueryClient) {
  return async (strings: TemplateStringsArray, ...values: unknown[]) => {
    const result = await client.queryObject<{ id: bigint }>(strings, ...values);
    return String(result.rows[0].id);
  };
}

async function fixture(limitValue = 3) {
  const databases = [new PostgresDatabase(() => databaseUrl), new PostgresDatabase(() => databaseUrl)];
  const database = databases[0];
  const services = databases.map((db) => new PracticeService(new PostgresPracticeStore(db)));
  const prefix = `edge-${crypto.randomUUID().slice(0, 8)}`;
  const userTelegramId = update();
  const otherTelegramId = update();
  const questionIds: string[] = [];
  const result = await database.transaction(async (client) => {
    const queryId = idQuery(client);
    const examId = await queryId`INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at)
      VALUES(${prefix},'Synthetic Edge Test Exam','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
    const category = async (code: string) => await queryId`INSERT INTO categories(exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
      VALUES(${examId},${code},${`Synthetic ${code}`},'',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
    const categoryId = await category("numbers");
    const premiumCategoryId = await category("premium");
    const inactiveCategoryId = await category("inactive");
    await client.queryArray`UPDATE categories SET active=FALSE WHERE id=${inactiveCategoryId}`;
    const user = async (telegramId: string) => {
      const hash = fakeHash();
      const userId = await queryId`INSERT INTO bot_users(telegram_user_id,preferred_language,selected_exam_type_id,registration_status,
        phone_identity_hash,registration_completed_at,created_at,updated_at)
        VALUES(${telegramId},'en',${examId},'COMPLETED',${hash},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      await client.queryArray`INSERT INTO access_entitlements(user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,
        questions_per_mock,practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
        VALUES(${userId},${examId},${hash},'FREE',${limitValue},2,50,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`;
      return userId;
    };
    return { examId, categoryId, premiumCategoryId, inactiveCategoryId, userId: await user(userTelegramId), otherId: await user(otherTelegramId) };
  });
  const version = async (questionId: string, number: number, options: { categoryId?: string; free?: boolean; premium?: boolean; text?: string; examId?: string } = {}) =>
    await database.transaction(async (client) => {
      const queryId = idQuery(client);
      const versionId = await queryId`INSERT INTO question_versions(question_id,version_number,exam_type_id,category_id,exam_name,category_name,
        question_text,explanation,difficulty,source_type,source_title,source_reference,source_notes,use_status,free_pool,premium_pool,mock_pool,
        fingerprint,stem_fingerprint,created_by,created_at,updated_at)
        VALUES(${questionId},${number},${options.examId ?? result.examId},${options.categoryId ?? result.categoryId},'Synthetic Edge Test Exam','Synthetic numbers',
        ${options.text ?? `Synthetic version ${number}`},${`Synthetic explanation ${number}`},'EASY','Original','Synthetic Edge fixtures','','','ORIGINAL',
        ${options.free ?? true},${options.premium ?? false},FALSE,${fakeHash()},${fakeHash()},'edge-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      await client.queryArray`INSERT INTO question_options(version_id,position,option_text,correct)
        VALUES(${versionId},0,'Synthetic correct',TRUE),(${versionId},1,'Synthetic incorrect',FALSE)`;
      await client.queryArray`UPDATE questions SET current_version_id=${versionId},status='PUBLISHED' WHERE id=${questionId}`;
      return versionId;
    });
  const question = async (options: { status?: string; categoryId?: string; free?: boolean; premium?: boolean; text?: string; examId?: string } = {}) => {
    const questionId = await database.transaction(async (client) => {
      const queryId = idQuery(client);
      return await queryId`INSERT INTO questions(status,created_by,updated_by,created_at,updated_at)
        VALUES('DRAFT','edge-test','edge-test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
    });
    questionIds.push(questionId);
    const versionId = await version(questionId, 1, options);
    if (options.status) await database.transaction(async (client) => { await client.queryArray`UPDATE questions SET status=${options.status} WHERE id=${questionId}`; });
    return { questionId, versionId };
  };
  const count = async (table: "practice_deliveries" | "practice_usage" | "practice_sessions") => database.withConnection(async (client) => {
    // The caller chooses from a closed set; values remain parameterized.
    const query = table === "practice_deliveries" ? await client.queryObject<{ count: bigint }>`SELECT COUNT(*) AS count FROM practice_deliveries WHERE user_id=${result.userId}`
      : table === "practice_usage" ? await client.queryObject<{ count: bigint }>`SELECT COUNT(*) AS count FROM practice_usage WHERE user_id=${result.userId}`
      : await client.queryObject<{ count: bigint }>`SELECT COUNT(*) AS count FROM practice_sessions WHERE user_id=${result.userId}`;
    return Number(query.rows[0].count);
  });
  return { ...result, database, services, userTelegramId, otherTelegramId, question, version, count,
    close: async () => {
      await database.transaction(async (client) => {
        await client.queryArray`DELETE FROM practice_update_receipts WHERE user_id IN (${result.userId},${result.otherId})`;
        await client.queryArray`DELETE FROM practice_sessions WHERE user_id IN (${result.userId},${result.otherId})`;
        await client.queryArray`DELETE FROM practice_usage WHERE user_id IN (${result.userId},${result.otherId})`;
        await client.queryArray`UPDATE practice_deliveries SET next_delivery_id=NULL,review_delivery_id=NULL WHERE user_id IN (${result.userId},${result.otherId})`;
        await client.queryArray`DELETE FROM practice_deliveries WHERE user_id IN (${result.userId},${result.otherId})`;
        await client.queryArray`DELETE FROM access_entitlements WHERE user_id IN (${result.userId},${result.otherId})`;
        await client.queryArray`DELETE FROM bot_users WHERE id IN (${result.userId},${result.otherId})`;
        if (questionIds.length) {
          await client.queryArray`UPDATE questions SET status='DRAFT',current_version_id=NULL WHERE id=ANY(${questionIds.map(Number)})`;
          await client.queryArray`DELETE FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id=ANY(${questionIds.map(Number)}))`;
          await client.queryArray`DELETE FROM question_versions WHERE question_id=ANY(${questionIds.map(Number)})`;
          await client.queryArray`DELETE FROM questions WHERE id=ANY(${questionIds.map(Number)})`;
        }
        await client.queryArray`DELETE FROM categories WHERE exam_type_id IN
          (SELECT id FROM exam_types WHERE code LIKE 'edge-%' AND name='Synthetic Edge Test Exam')`;
        await client.queryArray`DELETE FROM exam_types WHERE code LIKE 'edge-%' AND name='Synthetic Edge Test Exam'`;
      });
      for (const db of databases) await db.close();
    } };
}

Deno.test("PostgreSQL: actual selection SQL excludes unpublished, wrong exam, inactive taxonomy and premium-only content", async () => {
  const f = await fixture();
  try {
    for (const status of ["DRAFT", "REVIEWED", "ARCHIVED"]) await f.question({ status });
    const premium = await f.question({ free: false, premium: true, categoryId: f.premiumCategoryId });
    const eligible = await f.question();
    const service = f.services[0];
    const listed = await f.database.transaction(async (client) => {
      const unit = new PracticeUnitOfWork(client);
      const student = (await unit.student(f.userTelegramId))!;
      return await unit.categories(student, 0);
    });
    assert.deepEqual(listed.map((category) => category.id), [f.categoryId, f.premiumCategoryId]);
    assert.equal(listed.find((category) => category.id === f.categoryId)?.canAccess, true);
    assert.equal(listed.find((category) => category.id === f.premiumCategoryId)?.canAccess, false);
    assert.equal(listed.find((category) => category.id === f.premiumCategoryId)?.requiresUpgrade, true);
    await assert.rejects(service.next(f.userTelegramId, update(), { categoryId: f.premiumCategoryId }),
      (error) => error instanceof PracticeError && error.key === "category.upgrade");
    assert.equal(await f.count("practice_deliveries"), 0);
    const select = () => f.database.transaction(async (client) => {
      const unit = new PracticeUnitOfWork(client);
      const student = (await unit.student(f.userTelegramId))!;
      return await unit.selectVersion(student, null, false);
    });
    assert.equal(await select(), eligible.versionId);
    const otherExamCategory = await f.database.transaction(async (client) => {
      const queryId = idQuery(client);
      const code = `edge-${crypto.randomUUID().slice(0, 8)}`;
      const examId = await queryId`INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${code},'Synthetic Edge Test Exam','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      return await queryId`INSERT INTO categories(exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examId},'other','Synthetic Other Exam Category','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
    });
    const categoriesAfterOtherExam = await f.database.transaction(async (client) => {
      const unit = new PracticeUnitOfWork(client);
      const student = (await unit.student(f.userTelegramId))!;
      return await unit.categories(student, 0);
    });
    assert.ok(!categoriesAfterOtherExam.some((category) => category.id === otherExamCategory));
    await f.database.transaction(async (c) => { await c.queryArray`UPDATE categories SET active=FALSE WHERE id=${f.categoryId}`; });
    assert.equal(await select(), null);
    await f.database.transaction(async (c) => { await c.queryArray`UPDATE access_entitlements SET access_level='LIFETIME' WHERE user_id=${f.userId}`; });
    const upgradedCategories = await f.database.transaction(async (client) => {
      const unit = new PracticeUnitOfWork(client);
      const student = (await unit.student(f.userTelegramId))!;
      return await unit.categories(student, 0);
    });
    assert.equal(upgradedCategories.find((category) => category.id === f.premiumCategoryId)?.canAccess, true);
    assert.equal(upgradedCategories.find((category) => category.id === f.premiumCategoryId)?.requiresUpgrade, false);
    const premiumDelivery = await service.next(f.userTelegramId, update(), { categoryId: f.premiumCategoryId });
    assert.equal(premiumDelivery.question.versionId, premium.versionId);
    assert.equal(await select(), premium.versionId);
    await f.database.transaction(async (c) => { await c.queryArray`UPDATE exam_types SET active=FALSE WHERE id=${f.examId}`; });
    assert.equal(await select(), null);
  } finally { await f.close(); }
});

Deno.test("PostgreSQL: display/skip cost zero, concurrent answer costs one, resumed updates replay and ownership is enforced", async () => {
  const f = await fixture();
  try {
    for (let i=0;i<4;i++) await f.question();
    const [service, second] = f.services;
    const firstUpdate = update();
    const first = await service.next(f.userTelegramId, firstUpdate);
    const duplicates = await Promise.all([service.next(f.userTelegramId, update(), { previousId: first.delivery.id }),
      second.next(f.userTelegramId, update(), { previousId: first.delivery.id })]);
    const skipped = duplicates[0];
    assert.equal(skipped.delivery.id, duplicates[1].delivery.id);
    assert.equal(await f.count("practice_deliveries"), 2);
    assert.equal(await f.count("practice_usage"), 0);
    assert.equal((await service.profile(f.userTelegramId)).practiceUsed, 0);
    const resumedUpdate = update();
    assert.equal((await service.next(f.userTelegramId, resumedUpdate)).delivery.id, skipped.delivery.id);
    await Promise.all([service.answer(f.userTelegramId, skipped.delivery.id, 0), second.answer(f.userTelegramId, skipped.delivery.id, 0)]);
    assert.equal((await service.answer(f.userTelegramId, skipped.delivery.id, 1)).correct, true);
    assert.equal(await f.count("practice_usage"), 1);
    assert.equal((await service.profile(f.userTelegramId)).practiceUsed, 1);
    await service.next(f.userTelegramId, update());
    assert.equal((await service.next(f.userTelegramId, resumedUpdate)).delivery.id, skipped.delivery.id);
    assert.equal(await f.count("practice_deliveries"), 3);
    assert.equal(await f.count("practice_sessions"), 1);
    await assert.rejects(service.answer(f.otherTelegramId, skipped.delivery.id, 0), invalid);
    await assert.rejects(service.historyDelivery(f.otherTelegramId, skipped.delivery.id), invalid);
    await assert.rejects(service.next(f.otherTelegramId, firstUpdate), invalid);
    await assert.rejects(service.answer(f.userTelegramId, first.delivery.id, 7), invalid);
  } finally { await f.close(); }
});

Deno.test("PostgreSQL: practice selection and usage stay independent per exam", async () => {
  const f = await fixture(4);
  try {
    const examAQuestion = await f.question();
    const examB = await f.database.transaction(async (client) => {
      const queryId = idQuery(client);
      const code = `edge-${crypto.randomUUID().slice(0, 8)}`;
      const examId = await queryId`INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${code},'Synthetic Edge Test Exam','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      const categoryId = await queryId`INSERT INTO categories(exam_type_id,code,name,name_am,active,display_order,created_at,updated_at)
        VALUES(${examId},'numbers','Synthetic numbers','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id`;
      const hash = (await client.queryObject<{ phone_identity_hash: string }>`SELECT phone_identity_hash FROM access_entitlements WHERE user_id=${f.userId}`).rows[0].phone_identity_hash;
      await client.queryArray`INSERT INTO access_entitlements(user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,
        questions_per_mock,practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
        VALUES(${f.userId},${examId},${hash},'FREE',4,2,50,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`;
      return { examId, categoryId };
    });
    const examBQuestion = await f.question({ examId: examB.examId, categoryId: examB.categoryId });
    const service = f.services[0];
    await f.database.transaction(async (client) => { await client.queryArray`UPDATE bot_users SET selected_exam_type_id=${examB.examId} WHERE id=${f.userId}`; });
    const bDelivery = await service.next(f.userTelegramId, update());
    assert.equal(bDelivery.question.id, examBQuestion.questionId);
    await service.answer(f.userTelegramId, bDelivery.delivery.id, 0);
    await f.database.transaction(async (client) => { await client.queryArray`UPDATE bot_users SET selected_exam_type_id=${f.examId} WHERE id=${f.userId}`; });
    const aDelivery = await service.next(f.userTelegramId, update());
    assert.equal(aDelivery.question.id, examAQuestion.questionId);
    await service.answer(f.userTelegramId, aDelivery.delivery.id, 0);
    const usage = await f.database.withConnection(async (client) => (await client.queryObject<{ exam_type_id: bigint; used: bigint }>`
      SELECT exam_type_id,COUNT(*) AS used FROM practice_usage WHERE user_id=${f.userId} GROUP BY exam_type_id ORDER BY exam_type_id`).rows);
    assert.equal(usage.length, 2);
    assert.deepEqual(usage.map((row) => Number(row.used)), [1, 1]);
    const entitlements = await f.database.withConnection(async (client) => (await client.queryObject<{ practice_used: number }>`
      SELECT practice_used FROM access_entitlements WHERE user_id=${f.userId} ORDER BY exam_type_id`).rows);
    assert.deepEqual(entitlements.map((row) => row.practice_used), [1, 1]);
  } finally { await f.close(); }
});

Deno.test("PostgreSQL: stored limit, stale answers, progress math and current lifetime grants", async () => {
  const f = await fixture(3);
  try {
    for (let i=0;i<4;i++) await f.question();
    const premium = await f.question({ free:false, premium:true, categoryId:f.premiumCategoryId });
    const service = f.services[0];
    const deliveries = [];
    let previousId = null;
    for (let i=0;i<4;i++) {
      const view = await service.next(f.userTelegramId,update(),{previousId});
      deliveries.push(view.delivery.id); previousId = view.delivery.id;
    }
    for (let i=0;i<3;i++) await service.answer(f.userTelegramId,deliveries[i],i===1?1:0);
    const progress = await service.progress(f.userTelegramId);
    assert.equal(progress.answered,3); assert.equal(progress.correct,2); assert.equal(progress.incorrect,1);
    assert.equal(progress.accuracy,66.67); assert.equal(progress.categories[0].accuracy,66.67);
    assert.equal(progress.student.practiceUsed,3); assert.equal(progress.student.practiceLimit,3);
    await assert.rejects(service.answer(f.userTelegramId,deliveries[3],0),limit);
    await assert.rejects(service.next(f.userTelegramId,update(),{previousId:deliveries[3]}),limit);
    await service.next(f.userTelegramId,update(),{previousId:deliveries[2],review:true});
    await f.database.transaction(async(c)=>{await c.queryArray`UPDATE access_entitlements SET access_level='LIFETIME' WHERE user_id=${f.userId}`;});
    const upgraded=await service.next(f.userTelegramId,update(),{categoryId:f.premiumCategoryId});
    assert.equal(upgraded.question.versionId,premium.versionId);
    await service.answer(f.userTelegramId,upgraded.delivery.id,0);
    assert.equal((await service.profile(f.userTelegramId)).practiceUsed,3);
  } finally { await f.close(); }
});

Deno.test("PostgreSQL: history freezes versions, survives archive/deactivation, and revisions cost no additional slot", async () => {
  const f = await fixture();
  try {
    const original = await f.question({text:'Synthetic original wording'});
    const service=f.services[0];
    const delivered=await service.next(f.userTelegramId,update());
    await service.answer(f.userTelegramId,delivered.delivery.id,1);
    const revision=await f.version(original.questionId,2,{text:'Synthetic revised wording'});
    const review=await service.next(f.userTelegramId,update(),{review:true});
    assert.equal(review.question.versionId,revision);
    await service.answer(f.userTelegramId,review.delivery.id,0);
    await f.database.transaction(async(c)=>{
      await c.queryArray`UPDATE questions SET status='ARCHIVED' WHERE id=${original.questionId}`;
      await c.queryArray`UPDATE categories SET active=FALSE WHERE id=${f.categoryId}`;
    });
    const old=await service.historyDelivery(f.userTelegramId,delivered.delivery.id);
    assert.equal(old.question.text,'Synthetic original wording'); assert.equal(old.correct,false);
    const history=await service.history(f.userTelegramId);
    assert.deepEqual(history.rows.map((r: {id:string})=>r.id),[review.delivery.id,delivered.delivery.id]);
    const progress=await service.progress(f.userTelegramId);
    assert.equal(progress.answered,1); assert.equal(progress.correct,0); assert.equal(progress.student.practiceUsed,1);
  } finally { await f.close(); }
});

Deno.test("PostgreSQL: transaction rollback and receipt constraints protect state", async () => {
  const f=await fixture();
  try {
    const q=await f.question();
    const service=f.services[0];
    const event=update();
    const view=await service.next(f.userTelegramId,event);
    await assert.rejects(f.database.transaction(async(c)=>{
      const unit=new PracticeUnitOfWork(c); const student=(await unit.student(f.userTelegramId))!;
      const delivery=await unit.createDelivery(student,q.versionId,null);
      await unit.recordAnswer(student,delivery,0);
      throw new Error('INTENTIONAL_TEST_ROLLBACK');
    }),/INTENTIONAL_TEST_ROLLBACK/);
    assert.equal(await f.count('practice_deliveries'),1); assert.equal(await f.count('practice_usage'),0);
    assert.equal((await service.profile(f.userTelegramId)).practiceUsed,0);
    await assert.rejects(f.database.transaction(async(c)=>{
      await c.queryArray`INSERT INTO practice_update_receipts(update_id,user_id,question_id,delivery_id,created_at)
        VALUES(${event},${f.userId},${q.questionId},${view.delivery.id},CURRENT_TIMESTAMP)`;
    }));
    await assert.rejects(f.database.transaction(async(c)=>{
      await c.queryArray`INSERT INTO practice_update_receipts(update_id,user_id,question_id,delivery_id,created_at)
        VALUES(${update()},${f.otherId},${q.questionId},${view.delivery.id},CURRENT_TIMESTAMP)`;
    }));
    assert.equal((await service.next(f.userTelegramId,event)).delivery.id,view.delivery.id);
  } finally { await f.close(); }
});
