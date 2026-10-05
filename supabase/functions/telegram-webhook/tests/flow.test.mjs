import test from "node:test";
import assert from "node:assert/strict";
import { message } from "../domain.mjs";
import { createPracticeFlow } from "../practice-flow.mjs";
import { PracticeError } from "../practice-service.mjs";
import { createTelegramWebhookHandler } from "../handler.mjs";

function fixture(language = "en") {
  const calls = [];
  const sent = [];
  const student = { id: "1", telegramId: "77", examId: "1", language, accessLevel: "FREE",
    practiceLimit: 100, practiceUsed: 2, mockLimit: 2, mocksUsed: 0 };
  const view = {
    student,
    delivery: { id: "10", selectedOption: null },
    question: { text: "Synthetic practice prompt", explanation: "Synthetic explanation", options: [
      { position: 0, text: "Synthetic answer A", correct: true },
      { position: 1, text: "Synthetic answer B", correct: false },
    ] },
    answered: false,
    correct: false,
  };
  const service = {
    async maintenanceLanguage() { return null; },
    async profile(...args) { calls.push(["profile", ...args]); return student; },
    async categories(...args) { calls.push(["categories", ...args]); return { student, categories: [] }; },
    async next(...args) { calls.push(["next", ...args]); return view; },
    async answer(...args) { calls.push(["answer", ...args]); return { ...view, answered: true, correct: true }; },
    async historyDelivery(...args) { calls.push(["historyDelivery", ...args]); return { ...view, answered: true }; },
    async progress(...args) {
      calls.push(["progress", ...args]);
      return { student, answered: 2, correct: 1, incorrect: 1, accuracy: 50,
        categories: [{ name: "Synthetic category", correct: 1, answered: 2, accuracy: 50 }], completed: 0, recent: [] };
    },
    async history(...args) { calls.push(["history", ...args]); return { student, page: args[1], rows: [], more: false }; },
    async insights(...args) { calls.push(["insights", ...args]); return { student, page: args[1], rows: [], more: false }; },
    async recommendation(...args) { calls.push(["recommendation", ...args]); return null; },
  };
  const telegram = { async sendMessage(...args) { sent.push(args); }, async answerCallbackQuery() {} };
  return { student, view, calls, sent, service, telegram, flow: createPracticeFlow(service, telegram) };
}

test("the actual s:progress callback displays current metrics and allowance", async () => {
  const f = fixture();
  await f.flow.callback("77", "77", "s:progress", "100");
  assert.deepEqual(f.calls, [["profile", "77"], ["progress", "77"]]);
  assert.match(f.sent[0][1], /2 unique answered; 1 correct; 1 incorrect; 50% accuracy/);
  assert.match(f.sent[0][1], /98 remaining of 100/);
  assert.match(f.sent[0][1], /Synthetic category: 1\/2 first answers correct \(50%\)/);
  assert.equal(f.sent[0][2].inline_keyboard.at(-1)[0].callback_data, "s:home");
});

test("home menu loads a single profile and preserves registered menu callbacks", async () => {
  const f = fixture();
  await f.flow.callback("77", "77", "s:home", "100");
  assert.deepEqual(f.calls, [["profile", "77"]]);
  assert.deepEqual(f.sent[0][2].inline_keyboard.flat().map((item) => item.callback_data), [
    "p:menu", "m:intro", "s:progress", "s:help", "s:ph:0", "s:mh:0", "s:weak:0", "s:recommend", "pay:open", "pay:status", "s:exams", "lang:choose",
  ]);
});

test("all practice navigation callbacks pass their intended action and update ID", async () => {
  for (const [data, expected] of [
    ["p:c:0", ["next", "77", "100", { categoryId: null }]],
    ["p:c:9223372036854775807", ["next", "77", "100", { categoryId: "9223372036854775807" }]],
    ["p:n:10", ["next", "77", "100", { previousId: "10", review: false }]],
    ["p:r:0", ["next", "77", "100", { previousId: null, review: true }]],
    ["p:r:10", ["next", "77", "100", { previousId: "10", review: true }]],
    ["p:a:10:1", ["answer", "77", "10", 1]],
    ["p:h:10", ["historyDelivery", "77", "10"]],
    ["p:g:1", ["categories", "77", 1]],
    ["s:ph:1", ["history", "77", 1]],
    ["s:weak:1", ["insights", "77", 1]],
  ]) {
    const f = fixture();
    await f.flow.callback("77", "77", data, "100");
    assert.deepEqual(f.calls[1], expected, data);
    assert.equal(f.sent.length, 1, data);
  }
});

