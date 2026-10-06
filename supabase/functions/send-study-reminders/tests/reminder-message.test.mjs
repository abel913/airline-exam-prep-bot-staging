import test from "node:test";
import assert from "node:assert/strict";
import { buildReminder, campaignStatus, INACTIVITY_MS, isReminderEligible, isValidSchedulerPayload } from "../reminder-message.mjs";
import { createStudyReminderFlow } from "../../telegram-webhook/study-reminder-flow.mjs";

const base = (patch = {}) => ({
  preferred_language: "en", study_reminders_enabled: true, last_user_activity_at: "2026-01-01T00:00:00Z",
  last_reminder_sent_at: null, reminder_delivery_blocked_at: null, reminder_retry_after: null,
  exam_active: true, access_level: "FREE", practice_limit: 10, practice_used: 1, mock_limit: 2, mocks_used: 0,
  active_mock_id: null, expired_mock_id: null, has_practice: false, payment_request_id: null,
  ...patch,
});
const now = Date.parse("2026-01-01T08:00:00Z");

test("scheduler accepts an empty body or pg_net's default empty JSON object only", () => {
  assert.equal(isValidSchedulerPayload(""), true);
  assert.equal(isValidSchedulerPayload("{}"), true);
  assert.equal(isValidSchedulerPayload(" \n{} \n"), true);
  assert.equal(isValidSchedulerPayload('{"batch":1000}'), false);
  assert.equal(isValidSchedulerPayload("null"), false);
});

test("campaign status enforces OFF, scheduled, active, and expired windows", () => {
  const now = Date.parse("2026-10-10T12:00:00Z");
  assert.equal(campaignStatus({ enabled: false, startAt: "2026-10-10T00:00:00Z", endAt: "2026-10-11T00:00:00Z" }, now), "OFF");
  assert.equal(campaignStatus({ enabled: true, startAt: "2026-10-10T13:00:00Z", endAt: "2026-10-11T00:00:00Z" }, now), "SCHEDULED");
  assert.equal(campaignStatus({ enabled: true, startAt: "2026-10-10T00:00:00Z", endAt: "2026-10-11T00:00:00Z" }, now), "ACTIVE");
  assert.equal(campaignStatus({ enabled: true, startAt: "2026-10-09T00:00:00Z", endAt: "2026-10-10T11:59:59Z" }, now), "EXPIRED");
  assert.equal(campaignStatus({ enabled: true, startAt: null, endAt: null }, now), "OFF");
});

test("inactivity eligibility is strict at 8 hours and respects 8-hour successful-send throttle", () => {
  assert.equal(INACTIVITY_MS, 8 * 60 * 60 * 1000);
  assert.equal(isReminderEligible(base({ last_user_activity_at: new Date(now - INACTIVITY_MS + 1).toISOString() }), now), false);
  assert.equal(isReminderEligible(base({ last_user_activity_at: new Date(now - INACTIVITY_MS).toISOString() }), now), true);
  assert.equal(isReminderEligible(base({ last_user_activity_at: new Date(now - INACTIVITY_MS * 2).toISOString(),
    last_reminder_sent_at: new Date(now - INACTIVITY_MS + 1).toISOString() }), now), false);
  assert.equal(isReminderEligible(base({ last_user_activity_at: new Date(now - INACTIVITY_MS * 2).toISOString(),
    last_reminder_sent_at: new Date(now - INACTIVITY_MS).toISOString() }), now), true);
});

test("disabled and permanently blocked users are never eligible; retry_after is honored", () => {
  const inactive = { last_user_activity_at: new Date(now - INACTIVITY_MS * 2).toISOString() };
  assert.equal(isReminderEligible(base({ ...inactive, study_reminders_enabled: false }), now), false);
  assert.equal(isReminderEligible(base({ ...inactive, reminder_delivery_blocked_at: now - 1 }), now), false);
  assert.equal(isReminderEligible(base({ ...inactive, reminder_retry_after: new Date(now + 1).toISOString() }), now), false);
  assert.equal(isReminderEligible(base({ ...inactive, reminder_retry_after: new Date(now).toISOString() }), now), true);
});

