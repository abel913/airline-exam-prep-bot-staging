# Airline Exam Preparation Bot — Product and Architecture Specification

Current active milestone: **Compressed Phase 10: Advanced Product Features,
Analytics & Operational Polish**, based on `a58e997`. This supersedes the Phase 9
milestone paragraph below while retaining historical decisions. Acceptance status
is recorded in PHASE10_REVIEW.md. The Phase 10 request authorizes committing and
pushing passing changes to main, superseding section 16's default for this phase.

Phase 10 adds bounded owned practice/mock history, first-answer category insights,
deterministic eligible weak-area practice, optional immutable-version tags,
UTC aggregate analytics, protected formula-safe bounded CSV reports and audited
maintenance controls. Use existing data; no IP/device tracking, phone/HMAC exports,
financial evidence analytics or entitlement override. No new dependencies.
Weak-category minimum is five first answers; weak below 60%, strong at least 80%.
Quality signals require ten answers and cannot mutate question lifecycle/difficulty.
V14 adds columns/indexes only; V1–V13 stay immutable. Maintenance responds and asks
students to retry later without executing business commands; mock deadlines keep
running. Existing audited notification retries remain on payment details.

## 1. Authority and current scope

This document is the source of truth for subsequent development of the airline written-exam preparation platform. Read it completely before changing the project. Explicitly agreed requirement changes must be reflected here rather than silently changing product behavior.

The current milestone is **Compressed Phase 9: Test Production Deployment & Operations**, built on verified Phase 8 baseline `c9518d2`. It prepares a free Render Docker deployment using Java 21, Supabase PostgreSQL Session Pooler and authenticated Telegram HTTPS webhooks. Registration, content, exam and payment business rules remain intact. Local polling remains supported. PRODUCTION_RUNBOOK.md describes deployment and operations; PHASE9_REVIEW.md distinguishes local evidence from pending live acceptance. Existing temporary test credentials remain unchanged and must be replaced before serious private/paid production.

Requirements below are planned Version 1 capabilities unless labeled optional, future, or a decision to resolve. Implement only the structures needed by the active phase.

## 2. Product purpose and boundaries

Students prepare for airline written exams primarily through a Telegram bot. The platform provides practice questions, mock exams, explanations, progress records, and an optional paid lifetime upgrade. Registration should be short and collect only necessary information.

The product is an independent preparation platform. It must not claim to be an official airline examination service without authorization.

Version 1 is **one modular Spring Boot application, one repository, and one deployment**, with PostgreSQL persistence and a server-rendered admin website inside the same application. Keep operation practical on free or very low-cost infrastructure; the initial infrastructure goal is 0 ETB where feasible, not a guarantee of a particular hosting provider or free tier.

## 3. Access model and initial offer

There are two access levels: **FREE** and **LIFETIME**.

| Setting | Initial default | Meaning |
| --- | --- | --- |
| Free practice allowance | 100 | Unique practice questions with a submitted answer |
| Free mock allowance | 2 | Full mock attempts started by the first submitted answer |
| Questions per full mock | 50 | Default mock size |
| Free offer expiration | None | Free access has no time-based expiration |
| Lifetime price | 50 | Configurable price, initially in ETB |
| Currency | ETB | Configurable currency |

These business defaults must ultimately be stored as configurable settings, not embedded as permanent Java constants. Initialization of defaults in the appropriate implementation phase is permitted.

### 3.1 Independent free allowances

Practice and mock usage are independent. Mock answers do not consume practice allowance, and practice answers do not consume mock attempts.

- At 100/100 practice questions used and 1/2 mocks used, new practice consumption is locked; one mock remains available.
- At 50/100 practice questions used and 2/2 mocks used, new mock starts are locked; 50 unique practice answers remain available.
- A user may upgrade before either allowance is exhausted.
- Exhausting an allowance must not prevent resuming an already-consumed mock attempt.

### 3.2 Registration-time entitlement snapshot

Registration must copy the current free offer into the user's entitlement. Store granted allowances separately from usage and from current global defaults.

For example, Daniel registers with a 100-practice/2-mock offer. If the administrator later changes defaults to 50-practice/1-mock, Daniel retains 100/2; only new registrations receive 50/1. Changing global defaults must never silently reduce existing grants or reset counters. Any eventual explicit access adjustment must be authorized and auditable.

Because mock question count is described as part of the offer for future registrations, preserve the registration-time default mock size as part of the offer snapshot as well. Separately, each prepared mock retains its own selected questions and actual question count. How future configurable blueprints interact with an older offer's mock size must be resolved before the mock engine is implemented; existing attempts must never be resized.

### 3.3 Lifetime entitlement

Successful admin-approved payment grants lifetime access to the specific paying user, including:

- Unlimited practice and mock exams, without free quota restrictions.
- All available categories and the premium question bank, subject to publication and exam eligibility.
- Complete explanations, progress history, and future question-bank updates.
- No time-based entitlement expiration.

