import test from "node:test";
import assert from "node:assert/strict";
import {
  constantTimeTextEqual,
  decodePhoneIdentityKey,
  hmacSha256Hex,
  normalizeEthiopianPhone,
  phoneIdentity,
  phoneKeyFingerprint,
  requiredConfiguration,
  completedMenu,
  languageChangeKeyboard,
  message,
} from "../domain.mjs";

const fixtureKey = "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=";
const expectedPhoneHmac = "58c00037a5d53df2352ca0fd6e3588a8a948ae94d0c9813d0b83e721288979cf";
const expectedFingerprint = "daaf96344e5e0f133fb7c34f0fca6701718e634d6ddb8ee355d499c186ff97a7";

test("Ethiopian phone formats match Spring's canonicalization", () => {
  for (const input of ["0912345678", "+251912345678", "251912345678", "09 1234 5678", "+251 (91) 234-5678"]) {
    assert.equal(normalizeEthiopianPhone(input), "+251912345678");
  }
  assert.equal(normalizeEthiopianPhone("0712345678"), "+251712345678");
});

test("rejects phone formats Spring rejects", () => {
  for (const input of ["", "123", "+254912345678", "0812345678", "09123456789", "+2510912345678", "0912abc678", "++251912345678", null, "9".repeat(33)]) {
    assert.throws(() => normalizeEthiopianPhone(input), { message: "INVALID_ETHIOPIAN_PHONE" });
  }
});

test("HMAC matches the deterministic Spring compatibility fixture", async () => {
  const key = decodePhoneIdentityKey(fixtureKey);
  assert.equal(key.length, 35);
  assert.equal(await phoneIdentity(key, normalizeEthiopianPhone("0912345678")), expectedPhoneHmac);
  assert.equal(await phoneIdentity(key, normalizeEthiopianPhone("+251912345678")), expectedPhoneHmac);
  assert.equal(await phoneKeyFingerprint(key), expectedFingerprint);
  assert.equal(await hmacSha256Hex(key, "phone:+251712345678"), "4556fa102b5a987a681c712421fd4b5f43f2e1574ea71b8524d02421cae95c13");
});

test("HMAC identity key must be valid Base64 with at least 32 bytes", () => {
  assert.throws(() => decodePhoneIdentityKey("not_base64!"));
  assert.throws(() => decodePhoneIdentityKey("dGVzdA=="));
});

test("webhook secret comparison checks equal, unequal, and unequal-length values", () => {
  assert.equal(constantTimeTextEqual("staging_secret-123", "staging_secret-123"), true);
  assert.equal(constantTimeTextEqual("staging_secret-123", "staging_secret-124"), false);
  assert.equal(constantTimeTextEqual("staging_secret-123", "short"), false);
});

test("runtime configuration requires a transaction pooler URL and independent secrets", () => {
  const base = {
    TELEGRAM_BOT_TOKEN: "test-token",
    TELEGRAM_WEBHOOK_SECRET: "test-secret",
    PHONE_IDENTITY_HMAC_KEY: fixtureKey,
    DATABASE_URL: "postgresql://test:placeholder@localhost:6543/postgres",
  };
  assert.equal(requiredConfiguration(base).ok, true);
  assert.equal(requiredConfiguration({ ...base, DATABASE_URL: base.DATABASE_URL.replace(":6543", ":5432") }).ok, false);
  assert.equal(requiredConfiguration({ ...base, TELEGRAM_WEBHOOK_SECRET: "" }).webhookConfigured, false);
  assert.equal(requiredConfiguration({ ...base, PHONE_IDENTITY_HMAC_KEY: "dGVzdA==" }).ok, false);
});

test("completed main menu exposes Change Language in English and Amharic", () => {
  const view = (language) => ({ language, examName: "Staging Test Exam", examNameAm: "", grant: {
    accessLevel: "FREE", practiceLimit: 10, practiceUsed: 2, mockLimit: 2, mocksUsed: 1,
  } });
  const en = completedMenu(view("en")).reply_markup.inline_keyboard.flat();
  const am = completedMenu(view("am")).reply_markup.inline_keyboard.flat();
  assert.ok(en.some((button) => button.text === "Change Language" && button.callback_data === "lang:choose"));
  assert.ok(am.some((button) => button.text === "ቋንቋ ቀይር" && button.callback_data === "lang:choose"));
  assert.ok(en.some((button) => button.text === "Settings" && button.callback_data === "s:settings"));
  assert.ok(am.some((button) => button.text === "ቅንብሮች" && button.callback_data === "s:settings"));
});

test("study reminder translations exist in both supported languages", () => {
  for (const key of ["reminder.title", "reminder.statusOn", "reminder.statusOff", "reminder.turnOn", "reminder.turnOff",
    "reminder.savedOn", "reminder.savedOff", "reminder.activeMock", "reminder.expiredMock", "reminder.practice",
    "reminder.newLearner", "reminder.payment", "reminder.chooseExam", "reminder.resumeMock", "reminder.startMock",
    "reminder.mockHistory", "reminder.continuePractice", "reminder.startPractice", "reminder.continuePayment",
    "reminder.pendingReview"]) {
    assert.notEqual(message("en", key), key, `missing English translation: ${key}`);
    assert.notEqual(message("am", key), key, `missing Amharic translation: ${key}`);
  }
});

test("language selector offers both languages and returns to menu", () => {
  const keyboard = languageChangeKeyboard("am").inline_keyboard.flat();
  assert.deepEqual(keyboard.map((button) => button.callback_data), ["lang:en", "lang:am", "s:home"]);
  assert.equal(keyboard[2].text, message("am", "student.menu"));
  assert.equal(message("en", "student.chooseLanguage"), "Choose your language.");
  assert.notEqual(message("am", "student.chooseLanguage"), "student.chooseLanguage");
});

test("pending review message includes the exact English promise and no approval promise", () => {
  assert.equal(message("en", "payment.status.PENDING_REVIEW"),
    "Pending manual review.\n\nWe will review your request within 24 hours. If there is any issue, we will contact you directly.\n\nYour evidence is saved and cannot be edited.");
  const am = message("am", "payment.status.PENDING_REVIEW");
  assert.notEqual(am, "payment.status.PENDING_REVIEW");
  assert.match(am, /24/);
  assert.doesNotMatch(am, /ይጸድቃል|approved/i);
});