test("active mock reminder only offers existing resume action; expired mock never offers resume", () => {
  const active = buildReminder(base({ active_mock_id: "42" }));
  const buttons = active.reply_markup.inline_keyboard.flat();
  assert.match(active.text, /mock exam is still waiting/i);
  assert.ok(buttons.some((b) => b.callback_data === "m:o:42:-1"));
  assert.equal(buttons.some((b) => b.callback_data === "m:intro"), false);
  const expired = buildReminder(base({ expired_mock_id: "42" }));
  assert.match(expired.text, /has expired/i);
  assert.ok(expired.reply_markup.inline_keyboard.flat().some((b) => b.callback_data === "m:intro"));
  assert.equal(expired.reply_markup.inline_keyboard.flat().some((b) => b.callback_data.startsWith("m:o:")), false);
});

test("practice continuation, first practice, free user access, and premium copy use existing callbacks", () => {
  const practice = buildReminder(base({ has_practice: true }));
  assert.ok(practice.reply_markup.inline_keyboard.flat().some((b) => b.callback_data === "p:resume"));
  assert.match(practice.text, /practice available/i);
  const newLearner = buildReminder(base());
  assert.ok(newLearner.reply_markup.inline_keyboard.flat().some((b) => b.callback_data === "p:menu"));
  assert.ok(newLearner.reply_markup.inline_keyboard.flat().some((b) => b.callback_data === "m:intro"));
  const premium = buildReminder(base({ access_level: "LIFETIME", has_practice: true }));
  assert.doesNotMatch(premium.text, /upgrade|practice available/i);
});

test("payment waiting for proof gets continue/cancel; pending review falls back to learning", () => {
  const waiting = buildReminder(base({ payment_request_id: "81" }));
  const buttons = waiting.reply_markup.inline_keyboard.flat();
  assert.match(waiting.text, /waiting for payment confirmation/i);
  assert.ok(buttons.some((b) => b.callback_data === "pay:status"));
  assert.ok(buttons.some((b) => b.callback_data === "pay:cancel:81"));
  const review = buildReminder(base({ has_practice: true, payment_request_id: null, payment_pending_review: true }));
  assert.match(review.text, /while your upgrade request is being reviewed/i);
  assert.doesNotMatch(review.text, /waiting for payment confirmation/i);
  assert.ok(review.reply_markup.inline_keyboard.flat().every((button) => !button.callback_data.startsWith("pay:")));
  assert.equal(buildReminder(base({ payment_pending_review: true, practice_limit: 0, mock_limit: 0 })), null);
});

test("pending review never receives a payment action and skips users with no learning access", () => {
  const review = buildReminder(base({ payment_request_id: "81", payment_pending_review: true, has_practice: false }));
  const buttons = review.reply_markup.inline_keyboard.flat();
  assert.match(review.text, /while your upgrade request is being reviewed/i);
  assert.ok(buttons.every((button) => !button.callback_data.startsWith("pay:")));
  assert.equal(buildReminder(base({ payment_pending_review: true, practice_limit: 0, practice_used: 0, mock_limit: 0, mocks_used: 0 })), null);
});

test("inactive exam routes to exam picker and Amharic messages/buttons are localized", () => {
  const inactive = buildReminder(base({ exam_active: false }));
  assert.ok(inactive.reply_markup.inline_keyboard.flat().some((b) => b.callback_data === "s:exams"));
  const am = buildReminder(base({ preferred_language: "am", has_practice: true }));
  assert.match(am.text, /ልምምድ/);
  assert.ok(am.reply_markup.inline_keyboard.flat().some((b) => b.text === "ልምምድ ቀጥል"));
});

test("preference UI toggles only reminder state and shows localized status", async () => {
  const state = { language: "am", enabled: true, exam: "staging-test", progress: 4, payment: "PENDING_REVIEW" };
  const store = {
    async preference() { return { language: state.language, enabled: state.enabled }; },
    async setEnabled(_id, value) { state.enabled = value; return { language: state.language, enabled: value }; },
  };
  const sent = [];
  const flow = createStudyReminderFlow(store, { async sendMessage(...args) { sent.push(args); } });
  assert.equal(await flow.callback("10", "10", "s:settings"), true);
  assert.match(sent.at(-1)[1], /በርተዋል/);
  assert.equal(await flow.callback("10", "10", "r:off"), true);
  assert.equal(state.enabled, false);
  assert.equal(state.exam, "staging-test");
  assert.equal(state.progress, 4);
  assert.equal(state.payment, "PENDING_REVIEW");
  assert.match(sent.at(-2)[1], /ጠፍተዋል/);
  assert.equal(await flow.callback("10", "10", "unrelated"), false);
});