Later price changes must not affect already-granted lifetime access. Historical free usage may remain for records but must not restrict lifetime users. Unlimited access does not imply that unpublished content is accessible or that an unlimited number of distinct questions exists.

## 4. Registration, identity, and localization

The intended registration flow is:

`/start → choose language → choose exam type → share own Telegram contact → verify/register → create free entitlement → main menu`

Initially plan for English and Amharic. Keep user-facing text in a localization structure rather than scattered throughout Java code; permit additional languages later.

Exam types initially include examples such as Cabin Crew, Pilot, AMT, and Marketing. Exam types and categories are admin-managed data, not permanently fixed enums containing every possible offering.

Telegram user ID uniquely identifies the Telegram account. Repeated registration requests must not create duplicate users or fresh entitlements. Accept the user's own contact only after checking that the contact identity belongs to the Telegram sender; an arbitrary typed number or another person's contact is insufficient. This is shared-contact identity verification, not an invented independent SMS verification service.

### 4.1 Duplicate free-offer protection

Use Telegram user ID together with normalized, verified/shared phone identity to limit repeated free claims across accounts. The same verified phone identity must not receive repeated new free entitlements through different Telegram accounts. Enforce this rule persistently and atomically so simultaneous registration requests cannot bypass it.

Normalize Ethiopian phone identities consistently before comparison. Final normalization and validation cases must be documented and tested in the registration phase. Consider storing a secure keyed hash/HMAC of the normalized identity to reduce retention and exposure of raw phone numbers. If used, the HMAC key is a secret, and key rotation must preserve duplicate-claim detection.

Do not collect IMEI, MAC addresses, or invasive device fingerprints. Telegram bots cannot reliably establish physical-device identity. Multiple SIM cards can still bypass phone-based protection; that limitation is acceptable. Account recovery or transfer rules for a phone already associated with another account remain a decision to resolve; never silently issue another free grant or transfer lifetime access.

### 4.2 Main menu direction

The planned main menu contains Practice Questions, Mock Exams, My Progress, Upgrade / Lifetime Access, Account, and Help. Users should reach useful content quickly, with clear remaining allowances and resume options where relevant.

## 5. Practice behavior

The initial free allowance is **100 unique answered practice questions**.

1. Presenting a question does not consume quota.
2. The first accepted submitted answer to a unique question consumes one unit.
3. Viewing or answering the same question again consumes no additional unit.
4. Enforce eligibility, record the answer, and update any usage state consistently so retries or concurrent answers cannot double-count or exceed the allowance.
5. Lifetime users bypass free limits.

Example: usage is 34 when Question #1001 is shown. It remains 34 until an answer is submitted, then becomes 35. Later answers to #1001 leave usage at 35.

The data model must distinguish a logical question identity from an immutable question version. Define the quota identity precisely in the question/practice phases so a routine content correction cannot accidentally charge the same question again. Historical answers must reference the version actually presented.

Free users may receive only appropriately published questions from the admin-controlled free practice pool. They must not be able to enumerate the premium bank through direct IDs, stale callbacks, or repeated requests. Whether already-answered questions remain available for review after quota exhaustion must be explicitly resolved before free-limit behavior is finalized; repeated answers must never consume additional units.

## 6. Exam types, categories, and question bank

Administrators manage exam types and their applicable categories. Category examples are English, Mathematics, Aptitude, Logical Reasoning, General Knowledge, and Aviation Knowledge. Different exam types may use different category sets.

### 6.1 Question information

Questions must support at least:

- Stable ID, exam type, and category.
- Question text, answer options, correct answer, and explanation.
- Difficulty and source metadata.
- Publication status and free/premium/mock availability flags.
- Creation timestamp, update information, and version information.

Planned statuses are `DRAFT`, `REVIEWED`, `PUBLISHED`, and `ARCHIVED`. Only appropriate published questions may be selected for new student activity. Availability may combine free practice, premium practice, and mock eligibility. Admin controls pool membership; mock eligibility alone must not inadvertently expose the whole bank to free users outside their allotted attempts.

### 6.2 Historical integrity and versioning

Use archive/version/replace semantics for published content that has been used. Never destructively overwrite a question or answer key in a way that changes historical results. Preserve question text, options, answer key, explanation, and relevant category/scoring context for the version shown to a student.

If a question's correct answer changes from B to C after 800 students answered it, their existing results must remain tied to the original version and scoring context. Any future explicit regrading feature is outside the current scope. Archiving content removes it from new selection without breaking existing references or review history.

### 6.3 Sources and content rights

Record useful provenance such as source name, year, type, reference, and copyright/permission status. Discovery online does not establish commercial redistribution permission. Prefer original practice questions, properly licensed material, public-domain/permitted material, or original questions and explanations derived from legitimate reference concepts. Publication review must consider both content quality and source rights.

### 6.4 Bulk Excel/CSV import

The intended workflow is:

`Upload → parse → validate → detect duplicates → show problems → save valid records as DRAFT → admin review → publish`

