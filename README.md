# Airline Exam Preparation Bot

## Compressed Phase 10: product insights

Built on verified Phase 9 baseline `a58e997`. See [PHASE10_REVIEW.md](PHASE10_REVIEW.md)
for the implementation audit and actual verification/deployment status.

- Telegram now offers Practice History, Mock History, Category Insights and
  Practice Weak Areas. Histories show five records per page and only the sender's
  own data. Practice review uses the delivered version, even after archive/edit.
  Mock history includes prepared, active, completed and zero-answer expired exams;
  zero-answer expiry still withholds answer-key review and consumes no allowance.
- Progress keeps first-answer accuracy and free allowances; lifetime users see
  unlimited access. Category insights require five unique first answers per
  category. Below 60% suggests more practice; at least 80% is a stronger area.
  Recommendations choose eligible unanswered weak-category content with variety
  by least-recent delivery, falling back to normal practice when data/content is
  insufficient. No AI, public rankings or invasive tracking is involved.
- Existing question difficulty is preserved. Optional versioned tags accept up
  to eight comma-separated labels, each 1–32 letters/numbers/spaces/hyphens.
  Unicode NFKC and lowercase normalization merge duplicates. Admin question
  filters include difficulty and exact tag and retain filters across pages.
- CSV/XLSX import accepts both the original headers and the new optional `tags`
  column. The downloaded template includes it. Quote comma-containing tags in CSV.
  Existing difficulty validation and all import limits remain in force.
- `/admin/analytics` provides UTC today/7-day/30-day/all-time aggregates and links
  to paginated question, category, registration, content, mock, payment, student
  and outbox reports. Active students have practice answers or mock preparation,
  opening, answers or completion during the window. Counts show zero when empty.
- Question summaries explicitly combine historical versions, separating first
  practice answers from final completed-mock answers and unanswered mock items.
  Option distribution is per version. Quality signals require ten answers and
  never change difficulty, publication or content automatically.
- Authenticated aggregate CSV exports preserve UTF-8/Amharic and neutralize
  spreadsheet formulas. They include at most 500 rows; use filters to narrow the
  report. `X-Export-Truncated` identifies truncation. No phone, HMAC, Telegram ID,
  receipt or transaction reference is exported. Student pages use internal IDs.
- Maintenance controls are under Analytics and operations, backed by existing
  settings and audited CSRF-protected POSTs. The admin dashboard displays a banner.
  Health/admin remain available. Telegram receives a localized retry-later response
  without executing the requested business action. Saved state remains; existing
  mock deadlines continue. Disable maintenance to resume normal interaction.
- Outbox status is read-only here. Existing audited retry on secured payment
  details remains available; analytics never recreates payment events.

V14 is additive: optional tag metadata, maintenance default false, and query
indexes. V1–V13 and dependencies remain unchanged. Analytics uses on-demand SQL,
25-row admin pages and no background collector/cache. Hosting remains FREE TEST/BETA;
temporary credentials and the documented Spring Boot support limitation remain.


## Compressed Phase 9: free test deployment

See [PRODUCTION_RUNBOOK.md](PRODUCTION_RUNBOOK.md) for Render Docker / Supabase
Session Pooler deployment, the exact environment inventory, secured Telegram
webhooks, database preflight, backups, isolated restore, rollback and future
credential rotation. [PHASE9_REVIEW.md](PHASE9_REVIEW.md) records verification and
pending live checks. This is FREE TEST/BETA infrastructure with existing temporary
test credentials, not a paid-production availability commitment.

