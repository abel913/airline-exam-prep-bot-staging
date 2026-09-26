# Phase 9 deployment preparation and verification

Baseline: `c9518d2`, main aligned with origin/main at preflight. The initially
reported six modified paths had no content diff (line-ending/index metadata).
Existing work was preserved. Scope is FREE TEST/BETA deployment, not Phase 10.

## Implementation

- Explicit POLLING/WEBHOOK configuration with polling as the development default.
  Enabled webhook uses the same handler/services; disabled mode creates no transport.
- Exact POST webhook route, timing-safe required secret validation before parsing,
  bounded input, controlled invalid-body responses, shared ownership/private-chat
  protections and retryable overload/storage/transport failures. No volatile queue.
- The webhook returns success after handling. An uncertain Telegram reply may
  repeat, while database idempotency protects quota, registration, payment and grants.
  Slow outbound API calls remain a documented latency limitation.
- Prod profile: TLS JDBC composition, four-connection Hikari pool, port override,
  bind-all address, secure cookies and bounded servlet threads. Relative admin
  redirects preserve HTTPS behind Render without trusting forwarded request headers.
- Java 21 multistage Docker build, isolated tests in package, non-root runtime,
  allowlisted build inputs and 50%-of-container-memory heap sizing.
- Environment-only webhook configuration/inspection, TLS database preflight/backup,
  guarded loopback-only empty-database restore, operations runbook and env inventory.
- No dependency change, new schema migration, business-rule change or credential
  rotation. All applied V1–V13 files remain unchanged.

## Local evidence

Final local gates (2026-09-26): **417 distinct Java tests PASS** — 414 automated
tests plus three explicitly invoked PostgreSQL checks. Both the final full test
run and repeated randomized package run (seed 9262026) pass all 414 automated
tests with zero failures, errors or skips. Package succeeds; no remaining observed
flake. PowerShell offline/guard checks and TLS backup/restore are additional
operational checks, not included in the Java test count.

Baseline regression passed 376 tests. Webhook/security/prod-profile checks cover:
missing/blank/incorrect/duplicate secrets, malformed envelopes/JSON, missing fields,
unknown updates, private/group/supergroup boundaries, forwarded contacts, `/start`,
callbacks, addressed commands, payload limits, overload and failed storage/delivery.
Real database-backed replay tests verify one registration/entitlement, one unique
practice charge, one mock charge, one payment and one financial decision/grant.

ProductionProfileTests uses real HTTP on PORT 10000 with an isolated H2 database
and mocked Telegram. It checks health UP, secure cookies, relative login/logout
redirects, browser headers, protected actuator/admin operations, webhook routing,
absence of polling and pool replacement. No automated suite targets Supabase.

Explicit PostgreSQL 18.6 checks use a newly initialized disposable loopback cluster
on port 55439 with a disposable test TLS certificate; no existing credential was
replaced. PaymentPostgresIT and PostgresExamEngineIT pass, applying/validating all
13 migrations while rolling back scenario writes. PostgresConnectionRecoveryIT
terminates only its own idle backend in that disposable cluster and proves Hikari
replaces it. It rejects remote/non-drill targets before fault injection.

The backup script produced a custom-format public-schema archive over TLS. Restore
into a separate empty `airline_restore_phase9` database succeeded with all 13
Flyway entries and settings data. Repeating restore was correctly refused. The
helper skips only the archive's CREATE SCHEMA public entry, since a fresh database
already has that schema; it performs no drop/clean. Restored packaged application
startup validates Flyway, Hikari, JPA and Tomcat and reports health UP. Local smoke
used a 512 MiB JVM memory basis with a 50% heap (256 MiB); observed working set was
about 287 MiB, startup about 13 seconds. These are local observations, not Render
cold-start or container memory guarantees.

A second disposable backup/restore preserved the bootstrapped BCrypt admin as
well. Packaged smoke verifies the original password after restart and restore,
despite a changed bootstrap environment value, together with dashboard access,
logout, cookie protections and relative redirects. This tests only fictional
local credentials and does not replace the live Render admin checkpoint.

Offline PowerShell checks exercise getMe/setWebhook/getWebhookInfo, pending-update
preservation, allowed updates, one delivery connection, output redaction, HTTPS URL
validation, missing-token failure and all script syntax. No real Telegram call is
made by those tests. PostgreSQL script verification uses only disposable data.

