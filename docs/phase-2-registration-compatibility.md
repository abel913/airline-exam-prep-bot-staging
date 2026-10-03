# Phase 2 Telegram Registration Compatibility Contract

Source of truth: Spring registration code in `src/main/java/com/airlineprep/bot/user/RegistrationService.java`, `src/main/java/com/airlineprep/bot/telegram/TelegramUpdateHandler.java`, and `RegistrationPresenter.java`; localization in `src/main/resources/messages*.properties`; schema in Flyway V1–V3. This contract is staging-only and must be reviewed before Edge implementation.

## Start and state

- A private `/start` creates one `bot_users` row if absent with `registration_status=LANGUAGE_REQUIRED`; repeated `/start` shows the existing state. A completed user receives the existing free-access completion text and main menu.
- Incomplete users resume the step implied by `registration_status`: language, active exam choices, or contact-share prompt. The stored values remain `LANGUAGE_REQUIRED`, `EXAM_TYPE_REQUIRED`, `PHONE_REQUIRED`, and `COMPLETED`.
- Webhook updates are accepted only from a positive, non-bot Telegram sender in the matching private chat. Group updates are ignored.

## Language

- Supported values are `en` and `am`; callback identifiers are exactly `lang:en` and `lang:am`.
- A language choice is persisted only while state is `LANGUAGE_REQUIRED`; invalid/repeated callbacks do not overwrite a later state.
- The next state is `EXAM_TYPE_REQUIRED`. Both localization bundles use the same semantic welcome, exam, phone, validation, and completion behavior.

## Exam type

- Only records with `exam_types.active=true` are listed, ordered by `display_order`, then `id`.
- Callback identifier is `exam:<positive decimal id>`. Only an active exam is accepted, and only while the user is `EXAM_TYPE_REQUIRED`.
- With no active exams the bot sends the localized `registration.noExams` text (“Registration configuration is temporarily unavailable…” in English). A missing/inactive selected exam later resets the user to exam selection.
- A valid choice is stored in `selected_exam_type_id`, changes state to `PHONE_REQUIRED`, and displays the existing contact instruction.

## Phone and contact verification

- Only Telegram's `message.contact` path can register a phone. Forwarded contacts are rejected. `contact.user_id` must be present and equal the positive sender Telegram ID; otherwise the localized own-contact message is shown.
- Typed non-command text during `PHONE_REQUIRED` never enters phone normalization or identity creation. It returns the manual-phone warning and retains the contact-share keyboard.
- Exact normalizer: input must be non-null, at most 32 characters, and match `[+0-9 ()-]+`; remove spaces, parentheses, and hyphens. Accept `0[79][0-9]{8}` and prefix `+251` after dropping the leading zero; accept `251[79][0-9]{8}` and prefix `+`; accept `\\+251[79][0-9]{8}` unchanged. Canonical representation is `+251` followed by the nine mobile digits.
- Invalid formats return `registration.invalidPhone`; the contact keyboard remains. Another user's contact returns `registration.ownContact`; the contact keyboard remains.

## Phone identity and registration transaction

- `PHONE_IDENTITY_HMAC_KEY` is Base64-decoded to raw key bytes and must contain at least 32 bytes when Telegram is enabled. Hash is HMAC-SHA-256 over UTF-8 `phone:` + canonical phone, encoded as lowercase hexadecimal. The persisted setting fingerprint is HMAC-SHA-256 over UTF-8 `airline-exam-phone-key-v1`, also lowercase hex, under the same key.
- A pre-existing different key fingerprint blocks registration. Never log the raw phone, key, fingerprint, or complete phone hash.
- On first successful own-contact submission, atomically persist the user's phone hash, completion timestamp, `COMPLETED` status, and one `FREE` entitlement with the current app-settings snapshot (`free_practice_limit`, `free_mock_limit`, `questions_per_mock`), zero usage, and `grant_source=REGISTRATION`.
- `app_settings(id=1)` is locked `FOR UPDATE` across onboarding/settings changes. Schema uniqueness enforces one Telegram identity, one phone identity per user identity, and one entitlement per user/phone identity. Repeated contacts on a completed user return completed state and must not create another entitlement. A phone used by another Telegram identity is rejected as duplicate.
- No durable Telegram update-ID table exists. Existing state transitions, app-settings serialization, and database uniqueness are the current idempotency boundary; do not add a migration unless integration testing demonstrates an uncovered duplicate side effect.

## Responses and scope

- Preserve button labels, callback IDs, localization semantics, and the persistent `request_contact=true` keyboard after invalid input. On successful registration remove the keyboard and send the completion/menu response.
- Phase 2 handles registration only. It does not migrate practice, progress/history, mocks, payments, or admin operations. Do not claim those paths are available through Edge.

## Pre-cutover acceptance

- Synthetic contract tests cover authenticated Telegram updates, state transitions, active exams, rejected typed phones, contact ownership, exact normalization/HMAC fixtures, transaction atomicity, duplicate update/contact behavior, and secret-safe logs.
- Compare staged Edge behavior and staged database effects to this contract before changing the test bot webhook. Keep the Spring webhook live as the rollback destination.
