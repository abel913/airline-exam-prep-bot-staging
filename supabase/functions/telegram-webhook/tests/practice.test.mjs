import test from "node:test";
import assert from "node:assert/strict";
import { PracticeError, PracticeService } from "../practice-service.mjs";
import { createPracticeFlow } from "../practice-flow.mjs";

const question = (id, versionId = `v${id}`, { pool = "free", status = "PUBLISHED", examId = "exam-a", categoryId = "cat-a" } = {}) => ({
  id, versionId, examId, categoryId, categoryName: categoryId,
  text: `Synthetic question ${id}`, explanation: `Synthetic explanation ${id}`,
  pool, status, options: [
    { position: 0, text: "Synthetic option A", correct: true },
    { position: 1, text: "Synthetic option B", correct: false },
  ],
});

function fixture({ accessLevel = "FREE", practiceLimit = 2, practiceUsed = 0, questions = [question("q1"), question("q2"), question("q3")] } = {}) {
  const student = { id: "user-1", telegramId: "77", examId: "exam-a", language: "en", accessLevel,
    practiceLimit, practiceUsed, mockLimit: 2, mocksUsed: 0, questionsPerMock: 50 };
  const deliveries = new Map();
  const used = new Set();
  const receipts = new Map();
  const frozenVersions = new Map(questions.map((item) => [item.versionId, structuredClone(item)]));
  let id = 0;
  const mutex = { tail: Promise.resolve() };
  const store = {
    async withAction(operation) {
      const prior = mutex.tail;
      let release;
      mutex.tail = new Promise((resolve) => { release = resolve; });
      await prior;
      try { return await operation(unit); } finally { release(); }
    },
  };
  const unit = {
    async student(telegramId) { return telegramId === "77" ? student : null; },
    async categories(_student, page) { return [{ id: "cat-a", name: "Synthetic", nameAm: "" }].slice(page * 20, page * 20 + 21); },
    async validateCategory(_student, categoryId) { if (categoryId && categoryId !== "cat-a") throw new Error("STUDENT_INVALID"); },
    async deliveryByUpdateId(updateId) { return deliveries.get(receipts.get(updateId)) ?? null; },
    async recordDeliveryUpdate(_student, updateId, delivery) { assert.equal(receipts.has(updateId), false); receipts.set(updateId, delivery.id); },
    async ownedDelivery(_student, deliveryId) { return deliveries.get(String(deliveryId)) ?? null; },
    async currentDelivery() { return [...deliveries.values()].at(-1) ?? null; },
    async used(_student, questionId) { return used.has(questionId); },
    async selectVersion(_student, categoryId, review) {
      const candidates = questions.filter((q) => q.status === "PUBLISHED" && q.examId === "exam-a"
        && (!categoryId || q.categoryId === categoryId)
        && (q.pool === "free" || (accessLevel === "LIFETIME" && q.pool === "premium"))
        && used.has(q.id) === review);
      return candidates[0]?.versionId ?? null;
    },
    async frozen(versionId) { return frozenVersions.get(versionId) ?? null; },
    async createDelivery(_student, versionId, categoryFilter, updateId) {
      const q = questions.find((item) => item.versionId === versionId);
      const delivery = { id: String(++id), userId: "user-1", questionId: q.id, versionId, categoryFilter,
        updateId, nextDeliveryId: null, reviewDeliveryId: null, selectedOption: null, answeredAt: null };
      deliveries.set(delivery.id, delivery);
      return delivery;
    },
    async link(previous, nextId, review) { previous[review ? "reviewDeliveryId" : "nextDeliveryId"] = nextId; },
    async setCurrentDelivery() {},
    async recordAnswer(_student, delivery, position) {
      if (delivery.selectedOption === null) {
        delivery.selectedOption = position;
        delivery.answeredAt = "2026-10-03T00:00:00.000Z";
        if (!used.has(delivery.questionId)) {
          used.add(delivery.questionId);
          if (accessLevel === "FREE") student.practiceUsed += 1;
        }
      }
      return { usageCreated: true, practiceUsed: student.practiceUsed };
    },
    async progress() { return { answered: used.size, correct: used.size, categories: [], completed: 0, recent: [] }; },
    async history() { return { rows: [], more: false }; },
    async insights() { return { rows: [], more: false }; },
    async recommendation() { return null; },
    async maintenanceLanguage() { return null; },
  };
  return { service: new PracticeService(store), student, deliveries, used, questions, frozenVersions };
}

test("first delivery is version frozen and showing it does not charge usage", async () => {
  const f = fixture();
  const first = await f.service.next("77", "100");
  assert.equal(first.question.versionId, "vq1");
  assert.equal(f.student.practiceUsed, 0);
  assert.equal(f.used.size, 0);
  const retry = await f.service.next("77", "100");
  assert.equal(retry.delivery.id, first.delivery.id);
  assert.equal(f.deliveries.size, 1);
});

