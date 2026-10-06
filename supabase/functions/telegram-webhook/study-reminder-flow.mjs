import { message } from "./domain.mjs";

const button = (language, key, callback_data) => ({ text: message(language, key), callback_data });

function view(preference) {
  const { language, enabled } = preference;
  return {
    text: message(language, "reminder.title") + "\n\n" + message(language, enabled ? "reminder.statusOn" : "reminder.statusOff"),
    reply_markup: { inline_keyboard: [
      [button(language, enabled ? "reminder.turnOff" : "reminder.turnOn", enabled ? "r:off" : "r:on")],
      [button(language, "student.menu", "s:home")],
    ] },
  };
}

export function createStudyReminderFlow(store, telegram) {
  return {
    async callback(chatId, telegramId, data) {
      if (!(["s:settings", "r:on", "r:off"].includes(data))) return false;
      const preference = data === "s:settings"
        ? await store.preference(telegramId)
        : await store.setEnabled(telegramId, data === "r:on");
      if (!preference) {
        await telegram.sendMessage(chatId, message("en", "student.register"));
        return true;
      }
      if (data === "r:on" || data === "r:off") {
        await telegram.sendMessage(chatId, message(preference.language, preference.enabled ? "reminder.savedOn" : "reminder.savedOff"));
      }
      const screen = view(preference);
      await telegram.sendMessage(chatId, screen.text, screen.reply_markup);
      return true;
    },
  };
}
