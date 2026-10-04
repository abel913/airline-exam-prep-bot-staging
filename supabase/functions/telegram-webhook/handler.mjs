import {
  completedMenu,
  constantTimeTextEqual,
  examKeyboard,
  languageKeyboard,
  message,
  phoneKeyboard,
  requiredConfiguration,
} from "./domain.mjs";

const MAX_BODY_BYTES = 256 * 1024;
const LONG_MAX = 9223372036854775807n;

function json(status, body = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

async function boundedBody(request) {
  const length = Number(request.headers.get("content-length"));
  if (Number.isFinite(length) && length > MAX_BODY_BYTES) return null;
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const parts = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        return null;
      }
      parts.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.byteLength;
  }
  return bytes;
}

function safeId(value) {
  return Number.isSafeInteger(value) && value > 0 ? String(value) : null;
}

function validUpdateId(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

async function sendView(chatId, view, telegram) {
  const language = view.language === "am" ? "am" : "en";
  if (view.errorKey && view.status !== "PHONE_REQUIRED") {
    await telegram.sendMessage(chatId, message(language, view.errorKey), { remove_keyboard: true });
  }
  switch (view.status) {
    case "LANGUAGE_REQUIRED":
      await telegram.sendMessage(chatId, message(language, "registration.language"), languageKeyboard());
      return;
    case "EXAM_TYPE_REQUIRED":
      if (!view.exams?.length) {
        await telegram.sendMessage(chatId, message(language, "registration.noExams"), { remove_keyboard: true });
      } else {
        await telegram.sendMessage(chatId, message(language, "registration.exam"), examKeyboard(view.exams, language));
      }
      return;
    case "PHONE_REQUIRED":
      await telegram.sendMessage(chatId, message(language, view.errorKey ?? "registration.phone"), phoneKeyboard(language));
      return;
    case "COMPLETED": {
      const menu = completedMenu(view);
      await telegram.sendMessage(chatId, menu.text, menu.reply_markup);
      return;
    }
    default:
      throw new Error("INVALID_REGISTRATION_STATE");
  }
}

async function completedUserNotice(chatId, view, telegram) {
  if (view?.status === "COMPLETED") {
    const language = view.language === "am" ? "am" : "en";
    await telegram.sendMessage(chatId, message(language, "phase3.unsupported"));
    return true;
  }
  if (view) await sendView(chatId, view, telegram);
  return false;
}

function updateType(update) {
  if (update.callback_query && typeof update.callback_query === "object") return "callback_query";
  if (update.message && typeof update.message === "object") {
    if (update.message.contact && typeof update.message.contact === "object") return "contact";
    if (typeof update.message.text === "string") return "message_text";
    return "message_other";
  }
  return "other";
}

export function createTelegramWebhookHandler({ env, store, practice, mock, payment, telegram, logger = console }) {
  return async function handleRequest(request) {
    if (request.method === "GET") {
      const config = requiredConfiguration(env);
      if (!config.ok || !config.webhookConfigured) return json(503, { status: "unavailable" });
      try {
        await store.healthCheck();
        return json(200, { status: "ok" });
      } catch {
        return json(503, { status: "unavailable" });
      }
    }
    if (request.method !== "POST") return json(405, { status: "method_not_allowed" });

    const config = requiredConfiguration(env);
    if (!config.webhookConfigured) return json(503, { status: "unavailable" });
    const supplied = request.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
    if (supplied.length > 256 || !constantTimeTextEqual(config.webhookSecret, supplied)) {
      return new Response(null, { status: 403, headers: { "cache-control": "no-store" } });
    }
    if (!config.ok) return json(503, { status: "unavailable" });

    const bytes = await boundedBody(request);
    if (bytes === null) return new Response(null, { status: 413 });
    let update;
    try {
      update = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    } catch {
      return new Response(null, { status: 400 });
    }
    if (!update || typeof update !== "object" || Array.isArray(update) || !validUpdateId(update.update_id)) {
      return new Response(null, { status: 400 });
    }

    const type = updateType(update);
    const log = (level, outcome) => {
      const item = JSON.stringify({ event: "telegram_update", updateId: update.update_id, type, outcome });
      if (level === "warn") logger.warn?.(item);
      else logger.info?.(item);
    };

    try {
      const callback = update.callback_query && typeof update.callback_query === "object" ? update.callback_query : null;
      if (callback && typeof callback.id === "string" && callback.id.length > 0) {
        try {
          await telegram.answerCallbackQuery(callback.id);
        } catch {
          log("warn", "callback_ack_failed");
        }
      }

      const messageObject = callback ? callback.message : update.message;
      const sender = callback ? callback.from : messageObject?.from;
      const chat = messageObject?.chat;
      const senderId = safeId(sender?.id);
      const chatId = safeId(chat?.id);
      if (!senderId || !chatId || senderId !== chatId || chat?.type !== "private" || sender?.is_bot === true) {
        log("info", "ignored_non_private_or_invalid_sender");
        return new Response(null, { status: 200 });
      }

      if (callback) {
        const data = typeof callback.data === "string" ? callback.data : "";
        if (data === "lang:en" || data === "lang:am") {
          await sendView(chatId, await store.language(senderId, data.slice(5)), telegram);
        } else if (/^exam:[1-9][0-9]{0,17}$/.test(data) && BigInt(data.slice(5)) <= LONG_MAX) {
          await sendView(chatId, await store.exam(senderId, data.slice(5)), telegram);
        } else if (data === "s:home" || /^m:/.test(data) || /^s:mh:[0-9]{1,6}$/.test(data)) {
          if (mock) await mock.callback(chatId, senderId, data, String(update.update_id));
          else await completedUserNotice(chatId, await store.current(senderId), telegram);
        } else if (/^pay:/.test(data)) {
          if (payment) await payment.callback(chatId, senderId, data, String(update.update_id));
          else await completedUserNotice(chatId, await store.current(senderId), telegram);
        } else if (/^(?:p:|s:(?:help|progress|ph:[0-9]{1,6}|weak:[0-9]{1,6}|recommend)$)/.test(data)) {
          if (practice) {
            await practice.callback(chatId, senderId, data, String(update.update_id));
          } else {
            throw new Error("PRACTICE_NOT_CONFIGURED");
          }
        } else if (/^(s:|m:|pay:)/.test(data)) {
          await completedUserNotice(chatId, await store.current(senderId), telegram);
        }
        log("info", "handled");
        return new Response(null, { status: 200 });
      }

      if (messageObject?.contact && typeof messageObject.contact === "object") {
        const contact = messageObject.contact;
        const forwarded = messageObject.forward_origin !== undefined || messageObject.forward_date !== undefined
          || messageObject.forward_from !== undefined || messageObject.forward_from_chat !== undefined
          || messageObject.is_automatic_forward === true;
        const ownerId = !forwarded ? safeId(contact.user_id) : null;
        const view = await store.contact(senderId, ownerId, typeof contact.phone_number === "string" ? contact.phone_number : null);
        if (view.status === "COMPLETED") {
          const language = view.language === "am" ? "am" : "en";
          await telegram.sendMessage(chatId, message(language, "registration.ready"), { remove_keyboard: true });
        }
        await sendView(chatId, view, telegram);
        log("info", view.status === "COMPLETED" ? "registration_completed_or_replayed" : "contact_checked");
        return new Response(null, { status: 200 });
      }

      const text = typeof messageObject?.text === "string" ? messageObject.text : "";
      const command = text.split(/\s+/, 1)[0];
      if (command === "/start") {
        await sendView(chatId, await store.start(senderId), telegram);
      } else if (command.startsWith("/start@")) {
        const requestedUsername = command.slice(7);
        if (/^[A-Za-z0-9_]+$/.test(requestedUsername)
          && requestedUsername.toLowerCase() === (await telegram.getBotUsername()).toLowerCase()) {
          await sendView(chatId, await store.start(senderId), telegram);
        }
      } else if (!command.startsWith("/") && (text.trim() || payment)) {
        if (payment) await payment.message(chatId, senderId, messageObject, String(update.update_id));
        const phonePrompt = await store.manualPhoneInput(senderId);
        if (phonePrompt) {
          await sendView(chatId, phonePrompt, telegram);
        }
      }

      log("info", "handled");
      return new Response(null, { status: 200 });
    } catch (error) {
      // Error objects can include SQL values or token-bearing URLs; log only their category.
      log("warn", error instanceof Error ? `retryable_${error.name}` : "retryable_error");
      return new Response(null, { status: 503, headers: { "retry-after": "5", "cache-control": "no-store" } });
    }
  };
}