Production uses the `prod` profile and `TELEGRAM_MODE=WEBHOOK`; local development
continues with `TELEGRAM_MODE=POLLING`. Do not run both for one bot. PORT overrides
SERVER_PORT. Before deploying, disable Supabase's unused Data API and run the
read-only schema check. Secrets belong only in process environment, existing
ignored local configuration or Render secrets; Docker never copies local `.env`.

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
docker build -t airline-exam-prep-bot:phase9 .
# With existing private environment loaded securely:
.\scripts\check-production-db.ps1
.\scripts\configure-telegram-webhook.ps1 -PublicUrl https://YOUR-SERVICE.onrender.com
.\scripts\check-telegram-webhook.ps1
.\scripts\backup-production-db.ps1
```

No new dependency or migration is introduced. V1–V13 remain unchanged. Earlier
phase sections below are historical implementation/verification records; their
deployment exclusions describe their own phase boundaries.

One Java 21 / Spring Boot application for Telegram exam preparation and a
Thymeleaf admin website. [PROJECT_SPEC.md](PROJECT_SPEC.md) defines the product rules.

## Historical scope: compressed Phase 8

- Private-chat registration: /start → English or Amharic → active exam type →
  share your own Telegram contact → registration and free entitlement.
- PostgreSQL registration state survives restarts. Repeated commands, callbacks,
  and same-user contacts do not recreate accounts or entitlements.
- Free snapshots initially grant 100 unique practice questions, 2 mock exams,
  and 50 questions per mock, without expiration. These are independent allowances.
- Admin login, dashboard, settings, exam types, and categories.
- Administrative changes retain actor, timestamp, and before/after values.
- Question bank, immutable published versions, lifecycle review, source rights,
  pool eligibility, and staged CSV/XLSX imports.
- Student practice, unique usage counting, progress, resumable mocks, optional timers,
  frozen questions, scoring, and answer review.
- Manual payments, secured review, lifetime grants, notifications, and audit.
- Security and UX hardening, adversarial and end-to-end tests, durable retry recovery,
  and repeated regression verification. See [PHASE8_REVIEW.md](PHASE8_REVIEW.md).
  Production deployment remains future work.

## Requirements

JDK 21, Maven Wrapper (Maven 3.9.16), PostgreSQL 18. Spring Boot 3.5.16 manages
dependencies; Flyway core and the PostgreSQL module are aligned at 11.20.3.
Embedded Tomcat is explicitly patched to 10.1.60.
H2 and Spring Security test support are test-only dependencies.

## Configuration

Supply environment variables to the process starting the application. Neither
Spring Boot nor the wrapper automatically reads .env. Real .env files are ignored;
.env.example contains placeholders only.

| Variable | Default / requirement |
| --- | --- |
| DB_HOST | 127.0.0.1 |
| DB_PORT | 5432; configurable |
| DB_NAME | airline_exam_bot |
| DB_USERNAME | airline_exam_user |
| DB_PASSWORD | Required for normal PostgreSQL startup |
| SERVER_PORT | 8080; use 8081 temporarily if occupied |
| TELEGRAM_BOT_ENABLED | false |
| TELEGRAM_BOT_TOKEN | Required only when Telegram is enabled |
| PHONE_IDENTITY_HMAC_KEY | Base64 encoding of at least 32 random bytes; required when Telegram is enabled |
| ADMIN_BOOTSTRAP_USERNAME | Optional initial admin username, 3–64 letters/numbers/underscore/dot/hyphen |
| ADMIN_BOOTSTRAP_PASSWORD | Optional initial strong password, at least 16 characters and at most 72 UTF-8 bytes |

Never put actual values into source, SQL, documentation, command arguments, logs,
or tracked files. Read passwords/tokens through secure input, then set process
environment variables:

```powershell
$dbPasswordInput = Read-Host 'Local PostgreSQL password' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbPasswordInput).Password
Remove-Variable dbPasswordInput
$env:DB_HOST = '127.0.0.1'
$env:DB_PORT = '5432'
$env:DB_NAME = 'airline_exam_bot'
$env:DB_USERNAME = 'airline_exam_user'
$env:SERVER_PORT = '8081' # Optional, when another application owns 8080
.\mvnw.cmd spring-boot:run
```

Do not stop or reconfigure another application to free its port.

## Phone identity and key management

Accepted Ethiopian mobile formats are 09XXXXXXXX, 07XXXXXXXX,
+2519XXXXXXXX, +2517XXXXXXXX, and the equivalent 251 forms without a plus.
Spaces, parentheses, and hyphens are accepted as separators. Other country
codes, unsupported prefixes, incorrect lengths, extensions, and letters are
rejected. Canonical identity uses +251 followed by nine digits starting in 7 or 9.

Only a contact whose user_id equals the Telegram sender is accepted. Registration
is restricted to a private chat whose ID matches that sender. Typed phone numbers
and forwarded contacts belonging to others cannot complete registration.
Only Telegram ID, chosen language/exam, registration timestamps, and HMAC identity
are persisted; raw phone, Telegram names, usernames, and contact payloads are not.

Use a cryptographically random HMAC key and keep it in secure persistent storage
outside the repository. Supply the SAME key on every restart. A stored keyed
fingerprint rejects a changed key instead of silently permitting fresh claims.
Do not rotate or discard it without a designed identity migration and secure
backup. Account transfers and duplicate-phone recovery remain manual product
decisions; this phase never transfers access or grants a second free offer.

For this Windows development environment, generated local credentials can be
retained in a Windows DPAPI-encrypted file outside the repository:
%LOCALAPPDATA%\AirlineExamBot\development-secrets.clixml.
Only the Windows account/machine that encrypted it can normally decrypt it.
This is local development storage, not a production secret-management system.

If that local encrypted file has been provisioned, load it without printing values:

```powershell
$localSecrets = Import-Clixml (Join-Path $env:LOCALAPPDATA 'AirlineExamBot\development-secrets.clixml')
$env:PHONE_IDENTITY_HMAC_KEY = [System.Net.NetworkCredential]::new('', $localSecrets.PhoneHmacKey).Password
$env:ADMIN_BOOTSTRAP_USERNAME = $localSecrets.AdminUsername
$env:ADMIN_BOOTSTRAP_PASSWORD = [System.Net.NetworkCredential]::new('', $localSecrets.AdminPassword).Password
Remove-Variable localSecrets
```

## Admin bootstrap and usage

When the admin table is empty and both bootstrap values are supplied, startup
creates one BCrypt-hashed admin. Missing credentials create no default account.
When an admin already exists, startup never resets its password, even if the
bootstrap environment changes. Remove bootstrap credentials from the environment
after initial provisioning; retain them securely for login.

Open /admin/login. Authenticated administrators can manage:

- Overview: total users, completed registrations, active exams, categories, and current offer.
- Application settings: free limits, mock size, lifetime price/currency,
  payment flags, and support information.
- Exam types and categories: create, list, edit, activate, and deactivate.

Deactivation preserves foreign-key references and history. Inactive exam types
are not offered for registration; an unfinished selection is rechecked before
completion. There are no delete routes. Categories belong to existing exam types.
English names are required; optional Amharic names fall back to English.
Codes use lowercase letters, digits, and hyphens; category codes are unique within
their exam type.

Settings updates affect future registrations only. Existing grants are never
recalculated. Payment flags govern new manual requests. Selected requests may finish evidence submission; pending requests remain reviewable.

Security uses sessions, BCrypt, CSRF-protected POST mutations/logout, generic
login failures, and admin authorization. GET /actuator/health remains public
without database details. Other actuator endpoints and unrelated routes remain
denied. Production deployment must use HTTPS and secure session cookies.

## Telegram

Set TELEGRAM_BOT_ENABLED=true, the token, and the stable phone HMAC key, then
restart. The [Telegram Bot API](https://core.telegram.org/bots/api) client checks
getMe and polls message and callback_query updates. Callback queries are
acknowledged. All transport calls occur after database transactions complete.

Send /start (or /start@YourBotUsername). It resumes saved progress. Pick a
language and active exam type, then press the contact-sharing button. A completed
user sees their saved access summary; no new grant is created.

If no active exam types exist, create one through admin and send /start again.
No exam names are hard-coded into Telegram code.

One short database lock on the settings singleton serializes registration writes,
admin taxonomy changes, and offer updates. Unique constraints provide a second
line of protection. This deliberately simple approach suits the initial workload;
it performs no Telegram network calls while holding the lock.

Polling keeps an in-memory offset, skips duplicates in that process, backs off
on failures, honors retry_after, and stops through the application lifecycle.
Persistent registration makes retries/restarts safe for business state.
Exactly-once outgoing messages are not promised: after a failed/uncertain send,
send /start to resume. No fake incoming-update endpoint is exposed.

Run only one poller for a token. API 401 means authentication failed; 409 means
another poller or webhook conflicts. The application never deletes a webhook.
Never enable JDK HTTP wire logging, which could expose token-bearing URLs.
Optional LOGGING_LEVEL_COM_AIRLINEPREP_BOT_TELEGRAM=DEBUG logs safe transport
events without message contents, phone identities, or chat IDs.

## Tests and database safety

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

Automated tests override database, token, HMAC, and bootstrap configuration with
isolated H2 databases, fake keys, and mocked Telegram transport. They never require
or modify the development database. Coverage includes migration/JPA initialization,
normalization, ownership, duplicate/concurrent registration, rollback, snapshots,
bilingual keyboards, authentication, CSRF, validation, and admin management.
The separate live smoke test verifies the actual PostgreSQL migrations and HTTP/
Telegram transport. H2 alone is not evidence of PostgreSQL compatibility.

Flyway owns schema changes; Hibernate remains ddl-auto=validate. Versioned migrations:

1. V1__settings_and_admin.sql — initial configurable defaults, admins, change history.
2. V2__exam_types_and_categories.sql — managed exam taxonomy.
3. V3__registration_and_entitlements.sql — users, unique phone identity, saved grants.

Never edit applied migrations, clean Flyway, drop schemas/databases, truncate, or
reset development data. Use additive migrations for later changes.
Automatic baselining and Flyway clean are disabled.

Verify health on the chosen port:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8081/actuator/health'
```

