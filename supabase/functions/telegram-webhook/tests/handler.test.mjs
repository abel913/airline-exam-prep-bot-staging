import test from "node:test";
import assert from "node:assert/strict";
import { createTelegramWebhookHandler } from "../handler.mjs";

const env = {
  TELEGRAM_BOT_TOKEN: "test-token",
  TELEGRAM_WEBHOOK_SECRET: "test-secret",
  PHONE_IDENTITY_HMAC_KEY: "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=",
  DATABASE_URL: "postgresql://test:placeholder@localhost:6543/postgres",
};

function fakeStore() {
  const calls = [];
  const store = {
    calls,
    async healthCheck() { calls.push(["health"]); },
    async start(id) { calls.push(["start", id]); return { status: "LANGUAGE_REQUIRED", language: "en" }; },
    async language(id, language) { calls.push(["language", id, language]); return { status: "EXAM_TYPE_REQUIRED", language, exams: [{ id: "1", name: "Staging Test Exam", nameAm: "" }] }; },
    async exam(id, examId) { calls.push(["exam", id, examId]); return { status: "PHONE_REQUIRED", language: "en" }; },
    async manualPhoneInput(id) { calls.push(["manual", id]); return { status: "PHONE_REQUIRED", language: "en", errorKey: "registration.manualPhone" }; },
    async manualPhone(id, phone) {
      calls.push(["manualPhone", id, phone]);
      return { status: "COMPLETED", language: "en", grant: { accessLevel: "FREE", practiceLimit: 100, mockLimit: 2, questionsPerMock: 50, practiceUsed: 0, mocksUsed: 0 } };
    },
    async contact(id, owner, phone) {
      calls.push(["contact", id, owner, phone]);
      if (owner !== id) return { status: "PHONE_REQUIRED", language: "en", errorKey: "registration.ownContact" };
      return { status: "COMPLETED", language: "en", grant: { accessLevel: "FREE", practiceLimit: 100, mockLimit: 2, questionsPerMock: 50, practiceUsed: 0, mocksUsed: 0 } };
    },
    async current() { return { status: "COMPLETED", language: "en", grant: { accessLevel: "FREE", practiceLimit: 100, mockLimit: 2, questionsPerMock: 50, practiceUsed: 0, mocksUsed: 0 } }; },
  };
  return store;
}

function fakeTelegram() {
  const calls = [];
  return {
    calls,
    async sendMessage(...args) { calls.push(["send", ...args]); },
    async answerCallbackQuery(id) { calls.push(["ack", id]); },
    async getBotUsername() { return "AirlineStagingTestBot"; },
  };
}

function makeRequest(update, secret = env.TELEGRAM_WEBHOOK_SECRET) {
  return new Request("https://edge.invalid/functions/v1/telegram-webhook", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Telegram-Bot-Api-Secret-Token": secret },
    body: JSON.stringify(update),
  });
}

const privateMessage = (text) => ({ update_id: 9, message: { from: { id: 77, is_bot: false }, chat: { id: 77, type: "private" }, text } });

test("rejects missing and incorrect webhook secrets before touching storage", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const handler = createTelegramWebhookHandler({ env, store, telegram });
  const missing = await handler(makeRequest(privateMessage("/start"), ""));
  const wrong = await handler(makeRequest(privateMessage("/start"), "incorrect"));
  assert.equal(missing.status, 403);
  assert.equal(wrong.status, 403);
  assert.equal(store.calls.length, 0);
});

test("fails closed if a required Edge secret is missing", async () => {
  const handler = createTelegramWebhookHandler({ env: { ...env, TELEGRAM_WEBHOOK_SECRET: "" }, store: fakeStore(), telegram: fakeTelegram() });
  assert.equal((await handler(makeRequest(privateMessage("/start")))).status, 503);
});

test("accepts an authorized /start and sends the same language buttons", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const logs = [];
  const handler = createTelegramWebhookHandler({ env, store, telegram, logger: { info: (entry) => logs.push(entry), warn: (entry) => logs.push(entry) } });
  const response = await handler(makeRequest(privateMessage("/start")));
  assert.equal(response.status, 200);
  assert.deepEqual(store.calls[0], ["start", "77"]);
  assert.equal(telegram.calls[0][1], "77");
  assert.match(telegram.calls[0][2], /Welcome to Airline Exam Prep/);
  assert.deepEqual(telegram.calls[0][3].inline_keyboard[0].map((button) => button.callback_data), ["lang:en", "lang:am"]);
  assert.match(logs.join(" "), /telegram_update/);
  assert.doesNotMatch(logs.join(" "), /staging-test-token|staging_test_secret|0912345678/);
});

