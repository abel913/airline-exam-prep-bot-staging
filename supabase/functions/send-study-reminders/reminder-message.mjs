import { message } from "../telegram-webhook/domain.mjs";

export const INACTIVITY_MS = 8 * 60 * 60 * 1000;

export function isValidSchedulerPayload(body) {
  return typeof body === "string" && (body.trim() === "" || body.trim() === "{}");
}

export function campaignStatus({ enabled, startAt, endAt }, now = Date.now()) {
  if (enabled !== true || !startAt || !endAt) return "OFF";
  const start = new Date(startAt).getTime(), end = new Date(endAt).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return "OFF";
  if (now < start) return "SCHEDULED";
  if (now > end) return "EXPIRED";
  return "ACTIVE";
}

export function isReminderEligible(user, now = Date.now()) {
  if (!user || user.study_reminders_enabled !== true || user.reminder_delivery_blocked_at) return false;
  if (user.reminder_retry_after && new Date(user.reminder_retry_after).getTime() > now) return false;
  const activity = new Date(user.last_user_activity_at).getTime();
  const sent = user.last_reminder_sent_at ? new Date(user.last_reminder_sent_at).getTime() : null;
  return Number.isFinite(activity) && now - activity >= INACTIVITY_MS
    && (sent === null || (Number.isFinite(sent) && now - sent >= INACTIVITY_MS));
}

const button = (language, key, callback_data) => ({ text: message(language, key), callback_data });
const menu = language => [button(language, "student.menu", "s:home")];

export function buildReminder(user) {
  const language = user.preferred_language === "am" ? "am" : "en";
  const rows = [];
  let text;
  const free = user.access_level !== "LIFETIME";
  const practiceAvailable = !free || Number(user.practice_limit) > Number(user.practice_used);
  const mockAvailable = !free || Number(user.mock_limit) > Number(user.mocks_used);
  const hasFreePractice = free && practiceAvailable;

  if (user.payment_pending_review && !practiceAvailable && !mockAvailable && !user.active_mock_id) return null;

  if (!user.exam_active) {
    text = message(language, "reminder.chooseExam");
    rows.push([button(language, "student.switchExam", "s:exams")], menu(language));
  } else if (user.active_mock_id) {
    text = message(language, "reminder.activeMock");
    rows.push([button(language, "reminder.resumeMock", `m:o:${user.active_mock_id}:-1`)], menu(language));
  } else if (user.expired_mock_id) {
    text = message(language, "reminder.expiredMock");
    if (mockAvailable) rows.push([button(language, "reminder.startMock", "m:intro")]);
    rows.push([button(language, "reminder.mockHistory", "s:mh:0")], menu(language));
  } else if (user.payment_pending_review) {
    if (!practiceAvailable && !mockAvailable) return null;
    text = message(language, "reminder.pendingReview");
    if (practiceAvailable) rows.push([button(language, user.has_practice ? "reminder.continuePractice" : "reminder.startPractice", user.has_practice ? "p:resume" : "p:menu")]);
    if (mockAvailable) rows.push([button(language, "reminder.startMock", "m:intro")]);
    rows.push(menu(language));
  } else if (user.payment_request_id) {
    text = message(language, "reminder.payment");
    rows.push([button(language, "reminder.continuePayment", "pay:status")],
      [button(language, "payment.cancel", `pay:cancel:${user.payment_request_id}`)], menu(language));
  } else if (user.has_practice) {
    text = message(language, "reminder.practice");
    if (hasFreePractice) text += `\n\n${message(language, "reminder.freeAccess")}`;
    if (practiceAvailable) rows.push([button(language, "reminder.continuePractice", "p:resume")]);
    if (mockAvailable) rows.push([button(language, "reminder.startMock", "m:intro")]);
    rows.push(menu(language));
  } else {
    text = message(language, "reminder.newLearner");
    if (hasFreePractice) text += `\n\n${message(language, "reminder.freeAccess")}`;
    if (practiceAvailable) rows.push([button(language, "reminder.startPractice", "p:menu")]);
    if (mockAvailable) rows.push([button(language, "reminder.startMock", "m:intro")]);
    if (!practiceAvailable && !mockAvailable && free) rows.push([button(language, "payment.upgrade", "pay:open")]);
    rows.push(menu(language));
  }
  return { text, reply_markup: { inline_keyboard: rows } };
}
