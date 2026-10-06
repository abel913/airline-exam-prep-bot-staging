import { createTelegramWebhookHandler } from "./handler.mjs";
import { PostgresRegistrationStore } from "./postgres-store.ts";
import { PostgresDatabase } from "./postgres-database.ts";
import { PostgresPracticeStore } from "./practice-store.ts";
import { PracticeService } from "./practice-service.mjs";
import { createPracticeFlow } from "./practice-flow.mjs";
import { PostgresMockStore } from "./mock-store.ts";
import { MockService } from "./mock-service.mjs";
import { createMockFlow } from "./mock-flow.mjs";
import { PostgresPaymentStore } from "./payment-store.ts";
import { PaymentService } from "./payment-service.mjs";
import { createPaymentFlow } from "./payment-flow.mjs";
import { TelegramApiClient } from "./telegram-api.ts";
import { PostgresStudyReminderStore } from "./study-reminder-store.ts";
import { createStudyReminderFlow } from "./study-reminder-flow.mjs";

const env = {
  TELEGRAM_BOT_TOKEN: Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "",
  TELEGRAM_WEBHOOK_SECRET: Deno.env.get("TELEGRAM_WEBHOOK_SECRET") ?? "",
  PHONE_IDENTITY_HMAC_KEY: Deno.env.get("PHONE_IDENTITY_HMAC_KEY") ?? "",
  DATABASE_URL: Deno.env.get("DATABASE_URL") ?? "",
  TELEGRAM_ADMIN_ID: Deno.env.get("TELEGRAM_ADMIN_ID") ?? "",
};

const database = new PostgresDatabase(() => env.DATABASE_URL);
const store = new PostgresRegistrationStore(database, () => env.PHONE_IDENTITY_HMAC_KEY);
const practiceStore = new PostgresPracticeStore(database);
const practice = new PracticeService(practiceStore);
const mockStore = new PostgresMockStore(database);
const mock = new MockService(mockStore);
const paymentStore = new PostgresPaymentStore(database);
const payment = new PaymentService(paymentStore,{adminId:env.TELEGRAM_ADMIN_ID});
const telegram = new TelegramApiClient(() => env.TELEGRAM_BOT_TOKEN);
const reminderStore = new PostgresStudyReminderStore(database);
const reminders = createStudyReminderFlow(reminderStore, telegram);

Deno.serve(createTelegramWebhookHandler({ env, store, practice: createPracticeFlow(practice, telegram),
  mock: createMockFlow(mock, telegram), payment: createPaymentFlow(payment, telegram), reminders, telegram }));