Stop the application cleanly using Ctrl+C. Clear process credentials afterward:

```powershell
Remove-Item Env:DB_PASSWORD, Env:TELEGRAM_BOT_TOKEN, Env:PHONE_IDENTITY_HMAC_KEY,
    Env:ADMIN_BOOTSTRAP_PASSWORD, Env:ADMIN_BOOTSTRAP_USERNAME -ErrorAction SilentlyContinue
```

## Question bank and content management

Open **/admin/questions** to create drafts, search, filter, and inspect version
history. Lists use database pagination (20 questions/imports/rows per page,
10 historical versions per page). Filters cover text, exam, category, status,
pool, and rights. Sort by creation or update time. Archived questions are
excluded unless explicitly selected.

The lifecycle is DRAFT → REVIEWED → PUBLISHED → ARCHIVED. Reviewed content can
return to draft. Saving reviewed content also clears review. Incomplete drafts
are allowed; review and publication require an active matching exam/category,
question, explanation, EASY/MEDIUM/HARD difficulty, 2–8 distinct nonblank options,
exactly one correct answer, source type/title, resolved rights, and at least one
pool. Blank option slots are omitted and remaining options preserve order.

Published edits create a new numbered draft version. The previous text,
answer key, explanation, source metadata, eligibility, exam/category names and
IDs, and publication/review timestamps remain preserved. A draft revision removes
the logical question from new selection until published again. Archiving applies
to the logical question and preserves all versions; no delete or restore endpoint
exists. Concurrent stale edits are rejected. Future attempts must reference
question_versions.id; the logical questions.id is the stable unique-practice
quota identity across routine revisions. No serving or quota engine exists yet.