Example columns: `exam_type`, `category`, `question`, `option_a`, `option_b`, `option_c`, `option_d`, `correct_answer`, `explanation`, `difficulty`, `source`, `free_available`, `premium_available`, and `mock_available`.

Validate references, required fields, options, answer keys, and availability values. Detect duplicates and explain rejected/duplicate rows clearly. Invalid rows must not destroy valid rows. Imports must never automatically publish all uploaded content.

Retain import-batch history including original filename, acting admin, timestamp, total rows, imported rows, duplicates, and rejected rows. Associate created questions with the batch so a bad import can be identified and handled later without destroying referenced history. Final duplicate detection rules and file limits belong to the import phase.

## 7. Mock exams

The initial free offer contains **2 full mocks of 50 questions each by default**. The mock allowance and default size are configurable for future registrations, subject to the entitlement snapshot rule.

Future mock configuration may support exam type, blueprint, category distribution, question count, duration, random or fixed selection, and difficulty distribution. These are planned extensions within mock design, not a requirement to implement every option in the initial engine.

An example 50-question Cabin Crew blueprint allocates 10 questions each to English, Mathematics, Aptitude, Logical Reasoning, and General/Aviation knowledge. This example is not a mandatory blueprint for all exams.

### 7.1 Preparation, consumption, and resume

- Opening a mock introduction does not consume an attempt.
- The first accepted answer starts/consumes the attempt exactly once.
- Persist the prepared selection sufficiently early that reopening before the first answer cannot repeatedly reveal newly drawn sets. By the first accepted answer, the full selected version set and order must be frozen.
- Persist progress after each accepted answer. Retries and duplicate callbacks must not consume additional attempts or duplicate answers.
- Closing Telegram does not generate a replacement exam. A student at 23/50 must resume that same attempt through Continue Exam.
- Prevent repeated restarts from exposing unlimited question sets. Final abandonment and concurrent-attempt rules must be defined before implementation.
- Verify that enough eligible published questions exist before offering a full exam; do not silently shorten an advertised full mock or charge for an exam that cannot be prepared.

Attempt consumption, answer persistence, and quota enforcement must be atomic. Completing or resuming a started attempt must not consume another allowance.

### 7.2 Optional timed mocks

Timers are optional future mock functionality. If enabled, the backend controls `started_at` and `expires_at`; never trust a phone's clock. Define the precise timer start policy before enabling timers. On expiration, unanswered items may be recorded as unanswered and the result calculated. Reopening Telegram cannot reset the deadline.

### 7.3 Results and review

Planned results contain total questions, correct, incorrect, unanswered, percentage, category performance, answer review, and explanations. Totals and scoring must be reproducible from the actual attempt's frozen question versions and scoring context. Later question-bank edits must not change completed history.

## 8. Progress tracking

Plan to show total questions answered, correct and incorrect counts, accuracy percentage, category performance, mock history, recent activity, and remaining free allowances where applicable.

Keep progress metrics distinct from quota accounting: repeated practice answers may produce activity but must not consume new unique-question allowance. Define whether each displayed accuracy metric uses first, latest, or all answers in the progress phase so results are understandable and consistent. Free-user explanation and history depth, compared with the complete lifetime benefit, remains a product decision; do not silently invent restrictions.

## 9. Payment and lifetime access

### 9.1 Initial manual verification workflow

The chosen initial business workflow is manual payment verification:

1. User selects Unlock Lifetime Access.
2. Backend creates a payment request with a snapshot of expected amount, currency, destination/instructions, and creation time.
3. Bot shows the configured Telebirr or bank instructions.
4. User pays externally and submits payment method, transaction/reference number, and receipt image information.
5. Submission enters `PENDING` review.
6. Admin compares the claim with the actual transaction in the financial account.
7. Admin approves or rejects. Approval grants lifetime access to that specific user.

Screenshots alone are never proof of payment. The exact request/submission state machine, pending-request reuse, rejection/resubmission behavior, and any expiration policy must be specified in the payment phase. Do not invent automatic payment confirmation.

### 9.2 Payment-request snapshot

Persist the expected amount and currency on each request, together with destination details shown to the user. A request created at 50 ETB continues to expect 50 ETB after the global price becomes 80 ETB. New requests use 80 ETB. Changes to global payment instructions must not silently rewrite earlier request snapshots.

Payment enable/disable controls must govern availability without silently changing existing requests or removing already-granted lifetime access. The policy for handling pending requests when payments are disabled must be resolved in the payment phase.

### 9.3 Reference and receipt protection

Normalize transaction/reference numbers using method-appropriate rules. Protect approved references against reuse across accounts, including simultaneous approvals. Define the uniqueness scope using the payment method/provider and reference format; one real transaction must never fund multiple account approvals through formatting variations.

