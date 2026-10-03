import { completedMenu, message } from "./domain.mjs";
import { PracticeError } from "./practice-service.mjs";

function button(language, key, callbackData, fallback = key) {
  const value = { text: message(language, key) === key ? fallback : message(language, key), callback_data: callbackData };
  if (new TextEncoder().encode(callbackData).length > 64) throw new Error("CALLBACK_DATA_TOO_LONG");
  return value;
}

function home(language) {
  return [[button(language, "student.menu", "s:home", "Menu")]];
}

function allowance(student) {
  if (student.accessLevel === "LIFETIME") return message(student.language, "student.unlimited");
  return message(student.language, "student.remaining", Math.max(0, student.practiceLimit - student.practiceUsed), student.practiceLimit);
}

function displayQuestion(view) {
  const { student, delivery, question } = view;
  const lang = student.language;
  let text = `${question.text}\n\n${question.options.map((option) => `${String.fromCharCode(65 + option.position)}. ${option.text}`).join("\n")}`;
  const rows = [];
  if (view.answered) {
    const correct = question.options.find((option) => option.correct);
    text += `\n\n${message(lang, view.correct ? "practice.correct" : "practice.incorrect")}`;
    text += `\n${message(lang, "student.correctAnswer", String.fromCharCode(65 + correct.position))}`;
    text += `\n${question.explanation}\n${allowance(student)}`;
    rows.push([
      button(lang, "practice.nextAction", `p:n:${delivery.id}`),
      button(lang, "practice.review", `p:r:${delivery.id}`),
    ]);
  } else {
    rows.push(question.options.map((option) => ({
      text: String.fromCharCode(65 + option.position),
      callback_data: `p:a:${delivery.id}:${option.position}`,
    })));
    rows.push([button(lang, "practice.skip", `p:n:${delivery.id}`)]);
  }
  rows.push([button(lang, "student.progress", "s:progress")]);
  rows.push(...home(lang));
  return { text, reply_markup: { inline_keyboard: rows } };
}

function categoryView(result, page = 0) {
  const lang = result.student.language;
  const rows = [[button(lang, "practice.all", "p:c:0")]];
  for (const category of result.categories.slice(0, 20)) {
    const name = lang === "am" && category.nameAm ? category.nameAm : category.name;
    const callback = `p:c:${category.id}`;
    if (new TextEncoder().encode(callback).length > 64) continue;
    rows.push([{ text: name, callback_data: callback }]);
  }
  if (page > 0) rows.push([button(lang, "student.previous", `p:g:${page - 1}`)]);
  if (result.categories.length > 20) rows.push([button(lang, "student.next", `p:g:${page + 1}`)]);
  rows.push([button(lang, "practice.review", "p:r:0")], ...home(lang));
  return { text: message(lang, "practice.choose"), reply_markup: { inline_keyboard: rows } };
}

function progressView(result) {
  const { student } = result;
  const lang = student.language;
  const practiceAllowance = student.accessLevel === "LIFETIME"
    ? message(lang, "student.unlimited")
    : message(lang, "student.remaining", Math.max(0, student.practiceLimit - student.practiceUsed), student.practiceLimit);
  const mockAllowance = student.accessLevel === "LIFETIME"
    ? message(lang, "student.unlimited")
    : message(lang, "student.remaining", Math.max(0, student.mockLimit - student.mocksUsed), student.mockLimit);
  let text = message(lang, "progress.summary", result.answered, result.correct, result.incorrect, result.accuracy,
    practiceAllowance, result.completed, mockAllowance);
  if (student.accessLevel === "LIFETIME") text += `\n${message(lang, "payment.activeButton")}`;
  for (const category of result.categories.slice(0, 20)) {
    text += `\n${message(lang, "progress.category", category.name, category.correct, category.answered, category.accuracy)}`;
  }
  for (const mock of result.recent) {
    const percentage = mock.total === 0 ? 0 : Math.round(mock.correct * 10000 / mock.total) / 100;
    text += `\n${message(lang, "progress.mock", mock.id, mock.correct, mock.total, percentage)}`;
  }
  const rows = [
    [button(lang, "insights.title", "s:weak:0")],
    [button(lang, "history.practice", "s:ph:0"), button(lang, "history.mock", "s:mh:0")],
    ...home(lang),
  ];
  return { text, reply_markup: { inline_keyboard: rows } };
}

function historyView(result) {
  const lang = result.student.language;
  let text = `${message(lang, "history.practice")}\nUTC\n`;
  const rows = [];
  for (const item of result.rows) {
    text += `${item.date}\n${item.category} — ${message(lang, item.correct ? "practice.correct" : "practice.incorrect")}\n`;
    rows.push([{ text: message(lang, "history.explanation", item.id), callback_data: `p:h:${item.id}` }]);
  }
  if (!result.rows.length) text += message(lang, "history.empty");
  if (result.page > 0) rows.push([button(lang, "student.previous", `s:ph:${result.page - 1}`)]);
  if (result.more) rows.push([button(lang, "student.next", `s:ph:${result.page + 1}`)]);
  rows.push(...home(lang));
  return { text, reply_markup: { inline_keyboard: rows } };
}

