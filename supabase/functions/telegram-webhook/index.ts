import { createTelegramWebhookHandler } from "./handler.mjs";
import { PostgresRegistrationStore } from "./postgres-store.ts";
import { PostgresDatabase } from "./postgres-database.ts";
import { PostgresPracticeStore } from "./practice-store.ts";
import { PracticeService } from "./practice-service.mjs";
import { createPracticeFlow } from "./practice-flow.mjs";
import { TelegramApiClient } from "./telegram-api.ts";

const env = {
  TELEGRAM_BOT_TOKEN: Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "",
  TELEGRAM_WEBHOOK_SECRET: Deno.env.get("TELEGRAM_WEBHOOK_SECRET") ?? "",
  PHONE_IDENTITY_HMAC_KEY: Deno.env.get("PHONE_IDENTITY_HMAC_KEY") ?? "",
  DATABASE_URL: Deno.env.get("DATABASE_URL") ?? "",
};

const database = new PostgresDatabase(() => env.DATABASE_URL);
const store = new PostgresRegistrationStore(database, () => env.PHONE_IDENTITY_HMAC_KEY);
const practiceStore = new PostgresPracticeStore(database);
const practice = new PracticeService(practiceStore);
const telegram = new TelegramApiClient(() => env.TELEGRAM_BOT_TOKEN);

Deno.serve(createTelegramWebhookHandler({ env, store, practice: createPracticeFlow(practice, telegram), telegram }));
