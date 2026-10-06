import { PostgresDatabase } from "../telegram-webhook/postgres-database.ts";
import { PostgresStudyReminderSchedulerStore } from "./scheduler-store.ts";
import { buildReminder, isValidSchedulerPayload } from "./reminder-message.mjs";

const secret = Deno.env.get("STUDY_REMINDER_SCHEDULER_SECRET") ?? "";
const token = Deno.env.get("TELEGRAM_BOT_TOKEN") ?? "";
const databaseUrl = Deno.env.get("DATABASE_URL") ?? "";
const db = new PostgresDatabase(() => databaseUrl);
const schedulerStore = new PostgresStudyReminderSchedulerStore(db);
const BATCH_SIZE = boundedInteger(Deno.env.get("STUDY_REMINDER_BATCH_SIZE"), 25, 1, 50);
const CLAIM_TTL_MINUTES = 15;

function boundedInteger(raw: string | undefined, fallback: number, min: number, max: number): number {
  if (!raw || !/^\d+$/.test(raw)) return fallback;
  return Math.max(min, Math.min(max, Number(raw)));
}

function constantTimeEqual(expected: string, supplied: string): boolean {
  const a = new TextEncoder().encode(expected);
  const b = new TextEncoder().encode(supplied);
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

async function telegramSend(chatId: string, text: string, markup: unknown): Promise<{ ok: boolean; status: number; errorCode: number | null; description: string; retryAfter: number | null }> {
  const response = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text, reply_markup: markup }),
  });
  let body: { ok?: boolean; error_code?: number; description?: string; parameters?: { retry_after?: number } } = {};
  try { body = await response.json(); } catch { /* treat malformed response as retryable */ }
  return {
    ok: response.ok && body.ok === true,
    status: response.status,
    errorCode: Number.isInteger(body.error_code) ? body.error_code! : null,
    description: typeof body.description === "string" ? body.description.toLowerCase() : "",
    retryAfter: Number.isInteger(body.parameters?.retry_after) ? body.parameters!.retry_after! : null,
  };
}

async function sendBatch(): Promise<{ claimed: number; sent: number; blocked: number; retry: number; stale: number }> {
  const candidates = await schedulerStore.claimBatch(BATCH_SIZE, CLAIM_TTL_MINUTES);
  let sent = 0, blocked = 0, retry = 0, stale = 0;
  for (const candidate of candidates) {
    const userId = String(candidate.id), claimToken = candidate.claim_token;
    if (!await schedulerStore.beginDelivery(userId, claimToken)) {
      await schedulerStore.finishClaim(userId, claimToken, "retry", 0);
      stale++;
      continue;
    }
    const user = {
      ...candidate,
      active_mock_id: candidate.latest_mock_id !== null
        && (candidate.latest_mock_status === "READY"
          || (candidate.latest_mock_status === "IN_PROGRESS"
            && (!candidate.latest_mock_deadline_at || new Date(candidate.latest_mock_deadline_at).getTime() > Date.now())))
        ? String(candidate.latest_mock_id) : null,
      expired_mock_id: candidate.latest_mock_id !== null
        && (candidate.latest_mock_status === "EXPIRED"
          || (candidate.latest_mock_status === "IN_PROGRESS" && candidate.latest_mock_deadline_at
            && new Date(candidate.latest_mock_deadline_at).getTime() <= Date.now()))
        ? String(candidate.latest_mock_id) : null,
      payment_request_id: candidate.payment_request_id === null ? null : String(candidate.payment_request_id),
    };
    try {
      const result = await telegramSend(String(candidate.telegram_user_id), buildReminder(user).text, buildReminder(user).reply_markup);
      if (result.ok) {
        await schedulerStore.finishClaim(userId, claimToken, "sent");
        sent++;
      } else if (result.status === 403 || (result.status === 400 && /blocked|chat not found|deactivated/i.test(result.description))) {
        await schedulerStore.finishClaim(userId, claimToken, "blocked");
        blocked++;
      } else {
        const retrySeconds = result.status === 429 ? Math.max(1, Math.min(result.retryAfter ?? 60, 86400)) : 3600;
        await schedulerStore.finishClaim(userId, claimToken, "retry", retrySeconds);
        retry++;
      }
    } catch {
      await schedulerStore.finishClaim(userId, claimToken, "retry", 3600);
      retry++;
    }
    // 20 messages/second is below Telegram's standard bot broadcast rate.
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return { claimed: candidates.length, sent, blocked, retry, stale };
}

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return new Response(null, { status: 405 });
  const supplied = request.headers.get("x-study-reminder-secret") ?? "";
  if (!secret || !token || !databaseUrl || !constantTimeEqual(secret, supplied)) {
    return new Response(null, { status: 403, headers: { "cache-control": "no-store" } });
  }
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > 2) return new Response(null, { status: 400 });
  if (!isValidSchedulerPayload(await request.text())) return new Response(null, { status: 400 });
  try {
    const result = await sendBatch();
    return Response.json(result, { headers: { "cache-control": "no-store" } });
  } catch (error) {
    // Deliberately omit database errors, request URLs, and user identifiers.
    console.error("study_reminder_batch_failed", error instanceof Error ? error.name : "unknown_error");
    return Response.json({ status: "unavailable" }, { status: 503, headers: { "cache-control": "no-store" } });
  }
});