test("language and exam callbacks preserve callback IDs and show contact-share request", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const handler = createTelegramWebhookHandler({ env, store, telegram });
  const languageUpdate = {
    update_id: 10,
    callback_query: { id: "callback-1", data: "lang:am", from: { id: 77 }, message: { chat: { id: 77, type: "private" } } },
  };
  assert.equal((await handler(makeRequest(languageUpdate))).status, 200);
  assert.deepEqual(telegram.calls[0], ["ack", "callback-1"]);
  assert.deepEqual(store.calls[0], ["language", "77", "am"]);
  assert.equal(telegram.calls[1][3].inline_keyboard[0][0].callback_data, "exam:1");

  const examUpdate = {
    update_id: 11,
    callback_query: { id: "callback-2", data: "exam:1", from: { id: 77 }, message: { chat: { id: 77, type: "private" } } },
  };
  assert.equal((await handler(makeRequest(examUpdate))).status, 200);
  assert.deepEqual(store.calls[1], ["exam", "77", "1"]);
  assert.equal(telegram.calls.at(-1)[3].keyboard[0][0].request_contact, true);
  assert.equal(telegram.calls.at(-1)[3].one_time_keyboard, false);
});

test("registered users can open the language selector and switch both ways without changing profile data", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const profile = { language: "en", examName: "Staging Test Exam", examNameAm: "", grant: {
    accessLevel: "FREE", practiceLimit: 10, practiceUsed: 3, mockLimit: 2, mocksUsed: 1,
  } };
  store.current = async () => ({ status: "COMPLETED", ...profile });
  store.language = async (id, language) => {
    store.calls.push(["language", id, language]);
    profile.language = language;
    return { status: "COMPLETED", ...profile };
  };
  const handler = createTelegramWebhookHandler({ env, store, telegram });
  const callback = (update_id, data) => ({ update_id, callback_query: {
    id: `language-${update_id}`, data, from: { id: 77 }, message: { chat: { id: 77, type: "private" } },
  } });

  assert.equal((await handler(makeRequest(callback(301, "lang:choose")))).status, 200);
  assert.equal(telegram.calls.at(-1)[2], "Choose your language.");
  assert.deepEqual(telegram.calls.at(-1)[3].inline_keyboard.flat().map((button) => button.callback_data), ["lang:en", "lang:am", "s:home"]);

  assert.equal((await handler(makeRequest(callback(302, "lang:am")))).status, 200);
  assert.equal(profile.language, "am");
  assert.match(telegram.calls.at(-1)[2], /የአሁኑ ፈተና/);
  assert.ok(telegram.calls.at(-1)[3].inline_keyboard.flat().some((button) => button.text === "ቋንቋ ቀይር"));

  // A retried selection of the already-selected language is an idempotent update.
  assert.equal((await handler(makeRequest(callback(303, "lang:am")))).status, 200);
  assert.equal(profile.language, "am");
  assert.equal((await handler(makeRequest(callback(304, "lang:en")))).status, 200);
  assert.equal(profile.language, "en");
  assert.equal(profile.examName, "Staging Test Exam");
  assert.deepEqual(profile.grant, { accessLevel: "FREE", practiceLimit: 10, practiceUsed: 3, mockLimit: 2, mocksUsed: 1 });
  assert.deepEqual(store.calls.filter((call) => call[0] === "language").map((call) => call[2]), ["am", "am", "en"]);
});

test("typed phone is passed to registration and another account's contact does not register", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const handler = createTelegramWebhookHandler({ env, store, telegram });
  assert.equal((await handler(makeRequest(privateMessage("+251912345678")))).status, 200);
  assert.deepEqual(store.calls.slice(0, 2), [["manual", "77"], ["manualPhone", "77", "+251912345678"]]);
  assert.match(telegram.calls[0][2], /Welcome back/);

  const contactUpdate = {
    update_id: 12,
    message: { from: { id: 77 }, chat: { id: 77, type: "private" }, contact: { user_id: 88, phone_number: "0912345678" } },
  };
  assert.equal((await handler(makeRequest(contactUpdate))).status, 200);
  assert.deepEqual(store.calls.at(-1), ["contact", "77", "88", "0912345678"]);
  assert.match(telegram.calls.at(-1)[2], /not your Telegram-linked phone number/);
  assert.equal(telegram.calls.at(-1)[3].keyboard[0][0].request_contact, true);
});

test("own Telegram contact completes once and mock actions route to the mock flow", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const mockCalls = [];
  const mock = { async callback(...args) { mockCalls.push(args); } };
  const handler = createTelegramWebhookHandler({ env, store, mock, telegram });
  const update = {
    update_id: 13,
    message: { from: { id: 77 }, chat: { id: 77, type: "private" }, contact: { user_id: 77, phone_number: "0912345678" } },
  };
  assert.equal((await handler(makeRequest(update))).status, 200);
  assert.match(telegram.calls[0][2], /Phone number verified successfully/);
  assert.equal(telegram.calls[0][3].remove_keyboard, true);
  assert.match(telegram.calls[1][2], /Welcome back/);
  assert.equal(telegram.calls[1][3].inline_keyboard[0][0].callback_data, "p:menu");

  const mockUpdate = { update_id: 14, callback_query: { id: "callback-3", data: "m:intro", from: { id: 77 }, message: { chat: { id: 77, type: "private" } } } };
  assert.equal((await handler(makeRequest(mockUpdate))).status, 200);
  assert.deepEqual(mockCalls, [["77", "77", "m:intro", "14"]]);
  assert.equal(store.calls.filter((call) => call[0] === "contact").length, 1);
});