test("next and repeated skip link one next delivery without charging usage", async () => {
  const f = fixture();
  const first = await f.service.next("77", "100");
  const next = await f.service.next("77", "101", { previousId: first.delivery.id });
  const repeated = await f.service.next("77", "102", { previousId: first.delivery.id });
  assert.equal(next.delivery.id, repeated.delivery.id);
  assert.equal(f.deliveries.size, 2);
  assert.equal(f.student.practiceUsed, 0);
});

test("a resumed update still replays its original delivery after a later answer and next action", async () => {
  const f = fixture();
  const first = await f.service.next("77", "100");
  const resumed = await f.service.next("77", "101");
  assert.equal(resumed.delivery.id, first.delivery.id);
  await f.service.answer("77", first.delivery.id, 0);
  await f.service.next("77", "102");
  const replay = await f.service.next("77", "101");
  assert.equal(replay.delivery.id, first.delivery.id);
  assert.equal(f.deliveries.size, 2);
});

test("first unique answer charges once; repeated answer and revised version do not", async () => {
  const f = fixture({ practiceLimit: 3, questions: [question("q1"), question("q2")] });
  const delivery = await f.service.next("77", "100");
  const first = await f.service.answer("77", delivery.delivery.id, 0);
  const repeatedDifferentOption = await f.service.answer("77", delivery.delivery.id, 1);
  assert.equal(first.correct, true);
  assert.equal(repeatedDifferentOption.correct, true);
  assert.equal(f.student.practiceUsed, 1);
  const revised = await f.service.next("77", "101");
  assert.equal(revised.question.id, "q2");
  await f.service.answer("77", revised.delivery.id, 1);
  assert.equal(f.student.practiceUsed, 2);
  const revisedQuestion = question("q1", "v1-new");
  f.frozenVersions.set(revisedQuestion.versionId, revisedQuestion);
  f.questions[0] = revisedQuestion;
  const review = await f.service.next("77", "102", { previousId: revised.delivery.id, review: true });
  assert.equal(review.question.id, "q1");
  assert.equal(review.question.versionId, "v1-new");
  await f.service.answer("77", review.delivery.id, 0);
  assert.equal(f.student.practiceUsed, 2);
  assert.equal(f.used.size, 2);
});

test("limit blocks new practice and unanswered submissions; lifetime bypasses and sees premium pool", async () => {
  const f = fixture({ practiceLimit: 1, practiceUsed: 1, questions: [question("premium", "vp", { pool: "premium" })] });
  await assert.rejects(f.service.next("77", "100"), (error) => error instanceof PracticeError && error.key === "practice.limit");
  const lifetime = fixture({ accessLevel: "LIFETIME", practiceLimit: 1, practiceUsed: 1,
    questions: [question("premium", "vp", { pool: "premium" })] });
  const selected = await lifetime.service.next("77", "100");
  assert.equal(selected.question.id, "premium");
  await lifetime.service.answer("77", selected.delivery.id, 0);
  assert.equal(lifetime.student.practiceUsed, 1);
});

test("registration, ownership, stale deliveries, and invalid options fail closed", async () => {
  const f = fixture();
  await assert.rejects(f.service.next("88", "100"), (error) => error instanceof PracticeError && error.key === "student.register");
  await assert.rejects(f.service.answer("77", "999", 0), (error) => error instanceof PracticeError && error.key === "student.invalid");
  const d = await f.service.next("77", "100");
  await assert.rejects(f.service.answer("77", d.delivery.id, 4), (error) => error instanceof PracticeError && error.key === "student.invalid");
});

test("concurrent duplicate answer events produce one usage event", async () => {
  const f = fixture({ practiceLimit: 3 });
  const delivery = await f.service.next("77", "100");
  await Promise.all(Array.from({ length: 12 }, () => f.service.answer("77", delivery.delivery.id, 0)));
  assert.equal(f.student.practiceUsed, 1);
  assert.equal(f.used.size, 1);
});

test("progress math and Telegram callbacks preserve bounded routing", async () => {
  const f = fixture();
  const progress = await f.service.progress("77");
  assert.equal(progress.incorrect, 0);
  assert.equal(progress.accuracy, 0);
  const sent = [];
  const flow = createPracticeFlow(f.service, { async sendMessage(...args) { sent.push(args); } });
  await flow.callback("77", "77", "p:menu", "100");
  assert.equal(sent.length, 1);
  assert.equal(sent[0][2].inline_keyboard[0][0].callback_data, "p:c:0");
});

test("practice resume returns the existing exam-scoped delivery without charging or creating a new one", async () => {
  const f = fixture();
  const initial = await f.service.next("77", "100");
  const beforeCount = f.deliveries.size;
  const resumed = await f.service.resume("77");
  assert.equal(resumed.delivery.id, initial.delivery.id);
  assert.equal(resumed.question.versionId, initial.question.versionId);
  assert.equal(f.deliveries.size, beforeCount);
  assert.equal(f.student.practiceUsed, 0);
  const sent = [];
  const flow = createPracticeFlow(f.service, { async sendMessage(...args) { sent.push(args); } });
  await flow.callback("77", "77", "p:resume", "101");
  assert.equal(f.deliveries.size, beforeCount);
  assert.match(sent.at(-1)[1], /Synthetic question q1/);
});
