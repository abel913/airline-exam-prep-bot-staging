# Phase 3 Practice Compatibility Contract

This contract records the existing Spring Boot behavior that the Supabase Edge implementation must preserve. It was written from `PracticeService`, `StudentQuestionSelector`, `StudentProgressService`, `StudentInsights`, `StudentAccess`, `StudentPresenter`, `StudentFlow`, `QuestionService`, and `QuestionValidationService`, and checked against the staging schema at migration V16.

## Registration and menu access

- Student practice actions enter through the private-chat Telegram handler and `StudentFlow`.
- `StudentAccess.lock` locks singleton `app_settings` row 1, then requires a registered `bot_users` row with `registration_status = 'COMPLETED'` and its one `access_entitlements` snapshot. An absent or incomplete user receives `student.register`.
- The completed-user menu exposes Practice, Progress, practice history, category insights, mock/payment entries, and Help. Phase 3 implements the practice, progress, practice-history, and insight paths; mock exams and payment flows remain out of scope.
- Spring sends plain text, not HTML. Question text/options are followed by answer buttons; explanation and correct answer are only shown after an answer or through answered-history review.

## Eligible questions and category navigation

Spring selects the logical question's `current_version_id`, joining its version, active exam, and active category. The version's category must belong to the version's exam, and the version exam must equal the user's selected exam. Categories are listed in ID order, in pages of 20; the UI also offers all categories and review.

For ordinary practice the SQL requires `free_pool = true`, or `premium_pool = true` when the student's snapshot has `access_level = 'LIFETIME'`. A free user never sees premium-only content. Review requires an existing `practice_usage` row for that student and logical `question_id`; ordinary practice excludes such rows. The selector orders candidates by their greatest prior delivery ID for that user/question, then by question ID. This is deterministic least-recently-delivered ordering, not random ordering.

Every candidate must have `questions.status = 'PUBLISHED'`, an active exam, an active matching category, and the current version selected by the question. Thus DRAFT, REVIEWED, ARCHIVED, wrong-exam, inactive-exam/category, and superseded versions are excluded from new deliveries. Spring publication validates complete content, active taxonomy, at least two distinct nonblank options, exactly one correct option, a nonblank explanation, source type/title, difficulty, at least one pool, and rejects `BLOCKED` and `UNKNOWN_REVIEW_REQUIRED` rights statuses. Runtime selection relies on the published lifecycle and does not re-evaluate `use_status`.

## Versioning and deliveries

- A delivery stores `user_id`, logical `question_id`, exact `version_id`, optional `category_filter`, selected option, and timestamps. Composite foreign keys require the version to belong to that logical question and the selected option to belong to that version.
- An answer, result, progress entry, and history view use the frozen delivery version. A later edit creates a new version and returns the logical question to DRAFT; it does not change existing deliveries. Archiving removes a question from future selection but leaves its versions and practice history available.
- The first request for a category/all-categories/review selection creates the per-user `practice_sessions` row and sets its `current_delivery_id`. There is one session per user. If the session's current delivery is unanswered and the category matches, a category-entry callback resumes that delivery; review resumes it only if the logical question was already used.
- A Next/Skip callback uses the source delivery's `next_delivery_id`; a Review callback uses `review_delivery_id`. A missing link creates a new delivery and stores the link. This provides repeat-safe navigation. The session points to the newest delivery. There is no explicit session-end operation; the session is reused, and an answered current delivery is replaced on the next selection.
- Displaying a question creates a delivery but does not create `practice_usage` or increment `practice_used`.

## Answers, retries, and skip

- Valid answer callback: `p:a:<owned-delivery-id>:<option-position>`. The handler resolves the delivery by both ID and current user's database ID, loads its frozen version, and resolves the option from that version. Invalid IDs/options and another user's delivery return `student.invalid` without a write.
- The first answer writes `selected_option` and `answered_at`. Correctness is the selected option's stored `correct` flag. The result shows Correct/Incorrect, the correct option letter, the frozen explanation, and the remaining allowance.
- An already-answered delivery returns its original stored choice/result. A later different callback cannot change the answer or charge again. A repeated logical question, including a new version of the same `question_id`, does not consume another free slot because `practice_usage` is unique on `(user_id, question_id)`.
- A first answer to a previously unused logical question inserts one `practice_usage` row pointing to that answer delivery and increments `practice_used` only for a FREE entitlement. LIFETIME users do not spend free slots. The application serializes student actions on `app_settings` row 1; the usage primary key and unique first-delivery constraint provide database backstops.
- Skip is the unanswered delivery's `p:n:<delivery-id>` action. It does not set an answered/skipped field, does not create usage, and does not change progress/history. It selects and links the next ordinary question. Repeating the callback returns the linked delivery. If no more content exists, Spring returns `practice.empty`; if a FREE snapshot is exhausted, it returns `practice.limit` and still permits review.