Free practice, premium practice, and mock flags may be combined without duplicating
questions. Flags do not imply publication or user authorization. Future delivery
must require PUBLISHED and recheck active taxonomy, the requested pool, and the
user's entitlement. QuestionService.search is an admin query, not a student access
boundary.

Source metadata includes type, title, reference, optional year, notes, and use
status: ORIGINAL, LICENSED, PERMITTED, PUBLIC_DOMAIN, UNKNOWN_REVIEW_REQUIRED, or
BLOCKED. Unknown/blocked rights are visible and prevent review/publication.
Only import content you have permission to use commercially. Public visibility
does not establish permission. No exam papers are scraped or seeded.

### CSV and XLSX imports

Use **/admin/questions/import** and download the CSV template. The same headers
work in a single-sheet XLSX saved from a spreadsheet editor. Apache Commons CSV
1.14.1 handles quoted CSV; Apache POI 5.5.1 handles XLSX. Both are Apache-licensed.

Every header below is required exactly once (order may vary); optional cells may
be blank. Unknown, duplicate, or missing headers are rejected.

```text
exam_type,category,question,option_a,option_b,option_c,option_d,option_e,option_f,option_g,option_h,correct_answer,explanation,difficulty,source_type,source,source_reference,source_year,source_notes,copyright_status,free_available,premium_available,mock_available
```

- exam_type and category: existing catalog **codes**, with category scoped to exam.
- question and explanation: nonblank, up to 12,000 characters each.
- option_a through option_h: 2–8 nonblank choices, up to 2,000 characters each.
  correct_answer is exactly one A–H letter identifying a populated slot.
- difficulty: EASY, MEDIUM, HARD.
- source_type and source: nonblank type/title (100/300 characters).
- source_reference, source_year, source_notes: optional (500 characters,
  four-digit year 1000–9999, 2,000 characters).
- copyright_status: one of the use statuses above.
- free_available, premium_available, mock_available: true or false, with at least
  one true. Blank booleans are invalid.

