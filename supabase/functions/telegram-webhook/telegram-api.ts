type TelegramEnvelope<T> = { ok: boolean; result?: T; error_code?: number };

export class TelegramApiClient {
  constructor(private readonly token: () => string) {}

  private async call<T>(method: string, body: Record<string, unknown>): Promise<T> {
    const token = this.token();
    if (!token) throw new Error("TELEGRAM_NOT_CONFIGURED");
    const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("TELEGRAM_HTTP_FAILURE");
    const envelope = await response.json() as TelegramEnvelope<T>;
    if (!envelope.ok || envelope.result === undefined) throw new Error("TELEGRAM_API_FAILURE");
    return envelope.result;
  }

  async sendMessage(chatId: string, text: string, replyMarkup?: Record<string, unknown>): Promise<void> {
    await this.call("sendMessage", {
      chat_id: chatId,
      text,
      ...(replyMarkup === undefined ? {} : { reply_markup: replyMarkup }),
    });
  }

  async answerCallbackQuery(callbackQueryId: string): Promise<void> {
    await this.call("answerCallbackQuery", { callback_query_id: callbackQueryId });
  }

  async getBotUsername(): Promise<string> {
    const user = await this.call<{ is_bot?: boolean; username?: string }>("getMe", {});
    if (user.is_bot !== true || !user.username || !/^[A-Za-z0-9_]+$/.test(user.username)) {
      throw new Error("TELEGRAM_BOT_IDENTITY_UNAVAILABLE");
    }
    return user.username;
  }
}