Record Telegram receipt metadata, preferably `telegram_file_id` and `telegram_file_unique_id`, rather than depending on permanent files such as `/uploads/receipt.jpg` on ephemeral hosting. Duplicate file indicators can assist review but do not establish proof or disproof of payment. Receipt retrieval failures need a recoverable review path; temporary local disk must not be the system of record.

### 9.4 Replaceable payment boundary

Keep payment logic isolated behind a service boundary such as `PaymentService`. Planned implementations may include `ManualPaymentService`, a future `TelegramStarsPaymentService`, or another future provider. The latter are extension possibilities, not Phase 1 code or mandatory Version 1 integrations.

Manual payment must be enable/disable capable through settings. Replacing a payment method must not require rewriting questions, practice, mocks, progress, registration, or entitlement rules. Payment records establish approval; the access module owns entitlement granting and checks.

### 9.5 Transactional access grant

Approval and the associated lifetime grant must be transactional and idempotent. Persist the relationship between the approved payment, recipient, and access grant. Retrying approval must neither grant twice nor reuse a transaction for another account. Reject/approve races must resolve consistently. Free counters may remain unchanged for historical reporting after upgrade.

## 10. Admin website, settings, and audit

### 10.1 Application architecture and authentication

The admin website uses Thymeleaf and Spring Security inside the same Spring Boot process as Telegram integration and domain services. There is no separate React application or frontend deployment.

Admin authentication must be separate from normal Telegram-user identity. Knowing `/admin` is not authorization. Securely hash admin passwords and enforce appropriate authorization on sensitive operations. Proper authentication is implemented in the admin phase; a foundation phase must not create fake production authentication or publicly writable business endpoints.

### 10.2 Planned dashboard sections

Dashboard, Users, Exam Types, Categories, Questions, Question Import, Mock Exams, Payments, Access / Entitlements, Settings, and Audit Logs.

Useful dashboard counts include total users, free users, lifetime users, questions, published questions, pending payments, and mock attempts.

### 10.3 Configurable settings

Administrators must eventually manage the following without changing Java code:

- Default free practice allowance, default free mock allowance, and default questions per mock.
- Current lifetime price and currency.
- Payment enabled/disabled and manual payment enabled/disabled.
- Telebirr number, bank name, account number, and account holder/name.
- Support information and future business settings as needed.

Validate settings before saving. Clearly distinguish global defaults, existing entitlement snapshots, payment-request snapshots, and attempt configuration. Sensitive settings changes require admin authorization and audit records. Production secrets belong in secure configuration, not general-purpose dashboard settings.

### 10.4 Audit trail

Important admin operations must eventually record actor/admin, action, target, timestamp, and appropriate old/new values. Cover payment approval/rejection, price and allowance changes, payment destination changes, imports, publication, archiving, and user/access changes.

Example action names are `PAYMENT_APPROVED`, `PAYMENT_REJECTED`, `PRICE_CHANGED`, `FREE_LIMIT_CHANGED`, `QUESTIONS_IMPORTED`, `QUESTION_PUBLISHED`, `QUESTION_ARCHIVED`, and `ACCESS_GRANTED`. Do not put passwords, tokens, or unnecessary personal/receipt data in audit values.

Although the dedicated audit phase consolidates the capability, earlier sensitive features must preserve necessary actor/time/action context rather than discarding evidence until that phase.

## 11. Technical architecture

### 11.1 Planned stack

Java 21, a stable compatible Spring Boot release, Maven with Maven Wrapper, PostgreSQL, Flyway, Spring Data JPA, Spring Validation, Spring Security, Thymeleaf, Telegram Bot API, Spring Boot Actuator for health verification, and JUnit 5. Choose and verify concrete supported dependency versions during Phase 2; this specification does not pin an unverified version.

Suggested project identity: artifact `airline-exam-prep-bot`, base package `com.airlineprep.bot`.

### 11.2 Logical module boundaries

| Module | Responsibility |
| --- | --- |
| `config`, `common` | Necessary shared configuration and cross-cutting support |
| `telegram` | Telegram transport, updates, callbacks, and presentation |
| `user` | Registration, account identity, and language/exam preferences |
| `access` | Entitlement snapshots, eligibility, quotas, and grants |
| `examtype`, `category` | Configurable exam taxonomy |
| `question` | Content, immutable versions, availability, and imports |
| `practice` | Practice selection, answers, and practice usage |
| `mock` | Blueprints, attempts, resume, and results |
| `payment` | Requests, evidence, verification workflow, and provider abstraction |
| `admin` | Authenticated website and administrative use cases |
| `settings` | Validated business configuration |
| `audit` | Administrative change history |

These are logical boundaries, not instructions to create empty packages, interfaces, or dozens of placeholder classes. Keep controllers and Telegram handlers thin; business services own rules. Use constructor injection, clear names, and only useful comments. Avoid unnecessary static global state and boilerplate.

Telegram transport must remain separate from business logic. Local development may initially use long polling. Production web hosting should support a secured webhook mode, with transport-independent use cases and idempotent update handling.

### 11.3 Persistence direction

Potential later concepts/tables include:

