# Phase 10 review

Baseline: a58e997, clean main matching origin/main. Live beta is preserved;
implementation and fixtures use isolated databases until deployment acceptance.

## Feature audit and decisions

| Feature | Baseline | Change / impact |
| --- | --- | --- |
| Difficulty | Exists in versions/forms/import | Preserve, add search filter |
| Tags | Missing | Optional canonical comma-separated version metadata, max 8; V14, form/import/filter tests |
| Practice history | Frozen deliveries exist | Owned, paginated history and frozen review; no new events |
| Mock history | Recent summary exists | Owned pagination, status/result/resume; use frozen scores |
| Progress | First-answer totals and categories exist | Minimum 5 first answers/category; weak below 60%, strong at least 80% |
| Recommendations | Missing | Eligible unanswered weak-category content; least recently delivered category, normal fallback |
| Admin analytics | Basic counts exist | On-demand SQL aggregates, UTC windows, bounded pages; no tracking tables |
| Question analytics | Missing | Logical summary explicitly across versions; option breakdown per version; first practice answers and completed mock final answers separate |
| Exports | Import template only | Authenticated bounded aggregate CSV, formula-safe UTF-8 |
| Student overview | Missing | Internal IDs only, no phone/HMAC/Telegram IDs or financial evidence |
| Maintenance | Missing | Persisted setting, audited POST, private Telegram response before business actions |
| Outbox | Durable dispatcher and audited manual retry exist | Safe read-only counts/status/next retry; retain payment-detail retry without duplication |

No new dependencies, no background analytics, no V1–V13 edits, no arbitrary
entitlement override, no automatic difficulty/lifecycle changes. Tags stay within
immutable versions; a normalized taxonomy is unnecessary for eight optional labels.
Maintenance acknowledges valid incoming requests with a localized retry-later
response; it does not queue business commands. Existing timers continue in UTC.

## Verification

Local gates passed on 2026-09-26. Live deployment/acceptance is still pending.

- First full suite: 430 tests; after export/XSS coverage was expanded, package and
  randomized repeated suite (seed 102026) each passed 432 tests. Final code also
  passed all 432 during package before Windows blocked the JAR rename because the
  isolated smoke app was using it. Stopping that process and repackaging succeeded;
  the resulting artifact was then restarted and checked. No test failure remains.
- Five opt-in PostgreSQL checks passed: existing engine, payment and pool recovery,
  plus Phase10PostgresIT and Phase10MigrationIT. **437 distinct Java checks** in
  total, zero failures/errors/skips in the successful runs. The final analytics
  query revision was rechecked with Phase10PostgresIT successfully.
- New PostgreSQL databases were confined to loopback port 55439. Tested clean
  V1–V14 installation and V13-to-V14 upgrade, retaining a prior offer value and
  defaulting maintenance false. Scenarios roll back; no live stress fixtures.
- PostgreSQL analytics exercised 60 questions, three users, 618 answered deliveries
  and three mocks. Query output is bounded; history uses indexed user/id ordering,
  date and version indexes support aggregates. The plan check confirms LIMIT.
  A separate 505-question fixture proves 25-row pages and the 500-row CSV cap.
- Database queries aggregate in SQL. Report pages execute a fixed number of JDBC
  queries, not per-student/per-question Java query loops. Version option counts use
  indexed correlated aggregates within a single bounded statement. Aggregate work
  still grows with data; this is beta sanity evidence, not a high-scale benchmark.
- Tags normalize/deduplicate, reject invalid input, participate in filters and
  survive version changes. Original import headers remain accepted. Tests cover
  empty/one-answer insights, weak thresholds, repeats, archive/revision review,
  owned callbacks, pagination, UTC boundaries, per-version options, payment currency
  separation/idempotency, CSV formula/Unicode handling, authorization and XSS.
- Maintenance prevents /start/practice/mock/payment callback business actions,
  retains allowances/attempts, leaves health/admin accessible and resumes when
  disabled. RestartPersistenceTests reopens the app and verifies maintenance,
  tags and histories alongside all earlier persisted workflows. Existing audited
  notification retry and claim fencing remain regression-tested unchanged.
- TLS backup of the isolated V14 schema restored into a new local database with
  all 14 migrations, tags, maintenance true and the retained offer. The packaged
  prod-profile application validated the restored schema and reached health UP.
  Existing admin login worked on another restart with bootstrap disabled.
- Headless Edge checked 18 page/viewport combinations at 1440, 768 and 390 pixels,
  input labels, contained horizontal overflow, maintenance banner/toggle and logout.
  Desktop/mobile screenshots were inspected. The final compact filter layout was
  checked again. This is not a full assistive-technology certification.
- Local prod-profile smoke used a 512 MiB JVM basis and 50% heap; startup about
  10 seconds and observed working set about 298 MiB. The first Phase 10 artifact
  grew by about 36 KiB from the 82,631,849-byte baseline. No dependency was added.
  These are local measurements, not Render cold-start or memory guarantees.
- Source/tests/docs/scripts and packaged application scans found none of the
  existing private credential values. No phone/HMAC/receipt/reference fields are
  selected into new analytics or exports. V1–V13 and pom.xml remain unchanged.
- A fresh real Supabase backup was verified before preparing the Phase 10 deploy.
  It remains ignored/private. The Phase 9 sample and live state are untouched.

## Deployment checkpoint

After the passing local gates, commit/push the authorized changes and confirm
Render's deployment. Then verify live V14, HTTPS health, admin analytics, sample
counts and aggregate CSV, followed by the user's optional Telegram history/insight
check. One existing correct answer must produce insufficient category data, not
a weak classification. Do not claim the full Phase 10 acceptance before this gate.

## Repairs during verification

Fixed a missing GROUP BY in student last-activity aggregation; updated transport
unit-test fixtures for the injected insight service; preserved the legacy import
validation wording; flushed a test transaction before checking JDBC maintenance
state; grouped taxonomy reports by IDs to avoid merging equal display names;
resolved the Windows smoke-process JAR lock. No destructive repair was used.

## Live deployment verification

The user confirmed PHASE 10 DEPLOYED after commit `b90ec9f` was pushed. Independent
Supabase inspection confirms 14 successful migrations, latest V14, and maintenance
false. HTTPS health is UP. Existing private admin credentials still authenticate.
All nine analytics overview/report routes return 200; anonymous analytics/student
and CSV requests redirect to login. Maintenance POST without CSRF is rejected.
The UTF-8 aggregate question CSV retains the original Phase 9 sample and reports
one first practice answer and one correct answer. No sensitive identifier/evidence
fields were found in the report pages or export. The export was read in memory;
no downloaded private CSV file remains. Admin logout succeeded.
A fresh post-migration Supabase public-schema backup was taken over TLS and its
archive verified. It remains private/ignored; the V14 restore drill was isolated.

Pending human confirmation: live Telegram Progress/Practice History/review,
Mock History, insufficient-data category insights and weak-area fallback; safe
Render log summary. The optional live maintenance toggle has not been exercised;
maintenance behavior, recovery and persistence passed locally. No final Phase 10
completion is claimed until the human checkpoint is resolved.