test("completed users' free text does not send a misleading registration notice", async () => {
  const store = fakeStore();
  store.manualPhoneInput = async () => null;
  store.current = async () => { throw new Error("Unnecessary profile query"); };
  const telegram = fakeTelegram();
  const handler = createTelegramWebhookHandler({ env, store, telegram });
  assert.equal((await handler(makeRequest(privateMessage("Synthetic practice note")))).status, 200);
  assert.equal(telegram.calls.length, 0);
});

test("an unavailable practice module requests retry instead of accepting the update", async () => {
  const handler = createTelegramWebhookHandler({ env, store: fakeStore(), telegram: fakeTelegram(), logger: {} });
  const update = {
    update_id: 15,
    callback_query: { id: "practice-unavailable", data: "s:progress", from: { id: 77 }, message: { chat: { id: 77, type: "private" } } },
  };
  const response = await handler(makeRequest(update));
  assert.equal(response.status, 503);
  assert.equal(response.headers.get("retry-after"), "5");
});

test("practice callbacks use the Edge practice flow with Telegram update ID", async () => {
  const store = fakeStore();
  const telegram = fakeTelegram();
  const calls = [];
  const practice = { async callback(...args) { calls.push(args); } };
  const handler = createTelegramWebhookHandler({ env, store, practice, telegram });
  const update = {
    update_id: 123,
    callback_query: { id: "practice-callback", data: "p:menu", from: { id: 77 }, message: { chat: { id: 77, type: "private" } } },
  };
  assert.equal((await handler(makeRequest(update))).status, 200);
  assert.deepEqual(calls, [["77", "77", "p:menu", "123"]]);
  assert.deepEqual(telegram.calls[0], ["ack", "practice-callback"]);
});

test("mock and payment updates route to their Edge flows without using the Spring bot path", async () => {
  const store=fakeStore(),telegram=fakeTelegram(),mockCalls=[],paymentCalls=[],practiceCalls=[];
  store.manualPhoneInput=async()=>null;
  const mock={async callback(...args){mockCalls.push(args);}};
  const payment={async callback(...args){paymentCalls.push(["callback",...args]);},async message(...args){paymentCalls.push(["message",...args]);}};
  const practice={async callback(...args){practiceCalls.push(args);}};
  const handler=createTelegramWebhookHandler({env,store,telegram,mock,payment,practice});
  const callback=(update_id,data)=>({update_id,callback_query:{id:"cb-"+update_id,data,from:{id:77},message:{chat:{id:77,type:"private"}}}});
  assert.equal((await handler(makeRequest(callback(201,"m:intro")))).status,200);
  assert.equal((await handler(makeRequest(callback(202,"s:mh:0")))).status,200);
  assert.equal((await handler(makeRequest(callback(203,"pay:status")))).status,200);
  assert.equal((await handler(makeRequest(callback(204,"s:progress")))).status,200);
  assert.deepEqual(mockCalls.map(x=>x[2]),["m:intro","s:mh:0"]);
  assert.deepEqual(paymentCalls[0],["callback","77","77","pay:status","203"]);
  assert.deepEqual(practiceCalls,[["77","77","s:progress","204"]]);
  const receipt={update_id:205,message:{from:{id:77},chat:{id:77,type:"private"},photo:[{file_id:"f",file_unique_id:"u",width:2,height:2,file_size:10}]}};
  assert.equal((await handler(makeRequest(receipt))).status,200);
  assert.equal(paymentCalls.at(-1)[0],"message");
  assert.equal(paymentCalls.at(-1).at(-1),"205");
});

test("rejects malformed updates and ignores non-private messages", async () => {
  const store = fakeStore();
  const handler = createTelegramWebhookHandler({ env, store, telegram: fakeTelegram() });
  const malformed = await handler(new Request("https://edge.invalid", { method: "POST", headers: { "X-Telegram-Bot-Api-Secret-Token": env.TELEGRAM_WEBHOOK_SECRET }, body: "{" }));
  assert.equal(malformed.status, 400);
  const group = { update_id: 1, message: { from: { id: 77 }, chat: { id: -7, type: "group" }, text: "/start" } };
  assert.equal((await handler(makeRequest(group))).status, 200);
  assert.equal(store.calls.length, 0);
});

test("health responds without disclosing settings and checks database readiness", async () => {
  const store = fakeStore();
  const handler = createTelegramWebhookHandler({ env, store, telegram: fakeTelegram() });
  const response = await handler(new Request("https://edge.invalid/", { method: "GET" }));
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok" });
});