test("invalid and overflowing IDs never reach question or history storage", async () => {
  for (const data of [
    "p:c:9223372036854775808", "p:n:9223372036854775808", "p:r:9223372036854775808",
    "p:a:9223372036854775808:0", "p:h:9223372036854775808", "p:n:0", "p:a:0:0", "p:h:0",
    "p:g:1000001", "p:g:9999999999999999999", "p:c:-1", "s:progress:0",
  ]) {
    const f = fixture();
    await f.flow.callback("77", "77", data, "100");
    assert.deepEqual(f.calls, [["profile", "77"]], data);
    assert.equal(f.sent[0][1], message("en", "student.invalid"), data);
  }
});

test("business errors retain the student's language and do not reload the profile", async () => {
  const f = fixture("am");
  f.service.next = async () => { throw new PracticeError("practice.limit"); };
  await f.flow.callback("77", "77", "p:c:0", "100");
  assert.deepEqual(f.calls, [["profile", "77"]]);
  assert.equal(f.sent[0][1], message("am", "practice.limit"));
  assert.equal(f.sent[0][2].inline_keyboard[0][0].callback_data, "p:r:0");
});

test("incomplete registration blocks practice and explains how to register", async () => {
  const f = fixture();
  f.service.profile = async () => { throw new PracticeError("student.register"); };
  await f.flow.callback("77", "77", "p:menu", "100");
  assert.deepEqual(f.calls, []);
  assert.equal(f.sent[0][1], message("en", "student.register"));
});

test("maintenance prevents business actions and preserves the returned language", async () => {
  const f = fixture();
  f.service.maintenanceLanguage = async () => "am";
  await f.flow.callback("77", "77", "p:c:0", "100");
  assert.deepEqual(f.calls, []);
  assert.equal(f.sent[0][1], message("am", "maintenance.message"));
});

test("unexpected storage failures propagate so the webhook can retry", async () => {
  for (const operation of ["maintenanceLanguage", "profile", "next"]) {
    const f = fixture();
    const failure = new Error("Synthetic storage unavailable");
    f.service[operation] = async () => { throw failure; };
    await assert.rejects(f.flow.callback("77", "77", "p:c:0", "100"), (error) => error === failure);
    assert.equal(f.sent.length, 0);
  }
});

test("Telegram send failures propagate after both successful actions and business errors", async () => {
  for (const businessError of [false, true]) {
    const f = fixture();
    const failure = new Error("Synthetic Telegram send failure");
    f.telegram.sendMessage = async () => { throw failure; };
    if (businessError) f.service.next = async () => { throw new PracticeError("practice.limit"); };
    await assert.rejects(f.flow.callback("77", "77", "p:c:0", "100"), (error) => error === failure);
  }
});

test("long question text is chunked without splitting surrogate pairs; controls attach only to final chunk", async () => {
  const f = fixture();
  f.view.question.text = "x".repeat(3499) + "😀" + "y".repeat(3600);
  await f.flow.callback("77", "77", "p:c:0", "100");
  assert.equal(f.sent.length, 3);
  for (const [index, [, text, markup]] of f.sent.entries()) {
    assert.ok(text.length <= 3500);
    assert.ok(!/[\uD800-\uDBFF]$/.test(text));
    assert.ok(!/^[\uDC00-\uDFFF]/.test(text));
    assert.equal(markup === undefined, index !== f.sent.length - 1);
  }
  assert.equal(f.sent.map((entry) => entry[1]).join(""), `${f.view.question.text}\n\nA. Synthetic answer A\nB. Synthetic answer B`);
  assert.equal(f.sent.at(-1)[2].inline_keyboard[0][0].callback_data, "p:a:10:0");
});

test("real flow failures become HTTP 503 without exposing exception details in logs", async () => {
  for (const failureType of ["storage", "telegram"]) {
    const f = fixture();
    const failure = new Error("SyntheticSensitiveDetail");
    if (failureType === "storage") f.service.progress = async () => { throw failure; };
    else f.telegram.sendMessage = async () => { throw failure; };
    const logs = [];
    const env = { TELEGRAM_BOT_TOKEN: "test-token", TELEGRAM_WEBHOOK_SECRET: "test-secret",
      PHONE_IDENTITY_HMAC_KEY: "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=",
      DATABASE_URL: "postgresql://test:placeholder@localhost:6543/postgres" };
    const handler = createTelegramWebhookHandler({ env, store: {}, practice: f.flow, telegram: f.telegram,
      logger: { info: (value) => logs.push(value), warn: (value) => logs.push(value) } });
    const response = await handler(new Request("https://edge.invalid/functions/v1/telegram-webhook", {
      method: "POST", headers: { "X-Telegram-Bot-Api-Secret-Token": env.TELEGRAM_WEBHOOK_SECRET },
      body: JSON.stringify({ update_id: 100, callback_query: { id: "synthetic-callback", data: "s:progress",
        from: { id: 77 }, message: { chat: { id: 77, type: "private" } } } }),
    }));
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("retry-after"), "5");
    assert.match(logs.join(" "), /retryable_Error/);
    assert.doesNotMatch(logs.join(" "), /SyntheticSensitiveDetail|test-secret|test-token|postgresql/);
  }
});
