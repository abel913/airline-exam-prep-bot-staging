# Phase 4 mock compatibility contract

This contract records the existing Spring behavior from `MockAttemptService`,
`StudentQuestionSelector`, `MockScoringService`, and their Telegram presenter
and tests. The Edge implementation must preserve these rules.

## Opening and attempt creation

- `MockAttemptService.prepare` locks the student and the shared settings row.
- The creation key is required and must match `[a-zA-Z0-9-]{1,64}`. A
  repeated key returns the existing attempt.
- Preparing an attempt does not increment `access_entitlements.mocks_used`.
- An active attempt is returned for resume; a second active attempt is prevented
  by the unique `mock_attempts.active_user_id` constraint.
- New attempt question count is the user's stored
  `access_entitlements.questions_per_mock` snapshot. The verified fresh
  `staging-test` user has a five-question snapshot and a free allowance of two.
- A free user at the limit cannot prepare a new attempt. Lifetime users are not
  blocked by this allowance check.
- The selector requires a published logical question, current version, active
  exam and category, matching exam type, and `mock_pool=true`. The current
  Spring selector does not apply `free_pool`/`premium_pool` to mocks; the
  eligibility behavior is based on `mock_pool` and the grant limit.
- It selects the full configured count, randomized. If fewer are available,
  it creates no partial attempt and reports the existing `mock.empty` error.
- Each selected logical question and exact `question_versions.id` are stored
  in `mock_items` at creation, with stable sequence numbers. Review reads that
  stored frozen version and its version-specific options/explanation.

## Timer, answers, and allowance

- A prepared attempt has `READY`, no start/deadline, and a null cursor 0.
- Opening its first item changes it to `IN_PROGRESS`, stores server
  `started_at`, and sets `deadline_at` from the stored duration snapshot.
  Staging's current `mock_duration_minutes` is null, meaning untimed.
- Remaining time is calculated from server time and the stored deadline.
  Expiration is applied on the next student interaction at or after the
  deadline; that action finalizes the attempt as `EXPIRED`. A late answer is
  not recorded.
- Answers require an owned attempt for the currently selected exam, an active
  attempt in `IN_PROGRESS`, an item belonging to that attempt, and an option
  belonging to the frozen version.
- The first accepted answer stores `first_answer_at` and increments
  `mocks_used` once for a free user. Changing/re-answering uses an item answer
  revision and does not consume another attempt. Revision-mismatched stale
  callbacks return the current item state.
- Expiration with no submitted answers does not increment allowance because
  allowance is charged on first answer, not attempt creation/finalization.
- Student rows and the singleton settings row are transaction-locked. The DB
  unique constraints enforce one active attempt, unique attempt creation key,
  unique question per attempt, and unique sequence per attempt.

## Resume, scoring, results, and history

- Resume returns the same active attempt, frozen item ordering, existing
  selections, cursor/first unanswered position, and time remaining from the
  server deadline. It does not prepare a new attempt.
- Submitting requires at least one answer. A submitted attempt becomes
  `SUBMITTED`; an expired one becomes `EXPIRED`. Both have a final score and
  clear `active_user_id`.
- Scoring is calculated against each frozen version's option correctness.
  Counts include total, correct, incorrect, unanswered, percentage rounded to
  two decimal places, and per-category equivalents. There is no pass/fail rule.
- Expired zero-answer attempts are included in admin storage/history, but
  student progress and recent completed-mock summaries require
  `first_answer_at IS NOT NULL`.
- Result/review reads stored mock item selections and exact frozen versions;
  archived/currently edited questions do not replace the historical snapshot.
- The current Spring `StudentFlow`, `StudentPresenter`, and mock tests define
  the Telegram menu, resume, navigation, result, review, explanation, and
  history presentation. Edge must keep the same ownership and status rules.

## Staging observations

- The verified fresh `staging-test` user has `questions_per_mock=5`,
  `mock_limit=2`, and `mocks_used=0` before the Phase 4 mock E2E.
- The verified staging pool has five published free mock questions, five
  published premium mock questions, and two eligible categories.
- The staging mock E2E passed preparation, one-time allowance consumption,
  answer revisions, navigation, submission, scoring, review, and history.
- The `telegram-webhook` Edge Function is deployed to staging at version 6,
  ACTIVE, with JWT verification disabled for Telegram's custom webhook-secret
  header. Its entrypoint wires the Phase 4 mock and payment flows.