## Repairs found by verification

- Preserved record constructor binding after extending Telegram configuration.
- Corrected a test configuration inheritance conflict that disabled webhook mode
  in the production-profile test; real HTTP now verifies the enabled controller.
- Corrected TLS preflight evidence: pg_stat_ssl behind a pooler describes the
  pooler/backend hop; successful libpq sslmode=require verifies client TLS.
- Restored application data into an already-existing empty public schema without
  trying to recreate/drop that schema.
- Corrected PostgreSQL fault-injection PID binding to integer.
- Fixed absolute HTTP admin redirects found during packaged reverse-proxy smoke;
  prod now emits relative redirects and tests login/dashboard/logout over real HTTP.

## Initial external handoff (superseded by live follow-up below)

Using existing ignored local credentials, the read-only Supabase check confirmed
encrypted Session Pooler connectivity and an empty public schema. No Supabase
migration or application data write was performed. Initial production migrations
must be performed by Flyway during the Render deployment. Before that, disable
Supabase's unused Data API through its dashboard as described in the runbook.

Docker CLI exists, but its Linux engine pipe is absent. Starting Docker Desktop
and retrying outside the sandbox did not make the engine available; docker build
could not run. Render's Docker build is therefore the required remaining image
integration gate. No Docker success or image-secret scan is claimed; Dockerfile
and context exclusion rules were reviewed statically.

Render service creation, Frankfurt region, Free instance, HTTPS URL, live startup,
Supabase Flyway application, observed cold start, webhook registration/getMe,
live `/start`, admin notifications and live admin checks remain pending the stated
human deployment checkpoint. Bot token and admin destination are not available in
local process/user/machine environments; enter the existing values privately in
Render. Do not send them in chat. Live backup/restore is not yet claimed; the local
disposable drill passed. No live content/payment/registration record was created.

This phase must not be marked complete or ready for Phase 10 until the requested
live acceptance gates are resolved. See PRODUCTION_RUNBOOK.md for exact actions,
rollback constraints, unsupported Boot-line limitation and future credential work.

## Live follow-up: 2026-09-26

After deployment, the full suite and package were run again: each passes all 414
automated tests with zero failures, errors or skips. The extended PowerShell
checks pass for the fixed-text notification helper. No application Java/config
change was needed after the successful Render deployment.

The user deployed https://airline-exam-prep-bot.onrender.com and confirmed the
Supabase Data API is off. HTTPS health returns UP. Live database inspection confirms
V1–V13 applied successfully. The deployed healthy application supplies Render
build/runtime evidence; local Docker remains unavailable and Render image layers
were not independently inspected. No development data was copied into Supabase.

Live checks pass for private admin login/dashboard/logout, session invalidation,
Secure/HttpOnly/SameSite cookies, CSP, authenticated mutation rejection without
CSRF, anonymous admin/receipt/audit/settings protection, and sensitive actuator
endpoint rejection. Missing and invalid webhook secrets return 403. Health lists
liveness/readiness group names without sensitive details.

The user confirmed the webhook helper succeeded and then reported START WORKED.
The helper invokes getMe, setWebhook and getWebhookInfo; those operations are
user-confirmed rather than independently repeated because the real bot token is
unavailable to this local session. A fixed-text, environment-based admin notification
helper is now provided. No token or admin destination was requested in chat.

A real Supabase public-schema backup was taken over TLS and its archive verified.
It restored into a new isolated loopback database with all 13 successful Flyway
records and the admin account. Packaged application startup with Telegram disabled
and the matching existing HMAC key validated migrations and reached health UP.
The disposable service was stopped. The private archive remains ignored.

Latest read-only snapshot: three unfinished registrations, no completed registration,
no published content, and both payment flags false. No fake Telegram user or SQL
registration was created. A one-question original content smoke was prepared but
blocked by automatic approval review; explicit approval was requested before any
live publication. Practice/mock checks remain limited by available content.

Remaining human checks: safe Render restart and persistence verification, actual
Free/Frankfurt configuration confirmation, dashboard log review, admin notification
delivery, and optional own-contact registration/practice. Render cold-start time
is not measured. No live payment/receipt check was performed; automated evidence
remains authoritative for those workflows. Phase 9 is not yet declared complete.