Limits: 2 MiB per file, 3 MiB multipart request, 500 data rows, 12,000 characters
per cell; XLSX ZIP expansion is limited to 20 MiB and 1,000 parts. Only UTF-8 CSV
(with optional BOM) and XLSX are accepted. CSV supports quoted commas, multiline
fields, and Unicode. Blank rows are ignored. XLSX must have exactly one sheet;
formula/error cells are invalid, and macros, external links, embedded objects,
legacy XLS, and corrupt workbooks are rejected. Formulas are never evaluated.
Filenames cannot contain path separators or control characters. Raw uploads
are not retained as files.

Upload parses and persists a row-level preview without creating questions.
Preview reports source status, row number, reference codes, errors, and duplicate
reasons. The original submitted cells are retained as staging JSON for review and
revalidation; question content itself uses normalized relational tables.

Duplicate detection is scoped to exam type and checks all retained versions,
including archived history. NFKC normalization, Unicode whitespace folding, and
case folding identify exact question/options/answer-key matches. Same normalized
question text with differing options or key is a likely duplicate. Punctuation
is preserved to avoid merging mathematically distinct questions. Both classes
are skipped by confirmation and remain available for human review in batch
history; no automatic merge or override exists. Inspect existing content before
manually creating a distinct question.

Confirmation is an authenticated CSRF-protected POST. It revalidates references
and duplicates and creates only valid unique drafts. Unknown/blocked rights may
be imported as drafts for source review. Row errors do not prevent other valid
rows from being imported. Cancel creates no questions. Batch reports retain
uploaded actor/time/name, valid/invalid/duplicate/created/failed counts, and links
to created questions.

Confirmation is one atomic transaction. An unexpected persistence failure rolls
back all its questions, options, links, and counts, leaving the staged batch
retryable; it never claims partial success. Invalid/duplicate rows are expected
outcomes, not failed writes. A successful confirmation has zero failed writes.
The existing settings-row lock serializes confirmation with manual question
creation, registration, and taxonomy edits; parsing happens outside that lock.
The 500-row limit bounds lock duration. Persistent batch state makes refreshes
and simultaneous confirmations idempotent. Audit events retain acting admin and
question/version/batch identifiers without copying full content into audit logs.

### Phase 5 schema and checks

4. V4__question_bank.sql — logical questions, content versions, ordered options,
   historical taxonomy/source context, fingerprints, and lifecycle indexes.
5. V5__question_imports.sql — persistent preview rows, batch counts, question links.
6. V6__question_edit_revision.sql — deterministic optimistic revision advancement
   for draft edits, including saves within the same clock tick/transaction.

Indexes support lifecycle/current-version lookups, taxonomy, source rights,
fingerprints, and import history. Low-selectivity boolean flags are filtered
alongside indexed taxonomy/current-version joins rather than individually indexed.
Search uses bound criteria parameters and database pagination. Content escapes
through Thymeleaf; admin forms bind DTOs, not entities. CSRF and existing admin
role requirements remain in force. No registration phone data appears in content
pages. All automated tests use isolated databases.

```powershell
.\mvnw.cmd '-Dtest=QuestionBankTests,QuestionTransactionTests' test
.\mvnw.cmd test
.\mvnw.cmd package
```

## Compressed Phase 6: student exam engine

Registered students use `/start` to open Practice, Mock Exam, Progress, or Help
in English or Amharic. Practice offers All Categories and paginated eligible
categories for the registered exam type. Free students receive published free
practice content; existing lifetime entitlements also permit premium practice.
Inactive taxonomy and unpublished/archived questions are excluded from new selection.

Showing and skipping questions cost nothing. The first submitted answer to a
logical question consumes one free slot, regardless of later content versions.
Each delivery accepts one immutable answer; Review creates another delivery
without another charge. Feedback includes correctness, the correct choice,
explanation, and remaining allowance. Previously delivered versions remain
answerable after revision/archive. Core progress uses the first answer per
logical question, so retries cannot inflate accuracy. Category accuracy uses
that first answer's historical version. Accuracy rounds to two decimal places.
Review and explanations remain available to free students after exhaustion.
A content shortage is reported separately from an exhausted allowance.

Mock introduction and preparation cost nothing. Preparation freezes the user's
entitlement question-count snapshot (initially 50) using unique published mock
questions from the selected exam type. An insufficient pool creates no attempt
and consumes nothing. One READY/IN_PROGRESS attempt per student is enforced in
the database; returning to the menu leaves it resumable. Its first submitted
answer consumes exactly one mock allowance, initially two; practice and mock
counters are independent. Existing lifetime entitlements bypass both limits.
There is no payment or automatic lifetime-grant flow.

