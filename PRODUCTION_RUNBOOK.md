# Free test / beta operations

This is a FREE TEST/BETA deployment, not a high-availability or paid production
service. Existing temporary test credentials are retained unchanged. Replace them
before a serious private/paid launch using the procedures below. Never include
credentials in Git, build arguments, screenshots, support logs or chat.

## Architecture and deployment

GitHub `main` → Render Docker web service (Free, Frankfurt/Europe) → Java 21 /
Spring Boot → Supabase PostgreSQL Session Pooler over TLS. Telegram sends HTTPS
POSTs to `/api/telegram/webhook`. The same services and update handler serve local
polling and deployed webhooks. The admin website is part of this application.

Create a Render Web Service from `airline-exam-prep-bot`, branch `main`, runtime
Docker, Dockerfile `./Dockerfile`, build context `.`, region Frankfurt, Free
instance, health path `/actuator/health`. Use one instance. Render supplies PORT;
the application listens on `0.0.0.0` and prioritizes PORT over SERVER_PORT.
Set the active profile to prod, enable Telegram, select WEBHOOK. Use existing
private credentials in Render Environment/Secrets. Do not supply secrets at build
time. No Render API credential or dashboard automation is required by this repo.

Before the first deployment, open Supabase Integrations → Data API → Overview and turn
off **Enable Data API**. This JDBC-only application does not use PostgREST,
GraphQL, Supabase client keys or Realtime. Do not add application tables to
Realtime publications. Existing migrations rely on Spring Security and JDBC
authorization, not Supabase RLS policies. Leaving `public` exposed through the
Data API can bypass those controls. Verify the setting privately before startup.
See [Supabase API security](https://supabase.com/docs/guides/api/securing-your-api)
and [disabling the Data API](https://supabase.com/docs/guides/api/securing-your-api#disable-the-data-api).

The Docker build uses the Maven wrapper and runs the isolated test suite during
package. Java 21 JRE runs as UID/GID 10001. The context and COPY statements include
only build inputs; `.env`, Git, dumps, logs and local target artifacts are excluded.
The heap uses 50% of container memory, leaving space for metaspace, native buffers
and threads. The prod Tomcat pool caps at 20 threads. Monitor actual Render memory
and cold startup; local verification is not proof of Free instance capacity.

## Environment inventory (names only)

Required for the first deployed bot startup:

```text
SPRING_PROFILES_ACTIVE
DB_HOST
DB_PORT
DB_NAME
DB_USERNAME
DB_PASSWORD
TELEGRAM_BOT_ENABLED
TELEGRAM_MODE
TELEGRAM_BOT_TOKEN
TELEGRAM_WEBHOOK_SECRET
PHONE_IDENTITY_HMAC_KEY
ADMIN_BOOTSTRAP_USERNAME
ADMIN_BOOTSTRAP_PASSWORD
```

Notification destination and host/local overrides:

```text
TELEGRAM_ADMIN_ID
PORT
SERVER_PORT
SESSION_COOKIE_SECURE
```

TELEGRAM_ADMIN_ID is optional for application startup but required for the admin
notification check. It never authenticates a web admin. PORT is provided by Render;
SERVER_PORT is the local fallback. SESSION_COOKIE_SECURE remains a development
override; prod forces Secure regardless. Existing variable names remain supported.
No `.env` auto-loader is added: set process environment securely, or use the
existing ignored local configuration workflow. Do not point automated tests at
Supabase. Test-only fixtures are fictional and isolated.

Admin bootstrap requires a 3–64 character username using letters, digits, dot,
underscore or hyphen and a password of at least 16 characters, at most 72 UTF-8
bytes. It uses BCrypt cost 12, creates only the first admin and never overwrites an
existing password. Remove bootstrap variables after successful initial login.
Retain the password privately. The HMAC is a stable Base64 key of at least 32 bytes.
The webhook secret must be 1–256 characters from letters, digits, `_` and `-`.
Do not regenerate any current credential for this test deployment.

## Database preflight and migrations

Use the Supabase **Session Pooler** connection details, port 5432, database postgres.
The JDBC URL composes host, port and database, with a separate username/password.
It requires `sslmode=require`; timeouts bound connection and socket stalls.
This encrypts the client-to-pooler connection but does not verify its hostname.
`verify-full` requires the provider's trusted root configuration and should be
tested separately before promotion; no unverified certificate/truststore change
is applied during this phase. [Supabase connections](https://supabase.com/docs/guides/database/connecting-to-postgres)
describes session pooling for persistent IPv4 clients.

With PostgreSQL client tools on PATH and the existing private environment loaded:

```powershell
.\scripts\check-production-db.ps1
```

This read-only check requires TLS and checks public tables/Flyway history. An empty
schema is ready. Existing tables without history require investigation. Review
existing history and compare expected migrations before deploying a reused DB.
`pg_stat_ssl` reports the pooler's backend session, not client-to-pooler TLS;
successful libpq `sslmode=require` is the client transport check.

Flyway alone applies V1–V13. No Phase 9 migration is needed. V1–V13 are immutable;
JPA validates schema. Automatic baseline and Flyway clean remain disabled. Never
erase the schema, copy development data, or repair history blindly. If startup
fails, stop repeated deploy attempts, inspect the failed version and transaction
state privately, and forward-fix after understanding it. Existing unexpected
tables cause Flyway to fail closed rather than baseline them automatically.

Hikari uses four maximum connections, one minimum idle, 15-second acquisition,
5-second validation, 15-minute lifetime and idle connection validation every two
minutes while running. This is database pool maintenance, not an HTTP keep-awake
mechanism. Evicted/dead connections are replaced; Render can still sleep. The
domain's short settings lock serializes mutations, so a large pool adds little
benefit. Network calls occur outside database transactions.

## Telegram webhook and local polling

After Render reports health UP, load the existing production/test bot token and
webhook secret into your local process environment without displaying them:

```powershell
.\scripts\configure-telegram-webhook.ps1 -PublicUrl https://YOUR-SERVICE.onrender.com
.\scripts\check-telegram-webhook.ps1
```

The helper verifies getMe, configures the exact route with the secret header,
allows message/callback updates, limits delivery concurrency to one and preserves
pending updates. It reports only safe webhook information. A historical last-error
timestamp can remain after recovery; confirm it is not advancing and pending count
drains when a human sends `/start`. Do not fabricate inbound live updates.

The controller rejects missing, blank, duplicate and incorrect secrets before
reading JSON. It bounds bodies to 256 KiB, compares the secret in constant time,
and rejects malformed envelopes with 400, oversized bodies with 413. Unknown
valid updates are acknowledged and ignored by the shared handler. POST alone is
allowed; only that exact route bypasses CSRF. Browser admin CSRF remains enabled.
Disabled Telegram or POLLING mode has no webhook controller. WEBHOOK mode creates
no polling worker. Startup records the mode without credentials.

Responses wait for the shared handler. There is no volatile background queue that
could acknowledge and then lose a payment or answer on restart. Normal updates
avoid an extra identity request; addressed `/start@bot` resolves/caches identity.
Two concurrent handlers are allowed locally; excess requests get 503. Storage,
transport or shutdown interruption returns 503 so Telegram can retry. Domain
transactions and unique keys prevent repeated consumption, registration, payment,
financial decisions and grants. An uncertain outgoing reply may repeat. Remote
Telegram requests have the existing 40-second limit, so a slow API can delay ACK;
this is an explicit beta limitation, not a guarantee of instantaneous delivery.

For development use the separate development bot with POLLING mode. Before
switching the same bot from WEBHOOK to POLLING, stop its webhook deployment and
call deleteWebhook with pending updates preserved using the environment-based
helper (never call getUpdates while a webhook is installed):

```powershell
. .\scripts\telegram-common.ps1
$null = Invoke-TelegramOperation deleteWebhook @{ drop_pending_updates = $false }
```

Set local TELEGRAM_MODE to POLLING and enable the bot. Never operate two polling
processes or reuse one token simultaneously in two transports. A 409 usually means
another polling process or an installed webhook; check these before retrying.

## Admin, content and live acceptance

Open `https://YOUR-SERVICE.onrender.com/admin/login`, log in privately, check the
dashboard and log out. Prod cookies are Secure, HttpOnly and SameSite=Lax. CSP,
frame denial, no-referrer and MIME protections remain. Only basic health is public;
env, beans, mappings, configprops, heapdump and threaddump are unavailable.

Forwarded headers are deliberately not trusted. Login redirects use relative
locations and secure cookies work through Render TLS termination. Login throttling
uses the direct peer, so users behind a proxy can share the ten-attempt/minute
budget. Do not trust arbitrary X-Forwarded-For to work around it. Review a validated
proxy policy before broader use. HTTPS/HSTS at the Render edge remains a live check.

Verify `/start`, optionally register with your own contact, then create an original
sample exam/category/question through the secured admin, review and publish it.
Practice may report no content and mock may report insufficient content. Do not
create 50 throwaway production questions or reduce entitlements merely for smoke
tests. Payment flags start disabled; keep them disabled unless deliberately testing
with synthetic references and harmless images. Never transfer real money for a
smoke test. Test notifications only to the configured admin identity. With the
existing private TELEGRAM_BOT_TOKEN and TELEGRAM_ADMIN_ID in the process environment:

```powershell
.\scripts\send-test-admin-notification.ps1
```

Confirm that the fixed message arrives in the admin chat. API acceptance alone is
not a human delivery confirmation. The helper prints neither token nor recipient ID.

After a safe Render redeploy, verify admin, registration and workflow records
remain. All durable state is PostgreSQL; receipts are Telegram file IDs. Imports
and downloads use temporary bounded buffers/streams; no upload directory must
survive sleep. Telegram receipt availability still depends on Telegram.

## Backups and isolated restore

Use matching/newer pg_dump client tooling, the existing environment and private
storage. Run before a migration/deploy and after meaningful beta data changes;
if actively testing daily, take a daily copy. The beta recovery point is the last
successful verified backup; there is no promised recovery time or automatic SLA.

```powershell
.\scripts\backup-production-db.ps1
```

The script requires TLS, creates a timestamped custom-format `backups/` archive and
checks its table of contents. It saves application public-schema data/Flyway history,
not Supabase platform-managed schemas, roles, Storage binaries or Telegram receipt
binaries. Secrets go through a temporary child-process environment, never argv.
Backups contain private user/payment/admin-hash data: protect filesystem access,
copy to encrypted private storage, keep the stable HMAC key separately, and never
commit archives. A partial failed archive is not a valid backup. Prefer direct
connections if available; session pooler is the IPv4 fallback, not transaction mode.

Provision a NEW empty local database named `airline_restore_<suffix>` yourself and
set RESTORE_DB_PASSWORD in the process environment for that destination only:

```powershell
.\scripts\restore-isolated-db.ps1 -BackupFile .\backups\YOUR-BACKUP.dump `
  -DestinationHost 127.0.0.1 -DestinationPort 5432 `
  -DestinationDatabase airline_restore_drill -DestinationUsername YOUR_LOCAL_USER
```

Restore refuses non-loopback targets, non-drill database names, and nonempty
destinations. It uses one transaction, no clean/drop and no production credential
fallback. Local TLS is preferred, since disposable local servers may lack TLS.
Verify all Flyway versions/checksums, table counts, admin BCrypt login and restored
entitlements/workflows with Telegram disabled. Supply the matching original HMAC
privately when booting a restored production database. Never restore into Supabase
as a drill. Record actual backup and restore results; listing an archive alone is
not evidence of recoverability.

## Rollback and failures

Record the Phase 9 Git commit and successful Render deploy. Roll back through
Render's deploy history to a known healthy **webhook-capable** Phase 9 image, with
the same environment and schema. Phase 9 changes no database schema. `c9518d2` is
the prior local baseline but supports polling only: it is not a transparent Render
webhook rollback. Returning to it requires stopping the webhook deployment and
deliberately switching transport; prefer a Phase 9 forward fix. Database recovery
starts with a backup and isolated inspection; never apply destructive down SQL.

| Symptom | Check / action |
| --- | --- |
| Build fails | Wrapper download/network, Java 21, failing isolated tests, Docker build log |
| Port/health timeout | PORT binding, prod profile, DB reachability and credentials, Hikari/Flyway startup |
| Authentication failure | Correct session-pooler username/project and private password; no password in JDBC URL |
| Migration failure | Exact version/checksum and schema history; stop retries, no clean/baseline/repair shortcut |
| Webhook 403 | Exact current header secret at Render and setWebhook; no browser session needed |
| Webhook 503 | Database outage, Telegram send failure, shutdown or capacity; inspect safe summaries |
| No Telegram reply | Render sleep, health, pending count/error timestamp, bot identity, webhook URL and mode |
| Admin 403 | Valid login/session, CSRF token and fresh form; secure cookie needs HTTPS |
| Missing receipts | Telegram file availability and authorized download; retry from payment details |
| Memory termination | Render metrics and startup log; preserve native memory headroom, reduce workload or move tier |

Do not enable HTTP wire logs, request-header/body logs, SQL bind logging or debug
dumps with real credentials. Read safe Render logs for Hikari/Flyway/JPA/Tomcat
startup, webhook mode and generic errors. Never paste entire environment/log dumps.

## Future credential rotation

Do not rotate the present test values in Phase 9. Before serious private/paid use:

- Bot token: stop delivery during maintenance, replace via BotFather, update Render
  secret and local secure storage, redeploy, getMe and setWebhook with the current
  webhook secret, then verify pending updates and a real `/start`.
- Webhook secret: coordinate Render secret/redeploy and setWebhook using the new
  secret. Brief mismatches yield retries; preserve pending updates. Verify then
  retire old secret copies.
- Database password: coordinate Supabase credential change with Render/environment,
  redeploy and verify TLS/health; take a backup beforehand. Never store in URL.
- Admin password: changing bootstrap variables does NOT change an existing account.
  There is no self-service password-change UI. During an authorized maintenance
  window, generate BCrypt cost 12 through the existing PasswordEncoder, use a
  parameterized update for the intended admin, record a credential-change audit
  event without the password/hash, restart to invalidate sessions and test login.
  Do not remove the account to force re-bootstrap. Implement/review that controlled
  maintenance operation before launch; no current password change is performed.
- HMAC key: do not casually replace after registrations. Stored phone identities
  cannot be recomputed from raw phones (which are not retained). Rotation requires
  a designed identity migration/reverification plan that preserves duplicate-claim
  prevention and existing access. Back up and restore the matching key with data.

## Support and free-tier limits

Verified dependencies: Java 21, Boot 3.5.16, Framework 6.2.19, Tomcat 10.1.60,
Flyway core/PostgreSQL 11.20.3, pgJDBC 42.7.11. No dependency changes. Boot 3.5.16
is the final OSS release of its line; retain the patched Tomcat override for this
beta and plan supported framework migration/support before serious launch. This
is not an assertion of perpetual security support. See [Spring release notice](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
and [Tomcat advisories](https://tomcat.apache.org/security-10.html).

Render Free sleeps after inactivity and loses local files on restart. Provider
guidance estimates about a minute to wake; actual cold start is a live measurement
still to record. Resource, build, bandwidth and workspace-hour limits can suspend
service. No self-ping, cron ping or keep-alive workaround is included. See
[Render Free limits](https://render.com/docs/free) and [port binding](https://render.com/docs/web-services#port-binding).
Supabase Free also has capacity, inactivity and backup limitations: inspect the
project's current plan/dashboard rather than assuming paid retention or availability.
This setup is deliberately suitable only for free test/beta use.