function insightsView(result) {
  const lang = result.student.language;
  let text = `${message(lang, "insights.title")}\n${message(lang, "insights.rule")}\n`;
  for (const category of result.rows) {
    text += `${category.name}: ${message(lang, "progress.category", category.name, category.correct, category.answered, category.accuracy)}\n`;
    text += `${message(lang, category.key)}\n`;
  }
  if (!result.rows.length) text += message(lang, "insights.more");
  const rows = [[button(lang, "insights.practice", "s:recommend")]];
  if (result.page > 0) rows.push([button(lang, "student.previous", `s:weak:${result.page - 1}`)]);
  if (result.more) rows.push([button(lang, "student.next", `s:weak:${result.page + 1}`)]);
  rows.push(...home(lang));
  return { text, reply_markup: { inline_keyboard: rows } };
}

function callbackId(value, allowZero = false) {
  const id = BigInt(value);
  if (id > 9223372036854775807n || (!allowZero && id === 0n)) throw new PracticeError("student.invalid");
  return String(id);
}

export function createPracticeFlow(service, telegram) {
  async function send(chatId, view) {
    let text = view.text;
    while (text.length > 3500) {
      let end = 3500;
      if (text.charCodeAt(end - 1) >= 0xd800 && text.charCodeAt(end - 1) <= 0xdbff) end -= 1;
      await telegram.sendMessage(chatId, text.slice(0, end));
      text = text.slice(end);
    }
    await telegram.sendMessage(chatId, text, view.reply_markup);
  }

  async function showError(chatId, language, key) {
    const rows = [];
    if (key === "practice.limit" || key === "practice.empty") rows.push([button(language, "practice.review", "p:r:0")]);
    rows.push(...home(language));
    await telegram.sendMessage(chatId, message(language, key), { inline_keyboard: rows });
  }

  async function callback(chatId, telegramId, data, updateId, profile) {
    const lang = profile.language;
    if (data === "s:home") {
      const grant = {
        accessLevel: profile.accessLevel, practiceLimit: profile.practiceLimit, practiceUsed: profile.practiceUsed,
        mockLimit: profile.mockLimit, mocksUsed: profile.mocksUsed,
      };
      const menu = completedMenu({ language: lang, grant });
      await send(chatId, menu);
      return;
    }
    if (data === "s:help") {
      await telegram.sendMessage(chatId, message(lang, "student.helpText"), { inline_keyboard: home(lang) });
      return;
    }
    if (data === "p:menu") {
      await send(chatId, categoryView(await service.categories(telegramId, 0)));
      return;
    }
    if (data === "s:progress") {
      await send(chatId, progressView(await service.progress(telegramId)));
      return;
    }
    const match = /^(?:p:(g|c|n|r):([0-9]{1,19})|p:a:([0-9]{1,19}):([0-9]{1,2})|p:h:([0-9]{1,19})|s:(ph|weak):([0-9]{1,6})|s:recommend)$/.exec(data);
    if (!match) throw new PracticeError("student.invalid");
    const id = match[2] === undefined ? null : callbackId(match[2], match[1] !== "n");
    if (match[1] === "g") {
      const page = Number(id);
      if (!Number.isSafeInteger(page) || page > 1_000_000) throw new PracticeError("student.invalid");
      await send(chatId, categoryView(await service.categories(telegramId, page), page));
    } else if (match[1] === "c") {
      await send(chatId, displayQuestion(await service.next(telegramId, updateId, { categoryId: id === "0" ? null : id })));
    } else if (match[1] === "n" || match[1] === "r") {
      await send(chatId, displayQuestion(await service.next(telegramId, updateId, {
        previousId: id === "0" ? null : id, review: match[1] === "r",
      })));
    } else if (match[3] !== undefined) {
      await send(chatId, displayQuestion(await service.answer(telegramId, callbackId(match[3]), Number(match[4]))));
    } else if (match[5] !== undefined) {
      await send(chatId, displayQuestion(await service.historyDelivery(telegramId, callbackId(match[5]))));
    } else if (match[6] === "ph") {
      await send(chatId, historyView(await service.history(telegramId, Number(match[7]))));
    } else if (match[6] === "weak") {
      await send(chatId, insightsView(await service.insights(telegramId, Number(match[7]))));
    } else {
      const categoryId = await service.recommendation(telegramId);
      if (categoryId === null) {
        await telegram.sendMessage(chatId, message(lang, "insights.fallback"));
        await send(chatId, categoryView(await service.categories(telegramId, 0)));
      } else {
        await send(chatId, displayQuestion(await service.next(telegramId, updateId, { categoryId })));
      }
    }
  }

  return {
    async callback(chatId, telegramId, data, updateId) {
      let language = "en";
      try {
        const maintenanceLanguage = await service.maintenanceLanguage(telegramId);
        if (maintenanceLanguage) {
          await telegram.sendMessage(chatId, message(maintenanceLanguage, "maintenance.message"));
          return;
        }
        const profile = await service.profile(telegramId);
        language = profile.language;
        await callback(chatId, telegramId, data, updateId, profile);
      } catch (error) {
        // Storage and Telegram failures must reach the webhook's retry response.
        if (!(error instanceof PracticeError)) throw error;
        await showError(chatId, language, error.key);
      }
    },
  };
}