| Area | Candidate concepts |
| --- | --- |
| Identity/access | `users`, `trial_identities`, `access_entitlements`, `access_grants` |
| Taxonomy/content | `exam_types`, `categories`, `questions`, `question_options`, `question_versions`, `question_import_batches` |
| Practice | `practice_attempts`, `practice_answers` |
| Mocks | `mock_exam_blueprints`, `mock_attempts`, `mock_answers` |
| Payment | `payment_requests`, `payment_submissions` |
| Administration | `app_settings`, `admin_users`, `admin_audit_logs` |

This is architectural direction, not a complete schema. Each phase creates only its needed structures. Use database constraints and transactions to protect identity uniqueness, unique practice consumption, mock consumption, and payment/access idempotency.

Flyway owns schema evolution. Use additive, versioned migrations named `V1__description.sql`, `V2__description.sql`, and so on. Never modify an applied migration. Avoid destructive history changes. JPA must not create, recreate, or update the application schema automatically; prefer validation once migrations exist. If no table is needed for foundation, document deferring the first business migration rather than inventing tables.

## 12. Database safety and secrets

The prepared local development database connection is:

| Property | Value |
| --- | --- |
| Host | `127.0.0.1` |
| Port | `5432` |
| Database | `airline_exam_bot` |
| User | `airline_exam_user` |

No real password belongs in this document or in tracked configuration. Future datasource configuration must use `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`. Telegram tokens, admin credentials/secrets, phone HMAC keys, and future webhook secrets must come from environment variables or secure deployment configuration.

Never commit or log secrets. A future `.env.example` may contain names and safe placeholders only; real `.env` and similar secret files must be ignored. Keep build output, logs, and IDE/OS temporary files out of Git as appropriate.

Never run `flyway clean`, `DROP DATABASE`, `DROP SCHEMA`, `TRUNCATE`, bulk row deletion, or destructive test operations against development or production. Inspect database-changing operations before execution. Tests must use safe isolated configuration and must not depend on real Telegram credentials or mutate the development database. Never weaken production security or persistence settings just to make tests pass.

## 13. Deployment, operations, and recovery

Keep the application lightweight and avoid unnecessary services, separate frontend hosting, and persistent local receipt storage. Phase 9 selects Render Free Docker hosting in Europe with Supabase PostgreSQL Session Pooler. Cold-start delays from sleeping free hosting are accepted; no self-ping or keep-awake mechanism is permitted.

Expose only necessary health information through Actuator; do not publicly expose all management endpoints. Secure production Telegram webhook delivery appropriately and keep secrets out of URLs/logs where avoidable. Concrete production settings and provider/platform requirements must be checked in the relevant later phase.

Before meaningful production use, establish backups for users, questions, access grants, payments, progress, and settings. Document recovery and test a restore into an isolated environment. A backup without a tested restore is insufficient. Hosting choice must permit a viable backup/recovery path.

## 14. Version 1 non-goals

The following are not required for Version 1: React admin frontend, Flutter or React Native app, Telegram Mini App, AI question generation, leaderboards, referrals, Redis, Kafka, microservices, Kubernetes, paid hosting, or complex analytics. Optional advanced features may be reconsidered later through explicit scope changes. Node.js and a separate frontend server are not needed for the chosen application foundation.

## 15. Development roadmap

The explicitly authorized compressed roadmap supersedes the scheduling of the original small phases below: **Compressed Phase 4** combines original Phases 4–8 (registration, entitlement foundation, admin, settings, exam types/categories). **Compressed Phase 5** is Question Bank & Content Management. **Compressed Phase 6** implements the complete exam engine. **Compressed Phase 7** implements manual payments, lifetime access and audit. The original roadmap is retained as architectural history; its phase numbering does not prohibit the authorized combined scope.

### Compressed Phase 4 decisions

- Persist resumable private-chat onboarding in English and Amharic. Request only the sender's own Telegram contact. Do not retain raw phone or unnecessary Telegram names.
- Normalize Ethiopian mobile numbers starting with 07/09 or international +2517/+2519 (also 251 without plus); accept spaces, parentheses, and hyphens as separators, reject other country codes/prefixes/lengths/letters.
- HMAC-SHA256 with an environment-supplied, stable Base64 key of at least 32 random bytes identifies the phone. Keep a keyed configuration fingerprint to detect accidental key changes. Key rotation requires a separate identity-preserving migration; recovery never silently transfers accounts or issues another free grant.
- Registration completion and a single free entitlement commit atomically. A short database settings-row lock serializes onboarding writes and offer/taxonomy edits; unique Telegram IDs, phone hashes, and user grants enforce integrity independently. No network call runs under that lock.
- Initial offer and lifetime-price defaults remain unchanged. Grant snapshots include mock size and independent zeroed usage counters, without expiry. Consumption and payment workflows are deferred.
- Initial admin provisioning uses optional environment credentials, stores BCrypt only, and never resets existing passwords on restart. Admin forms use sessions, CSRF, validation, and Post/Redirect/Get. Settings and taxonomy mutations preserve actor/time/before/after audit context.
- Exam types/categories support create/edit/activation/deactivation, with no destructive delete routes. Registration uses only active exam types and rechecks deactivation before completion. No preconfigured exam types are required to start safely.