Admin Settings accepts an optional mock duration of 1–1440 minutes. Blank means
untimed; no business duration is invented. Each attempt snapshots the duration
at preparation. Opening its first question starts the server-side deadline.
Navigation, restarting the application, and subsequent setting changes cannot
reset it. The next student operation finalizes an expired attempt; no scheduler
or running-process timer is required. A zero-answer expiry consumes nothing and
does not expose an answer key. Manual submission requires at least one answer.
An untimed unanswered attempt remains resumable rather than allowing repeated
free question-set replacement.

Before submission, students can navigate and change answers without seeing keys
or explanations. Revision-bearing callbacks prevent delayed old choices from
overwriting newer answers. Submission is idempotent and freezes total correct,
incorrect, unanswered, percentage, and category results (one point per correct
answer; no negative marking). Review reads exact frozen versions, including
after edits or archival. Progress shows practice/category metrics and the latest
10 completed mocks. Zero-answer expirations are excluded from completed history.

### Persistence and concurrency

- V7__practice_activity.sql: deliveries, unique user/logical-question usage, and
  durable current practice position.
- V8__mock_exam_attempts.sql: attempts, frozen items, answer revisions, unique
  active-user slot, and persisted totals.
- V9__mock_duration_setting.sql: nullable validated duration.

V1–V6 are unchanged. Short engine transactions share the existing settings-row
lock with content/taxonomy edits, preserving atomic answers, allowance changes,
and content selection. This deliberately serializes initial low-volume traffic;
revisit lock granularity before scaling. Telegram sends happen after commit.
Database ownership checks and composite option/version foreign keys protect
callbacks. Presentation is plain text, split within Telegram message limits.
No phone number or hash appears in student results. Selection stays in SQL;
mock creation avoids per-item content reads, and history/category menus are bounded.

### Verification commands

```powershell
.\mvnw.cmd '-Dtest=PracticeEngineTests,MockEngineTests,EngineConcurrencyTests,StudentTelegramEngineTests' test
.\mvnw.cmd test
.\mvnw.cmd package
# Explicit development PostgreSQL check, with DB_PASSWORD and the existing
# PHONE_IDENTITY_HMAC_KEY supplied securely through the process environment:
.\mvnw.cmd '-Dtest=PostgresExamEngineIT' test
# Use this override when another project owns 8080:
$env:SERVER_PORT='8081'
java -jar target/airline-exam-prep-bot-0.0.1.jar
```

The default suite uses isolated H2 databases and mocked Telegram transport.
The explicit PostgreSQL test uses a fictional registered account and 101 fictional
questions, verifies 100-practice and two full 50-question mock limits, and rolls
back its scenario data. Flyway migrations apply normally and remain installed;
identity sequence values can advance during rolled-back tests. Existing accounts
and entitlement snapshots are never changed for testing. Live Telegram verification
must use actual getMe/polling; only a real human can supply the inbound live click.
Do not weaken a user's mock size to compensate for insufficient live content.

Phase 6 verification (2026-09-25): `test` and `package` each passed 226 tests
(0 failures, 0 errors, 0 skipped). The separately invoked PostgreSQL scenario
passed 1 test with the same zero-failure result. PostgreSQL 18.6 applied V7–V9
without repair; V1–V6 remained unchanged. Scenario rollback preserved the original
one user, one entitlement, six archived questions, and zero engine activity rows.
The packaged application passed Hikari/Flyway/JPA/Tomcat startup, health UP,
Telegram getMe, successful polling, a real registered-user /start response, and
graceful shutdown on port 8081 (8080 was unavailable). No 401/409 occurred.
A live question-answer/mock completion check remains unavailable because the live
eligible question pools are empty; no entitlement size was reduced. Full student
flows passed with mocked Telegram transport and fictional integration content.
No Phase 7 payment workflow or new dependency was added.

## Compressed Phase 7: payments, lifetime access and audit

Students choose Upgrade / Lifetime Access or Payment status from the main menu.
The current price/currency and benefits are shown before a request starts.
Payment requires manual verification against the actual financial account;
no screenshot, reference, receipt, or notification automatically approves it.
Never send real money for software verification.

Admin Settings controls payment_enabled, manual_payment_enabled, lifetime price,
currency, and support information. Both flags must be enabled for new requests
and method selections. Selected requests may finish their evidence submission
when payments are disabled; pending requests remain reviewable. Disabling payments
never revokes lifetime access. Existing entitlement snapshots are unchanged.