## Entitlements and limits

- Registration snapshots `free_practice_limit`, `free_mock_limit`, and `questions_per_mock` from `app_settings` into a unique `access_entitlements` row. Staging currently stores a free-practice default of 100; the runtime rule uses each user's stored `practice_limit` and `practice_used`, not a hardcoded value.
- A FREE user at zero remaining cannot start ordinary practice or answer a previously displayed, still-unanswered delivery. Previously answered review remains available. A LIFETIME grant bypasses the free-practice limit and can use premium-pool content, while preserving the original entitlement limits/counters. Premium-pool availability alone never grants access.
- The only current access levels are `FREE` and `LIFETIME`. The existing admin approval flow sets a lifetime grant in the shared PostgreSQL database; the Edge path must read current entitlement state on each transaction so admin changes take effect without a Render student request.

## Progress, weak areas, and history

- Progress counts `practice_usage` rows by user and frozen version exam, joining each row's first delivery and selected option. It displays unique logical questions answered, correct, incorrect (`answered - correct`), and accuracy rounded to two decimal places (`round(correct * 10000 / answered) / 100`). With zero answers, accuracy is 0.
- Category progress groups those first-answer usage rows by frozen category ID/name, and shows category correct/answered and the same rounded percentage. The page displays up to 20 category groups. There are no extra category metrics beyond these counts/accuracy.
- Weak-area insights use first answers only. A category needs at least five unique answered questions; under 60% is “Needs more practice,” 80% or higher is “Stronger area,” and the middle range is “Keep practicing.” Recommendations are offered only if the category has an eligible, unanswered current question. Categories are ordered by latest delivery ID, then category ID. No qualifying recommendation returns to category selection.
- Practice history selects answered deliveries for the user and selected exam, ordered by delivery ID descending. It fetches six rows to show five plus a next-page indicator; page size is five. Each row shows the delivery timestamp as UTC ISO text, the frozen version's category name, and that delivery's correct/incorrect outcome. Answered-history review uses the same owned delivery and frozen version. History does not require the logical question to remain published or its category/exam to remain active.
- Progress also includes completed mock count, mock allowance, and up to ten recent mock results in current Spring. Phase 3 preserves those read-only summary fields for display; it does not move mock creation, delivery, timing, scoring, or mock history.

## Storage and concurrency

Staging is at Flyway V16. The existing schema already provides:

- frozen version references and composite version/option foreign keys;
- one session per user;
- unique usage by `(user_id, question_id)`;
- unique usage `first_delivery_id` and composite ownership/reference foreign key;
- delivery indexes by user/ID and user/question/ID;
- the singleton `app_settings` row used by Spring to serialize student actions and admin publication/settings changes.

Those constraints are sufficient for one-use accounting and linked Next/Review navigation. They do not record Telegram `update_id`, so replay of a category-entry update after a later action can create a second delivery. Phase 3 therefore adds `practice_update_receipts`, mapping each successful delivery-producing update to its owned delivery. Multiple updates may resume the same delivery, so a single source-update column would not cover delayed replays. The update ID primary key and composite delivery/owner/question foreign key protect the mapping. The receipt is written in the same transaction for new, resumed, and linked deliveries. PostgreSQL RLS is enabled and public/anon/authenticated table privileges are revoked in the migration. This requires forward-only migration V17; V1–V16 remain unchanged.

## Deferred mock interface

Mock creation, resume, scoring, results, and payment callbacks remain unavailable on the Edge path during Phase 3. Existing mock totals remain visible as read-only progress summaries. Spring's active-mock resume row and clickable mock-result buttons are deferred with that engine; practice metrics and history preserve Spring behavior.