### Compressed Phase 5 decisions

- Stable logical question IDs survive revisions and will be the unique-practice quota identity. Future historical attempts must reference the exact question-version ID; no attempt or quota-consumption workflow is implemented here.
- Single-correct MCQs support 2–8 ordered nonblank options. Drafts can be incomplete. Review/publication require matching active taxonomy, text, explanation, difficulty, distinct options with exactly one correct key, provenance, resolved rights, and at least one pool.
- Draft edits retain their version number; reviewed edits clear review. Published edits create a new draft version and suspend the logical question from new selection until republished. Published content, option keys, source details, and taxonomy names/IDs remain preserved. Archiving is non-destructive, with no restore or physical-delete route.
- Source-use statuses are ORIGINAL, LICENSED, PERMITTED, PUBLIC_DOMAIN, UNKNOWN_REVIEW_REQUIRED, and BLOCKED. Unknown/blocked content may be staged/imported as draft but cannot be reviewed or published.
- CSV/XLSX uploads persist preview rows and batch history before any question creation. Confirmation imports valid unique rows as drafts, rechecking taxonomy and duplicates. Invalid and duplicate rows remain reviewable. Batch confirmation is atomic and idempotent; unexpected write failures roll back all newly created content and leave the batch retryable.
- Duplicate fingerprints use Unicode normalization, case folding, and whitespace folding within an exam type, including retained historical versions. Same text/options/key is exact; same text with changed options/key is likely. Both are skipped for human review; punctuation is preserved to avoid collapsing distinct mathematical expressions. No silent merge or automatic override exists.
- Upload limits are 2 MiB/file, 3 MiB/request, 500 data rows, one XLSX sheet, 20 MiB expanded workbook, and 1,000 ZIP parts. Formula/error cells, macros, external links, and embedded objects are rejected. README documents the exact headers and cell limits. Upload bytes are not retained as filesystem files.
- V4–V6 are additive migrations following V1–V3. V6 guarantees deterministic stale-edit protection independently of clock resolution. Content pages retain existing admin authentication, CSRF, escaped rendering, and audit context. The live fictional verification content is archived and its temporary taxonomy deactivated.
- Compressed Phase 5 content management is complete. Compressed Phase 6 adds the exam engine; payments are added by compressed Phase 7; production deployment remains unimplemented.

### Original incremental roadmap (historical)

| Phase | Scope |
| --- | --- |
| 0 | Environment and account setup |
| 1 | Create and validate `PROJECT_SPEC.md` |
| 2 | Spring Boot foundation only |
| 3 | Telegram connection and `/start` |
| 4 | Registration and verified phone identity |
| 5 | Access entitlement model |
| 6 | Admin security/dashboard foundation |
| 7 | Configurable application settings |
| 8 | Exam types and categories |
| 9 | Question bank |
| 10 | Excel/CSV import |
| 11 | Practice engine |
| 12 | Progress tracking |
| 13 | Mock exam engine |
| 14 | Mock results/review |
| 15 | Free-limit behavior |
| 16 | Manual payment requests |
| 17 | Receipt/reference handling |
| 18 | Admin payment approval |
| 19 | Lifetime access |
| 20 | Admin audit logging |
| 21 | Question/source quality controls |
| 22 | Bot UX improvements |
| 23 | Security/reliability hardening |
| 24 | Automated testing consolidation |
| 25 | Manual end-to-end testing |
| 26 | Production configuration |
| 27 | Free deployment |
| 28 | Production Telegram webhook |
| 29 | Backup/recovery |
| 30 | Small beta |
| 31 | Public launch |
| 32 | Monitoring/improvement |
| 33 | Optional advanced features |

The roadmap is incremental, not permission to defer essential safeguards. Tests accompany every coding phase; Phase 24 expands and consolidates them. Registration must not issue free grants until entitlement support is available. Practice/mock quota invariants must be designed into their engines; Phase 15 completes user-facing limit behavior. Payment approval must not be presented as a completed purchase until the transactional lifetime grant is implemented. Earlier audit context and source metadata support the later consolidated audit and quality phases. Do not expose incomplete dependent flows to real users.

### 15.1 Phase 2 boundary

Phase 2 establishes Java 21, Maven Wrapper, Spring Boot dependencies, environment-based datasource configuration, safe JPA/Flyway setup, minimal security, limited health exposure, safe baseline tests, and setup/run documentation. Verify compile/tests, application startup, `/actuator/health`, clean shutdown, and changes where the environment permits. Report blockers honestly.

Phase 2 must not implement Telegram, registration, questions, practice, mocks, payments, admin business features, or lifetime access. It must not create the full business schema. The foundation health endpoint may be accessible without introducing a generated-login workflow or fake admin authentication. Proper admin security belongs to Phase 6.