Manage public Telebirr and bank destinations at /admin/payment-methods. Methods
have an active flag, display order, holder, destination and instructions. Do not
enter PINs, OTPs or credentials. Changes use optimistic revisions and affect only
new selections. Referenced methods are deactivated rather than deleted.

Each request snapshots amount/currency at creation and method instructions at
selection. One open request per user is enforced by a unique database slot.
The durable states are SELECT_METHOD → AWAITING_REFERENCE → AWAITING_RECEIPT →
PENDING_REVIEW → APPROVED or REJECTED. Cancellation is allowed only before review.
Repeated /start shows the student menu; Payment status resumes evidence collection.
A rejected/cancelled request can be followed by a new request, with a new reference.
An already-lifetime user cannot start an unnecessary payment.

References are trimmed, validated as 3–100 ASCII letters/digits plus dot, hyphen,
underscore or slash, and uppercased with Locale.ROOT. Interior punctuation remains
significant. The normalized value is globally unique across methods and accounts,
a deliberately conservative policy that prevents reuse through another method.
A collision requires support review, never an automatic override. Rejected and
cancelled references remain reserved. Original spelling is retained for the admin.
Method/reference selections are immutable; cancel and start again to correct them.

### Receipts and secured review

Receipt photo or JPEG/PNG/PDF document metadata is accepted up to 10 MiB. Missing
IDs/size/type, dangerous names, MIME/extension mismatch, unsupported formats and
forwarded evidence are rejected. The largest Telegram photo variant is selected.
Receipt acceptance submits for review atomically; pending evidence cannot be edited.
Telegram file_id and file_unique_id are durable storage references. No receipt
binary or token-bearing URL is saved to the filesystem or exposed to the browser.
Duplicate file_unique_id is flagged to the reviewer, not automatically rejected.

/admin/payments supports status, method, UTC date and internal user-ID filters
with 25-row pages. Details preserve the snapshot, evidence, reviewer and latest
25 request-specific audit events. Only authenticated web admins may download
receipts. The server looks up the stored file_id, obtains Telegram's file path,
restricts it to a safe Telegram path, bounds the streamed download and validates
JPEG/PNG/PDF signatures. Downloads are attachments with no-store and nosniff.
Retrieval failures return a recoverable error; retry the secured download later.
No OCR, executable handling, automatic bank integration or payment verification exists.

Approval is a CSRF-protected POST. Under the existing short settings-row lock,
review status, lifetime grant, unique grant provenance, audit and notification
outbox entries commit together. Retrying approval is harmless. Approve/reject
races produce one terminal result. An already-lifetime account receives no second
grant. Original registration grant fields, limits, counters, practice/mock history
and active exams are preserved. The separate lifetime_access_grants table records
payment, user, granting admin and time. Rejection requires a user-visible reason;
there is no separate internal note that could accidentally be sent to the student.

### Notifications and Telegram admin configuration

Supply TELEGRAM_ADMIN_ID as an optional positive numeric environment value.
An absent value disables only Telegram admin notifications; payments and web-admin
review still work. Invalid nonblank values fail configuration validation. The
.env.example value is a placeholder, not a valid runtime ID. Unset the variable
to disable notifications. Never hard-code a real ID in tracked files.

The admin ID was discovered once from a new private /start message during an
explicit development discovery window. Discovery checked getMe, webhook absence,
a fresh update baseline, sender/private-chat agreement and non-forwarded status.
That temporary procedure is closed and removed. There is NO automatic discovery,
first-user-becomes-admin rule, Telegram approval command or web-login bypass in
this application. Web-admin credentials and Telegram notification identity remain
separate security boundaries.

Notifications use a durable database outbox, unique per payment/event. A worker
reads only committed entries every 10 seconds, claims a two-minute lease in a
short transaction, sends outside any database transaction, then records delivery
or safe failure. Retries back off by 60 seconds per attempt, with five automatic
attempts. Admins may retry failed notifications from payment details. No send
failure rolls back evidence, approval, rejection or access. An absent bot transport
leaves deliverable notifications queued; an absent admin destination records SKIPPED.
Exactly-once Telegram delivery cannot be guaranteed after a lost acknowledgement
or crash, so an ambiguous retry may repeat a message, never a financial transition
or grant. Only safe summaries are sent; full references remain in secured review.

/admin/payment-audit provides read-only, paginated filtering by action, entity,
UTC date and actor type. Append-only application flows record request creation,
method/reference/receipt submission, pending status, cancellation, review, lifetime
grants and notification outcomes. Existing settings/content audit is preserved.
No audit update/delete routes exist. Audit metadata excludes references, receipt
contents, phone identities and secrets.