## 16. Engineering and change-control rules

1. Treat this specification as the source of truth and read it before changes.
2. Work one development phase at a time; stop at its boundary.
3. Do not implement future phases early unless a narrowly scoped technical prerequisite is unavoidable and documented.
4. After each coding phase, compile, run appropriate tests, fix failures, manually verify relevant behavior, review the Git diff, and stop.
5. Prefer simple, maintainable architecture over clever abstractions.
6. Do not hard-code changeable business settings.
7. Protect entitlement, payment, question, and result history.
8. Protect secrets and minimize personal-data exposure.
9. Use additive/versioned migrations; never rewrite applied migrations.
10. Keep payment replaceable and separate from access/practice/mock logic.
11. Separate Telegram transport from domain logic.
12. Never silently weaken security to pass tests.
13. Avoid destructive database operations and isolate tests.
14. Preserve compatibility with free or low-cost deployment.
15. Preserve existing valid work. Do not modify Git remotes, force-reset history, delete `.git`, commit automatically, or push automatically.

## 17. Decisions to resolve before their implementation phases

These decisions do not block the Phase 2 technical foundation and must not be filled with invented product features:

- Registration: controlled HMAC key rotation and recovery policy for a previously claimed phone identity. Normalization, HMAC adoption, and stable-key requirements are defined in the compressed Phase 4 decisions above.
- Practice/progress decisions are resolved by the compressed Phase 6 decisions below.
- Mock engine decisions are resolved by the compressed Phase 6 decisions below; category blueprints remain future scope.
- Payment decisions are resolved in the compressed Phase 7 decisions below.
- Admin/operations: credential provisioning, detailed authorization, audit/data retention, production transport/hosting, backup schedule, and recovery objectives.

## 18. Specification validation baseline

Future implementations must preserve these acceptance examples:

- The initial offer is 100 unique answered practice questions, 2 full mocks, 50 questions per mock by default, and no free-offer time expiration.
- Showing a practice question consumes nothing; its first submitted answer consumes once, and repeating it does not consume again.
- Practice and mock allowances remain independent.
- Global offer changes affect new registrations, not existing entitlement snapshots.
- The initial lifetime price is configurable at 50 ETB; pending requests preserve their own amount/currency/destination snapshot.
- A verified phone identity cannot repeatedly claim free entitlements through different Telegram accounts.
- Imports save validated content as drafts; review and publication are separate controlled steps.
- A mock starts consuming allowance on its first answer, retains its question set, and resumes at saved progress.
- Historical results remain tied to the versions presented, despite later corrections.
- Payment verification checks real transactions; approved references cannot fund multiple accounts, and access grants are idempotent.
- Admin, settings, Telegram, and services run in one modular Spring Boot application with a replaceable payment boundary.

Phase 1 delivers this document only. No implementation or operational verification is implied by these planned acceptance rules.

## 19. Compressed Phase 6 decisions

- Default 100 unique answered practice questions, 2 mocks, and 50 questions per
  mock are enforced from registration-time entitlement snapshots. No offer expiry.
- Practice delivery/skip costs zero. Unique logical-question first answers charge
  once across content versions. Repeat deliveries preserve first-answer accuracy.
  Free students retain explanation/review access after exhaustion.
- New practice selection uses eligible published free content, plus premium for
  existing lifetime access. Historical deliveries retain their exact versions.
- Mock preparation freezes a random unique set from the selected exam's active
  published mock pool, using the entitlement's size. A shortage fails without
  consumption. No category blueprint or smaller fallback is invented.
- One active READY/IN_PROGRESS attempt is allowed per user. Leaving the menu
  preserves it; it resumes at the first unanswered item, or saved position when
  all are answered. There is no cancellation/replacement loop for free previews.
- Only the first answer consumes a mock. Answers may change before finalization;
  stale answer revisions cannot undo newer choices. Submission is idempotent.
- Duration is optional (blank/NULL = untimed, otherwise 1–1440 minutes), snapshotted
  at preparation. First question opening starts an absolute server deadline.
  Lazy expiry on interaction survives restarts. Zero-answer expiry costs nothing
  and withholds answer-key review; manual submission requires an answer.
- Scoring gives one point per correct answer, no negative marking, and percentage
  over the complete frozen set. Unanswered items and historical category results
  are explicit. Review references frozen versions; totals persist after completion.
- Progress uses first practice answers and latest 10 completed mocks; zero-answer
  expiry is excluded from completed history. Percentages round to two decimals.
- Existing lifetime flags permit unlimited appropriate practice/mocks without
  adding payment, receipt, approval, notification, or access-grant endpoints.
- V7–V9 are additive. Transactional settings-row serialization and database
  uniqueness protect concurrent usage/attempt creation; network sends follow commit.
- Live human actions cannot be fabricated. Successful transport plus automated
  engine/Telegram E2E verification may be reported PASS-WITH-LIVE-USER-CHECK.

## 20. Compressed Phase 7 decisions

- Manual payment is isolated behind PaymentService. Telebirr and bank transfer
  methods contain public destinations only, managed through secured web admin.
- One open payment per user. Price/currency snapshot at creation; method details
  snapshot at selection. Later edits never rewrite instructions already shown.
- SELECT_METHOD → AWAITING_REFERENCE → AWAITING_RECEIPT → PENDING_REVIEW →
  APPROVED/REJECTED. Cancellation only before pending review; no request expiry.
  New requests follow cancellation/rejection; selected evidence is immutable.
- Both payment flags gate creation and method selection. Already-selected requests
  may finish evidence while disabled; pending review remains available.
- References use ASCII validation, surrounding-whitespace trimming and uppercase
  case folding, preserving punctuation. Global normalized uniqueness is conservative
  across methods. Cancelled/rejected references remain reserved; no reuse override.
- Telegram photo or JPEG/PNG/PDF receipt metadata, maximum 10 MiB, is retained using
  file_id/file_unique_id. Forwarded receipts are rejected. Duplicate file IDs are a
  review signal. Secured bounded server downloads check file signatures; no permanent
  receipt binaries or token URLs are stored/exposed. Receipts do not prove payment.
- Approval, access provenance, terminal state and audit commit atomically. Existing
  snapshots, registration grant fields, usage and exam history remain unchanged.
  Already-lifetime accounts receive no duplicate grant. Rejection needs a meaningful
  user-visible reason; no internal review notes are sent accidentally.
- Notifications use a durable unique outbox and run after commit, outside database
  transactions. Five automatic retries plus admin retry preserve business state.
  Ambiguous Telegram sends may repeat a message, never a grant or review transition.
- TELEGRAM_ADMIN_ID is optional runtime configuration for notifications only. The
  development ID was discovered once from a new non-forwarded private /start in an
  explicit window; no application discovery or first-user enrollment exists.
  Telegram identity never authenticates web admin or grants database permissions.
- Payment audit is append-only through application flows, filtered and paginated.
  Sensitive financial evidence remains in secured review, not audit metadata/logs.
- V10–V12 are additive; V1–V9 remain intact. No production deployment, production
  webhook, automatic financial verification or compressed Phase 8 work is included.

## 21. Compressed Phase 8 decisions

- Preserve V1 business rules and applied V1-V12 migrations. V13 adds only an outbox
  claim token to fence stale completion and recover exhausted abandoned claims.
- Bound local admin login attempts by direct peer; retain BCrypt, session rotation,
  CSRF and ADMIN authorization. Add script-free CSP, safe error recovery, 30-minute
  sessions and configurable secure cookies for the later HTTPS deployment.
- Reject forwarded contacts, malformed callback shapes, contradictory upload MIME
  types and unsafe ZIP entry paths. Keep all existing input and expansion limits.
- Honor bounded Telegram retry-after values without holding database transactions.
  Network failures never roll back already-committed usage, evidence or grants.
- Verify localization parity, restart persistence, rollback, concurrent retry claims,
  adversarial imports, complete student/admin journeys and repeated regressions.
- Patch embedded Tomcat to 10.1.60 without adding dependencies. Document framework
  support and operational limitations in PHASE8_REVIEW.md for Phase 9.
- No production deployment, DNS, HTTPS provisioning, production database or webhook
  is part of this phase. A live human receipt check remains optional.

## 22. Compressed Phase 9 decisions

- One explicit transport: enabled POLLING starts the existing worker; enabled
  WEBHOOK exposes POST `/api/telegram/webhook`. Both use the same core handler.
- Require the Telegram secret header, bound inbound JSON, preserve private-chat
  and ownership checks, and exempt only the exact webhook POST from CSRF.
  Process before ACK; use domain idempotency for retries without an in-memory queue.
- Prod composes JDBC fields from environment, requires TLS, uses a small Hikari
  pool, binds Render PORT on all interfaces and forces secure session cookies.
- Flyway owns V1–V13 without modification. No Phase 9 migration. Inspect Supabase
  before first startup; disable its unused Data API before creating app tables.
  Unexpected schemas are not automatically baselined or overwritten.
- Java 21 multistage Docker build runs isolated tests and uses a non-root runtime.
  All durable state remains PostgreSQL or Telegram file IDs, never container files.
- Environment-only scripts configure/inspect webhook, inspect database readiness,
  back up public application data and restore only to explicitly selected empty
  local drill databases. Runbook covers rollback and future credential rotation.
- Keep current test credentials unchanged. HMAC replacement after registration
  requires a planned identity migration. No paid-production readiness claim.
- The Phase 9 request explicitly authorizes committing and pushing passing changes
  to main, superseding section 16's default no-automatic-commit/push rule for this
  phase. Pause at the specified Render and live Telegram human checkpoints.
- Advanced/optional Phase 10 features remain out of scope.