### Phase 7 schema and verification

- V10__manual_payments.sql: methods, request snapshots, state constraints, reference
  uniqueness, open-request uniqueness and query indexes.
- V11__lifetime_grants_and_payment_audit.sql: unique grant provenance and audit events.
- V12__payment_notification_outbox.sql: durable notification state and retry indexes.

V1–V9 are unchanged. No additional dependencies are required.

```powershell
.\mvnw.cmd '-Dtest=PaymentServiceTests,PaymentConcurrencyTests,PaymentWebTests,PaymentTelegramTests,PaymentNotificationTests,ReceiptTests,TelegramAdminPropertiesTests,TelegramReceiptClientTests' test
.\mvnw.cmd test
.\mvnw.cmd package
# Explicit real development PostgreSQL verification with securely supplied DB
# password and the established phone HMAC key; scenario writes roll back:
.\mvnw.cmd '-Dtest=PaymentPostgresIT' test
# Use 8081 when another project owns 8080:
$env:SERVER_PORT='8081'
java -jar target/airline-exam-prep-bot-0.0.1.jar
```

The automatic suite uses isolated databases and mocked Telegram/network transport.
Live receipt uploads require a real human's harmless test image; never fabricate
inbound Telegram updates. No real transfer or sensitive financial receipt is needed.
Phase 7 excludes production deployment, production webhooks and Phase 8 work.

Development verification: the full suite contains 294 tests, plus a separately
invoked real-PostgreSQL payment integration test whose scenario rolls back.
Live checks exercised authenticated review, CSRF rejection, rejection without an
access grant, repeated approval with exactly one lifetime grant, append-only audit,
and delivery of both admin and user notifications. The development account became
lifetime through normal secured approval; its registration grant fields, limits,
usage counters and existing history were preserved. Temporary development methods
were deactivated and the original payment flags restored. No financial transfer
occurred. The one-time discovery and fixture helpers were removed after use.

No human receipt attachment was available for this run. Live review used explicit
synthetic metadata supplied through the payment service, without fabricating inbound
Telegram traffic or a bank receipt. Real Telegram file retrieval remains a live user
check; receipt parsing, authorization, bounded download and file-signature validation
are covered by automated tests. Do not treat development approvals as financial
evidence. Retry tests use a controlled clock, and receipt failures return explicit
HTTP responses so servlet error dispatch cannot obscure the intended status.

## Compressed Phase 8: security, UX and recovery

See [PHASE8_REVIEW.md](PHASE8_REVIEW.md) for the threat model, adversarial evidence,
dependency review, limitations and Phase 9 handoff. No production deployment is
included. Existing content, entitlement and payment rules remain intact.

Admin login allows ten attempts per direct peer per minute, with bounded local
storage. Sessions expire after 30 minutes; cookies use HttpOnly and SameSite=Lax.
Set `SESSION_COOKIE_SECURE=true` when serving through production HTTPS. CSP forbids
scripts, framing and external resources. Error pages omit exception details and
offer recovery links. Forwarded registration contacts are rejected.

V13__notification_claim_ownership.sql adds a nullable outbox claim token. Completion
is conditional on the current claim, preventing stale workers from overwriting a
new attempt. Expired fifth attempts become manually retryable failures. Telegram
retry-after is honored up to one hour. Business changes remain committed when a
notification fails; ambiguous delivery may repeat a message.

Adversarial imports cover MIME contradictions, unsafe ZIP names, expanded-size and
part limits, malformed CSV and oversized cells. Admin pages include responsive
overflow, touch targets, visible focus and useful empty states. English/Amharic
keys and placeholders are checked together. Tomcat is patched to 10.1.60; no new
library is added. Resolve Spring Boot support before production (see the review).

Verification commands, with development secrets supplied only through environment:

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
.\mvnw.cmd '-Dsurefire.runOrder=random' '-Dsurefire.runOrder.random.seed=8252026' test
.\mvnw.cmd '-Dtest=PaymentPostgresIT,PostgresExamEngineIT' test
```

The automatic suite uses isolated test data and mocked Telegram transport. The
explicit PostgreSQL scenarios roll back their writes; additive Flyway migrations
remain applied. Optional human receipt verification requires a harmless real
attachment and an eligible non-lifetime development account; never downgrade an
existing account or fabricate inbound Telegram traffic to perform it.
