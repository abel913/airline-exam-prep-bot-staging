# Phase 4 payment compatibility contract

This contract is based on the existing Spring Telegram flow, payment services,
admin review service, PostgreSQL schema, and notification outbox. Edge must
preserve the student-side states while Render remains the human approval UI.

## Payment enablement, price, and methods

- New payment requests and method selection require both
  `app_settings.payment_enabled` and `app_settings.manual_payment_enabled`.
  When disabled, the bot displays the existing disabled message; already
  submitted requests remain reviewable.
- Price is the `app_settings.lifetime_price` and currency is the stored
  `app_settings.currency`, snapshotted on request creation. This is one-time
  lifetime access; approval does not alter historical usage snapshots.
- Only active `payment_methods` are listed. Method ID is revalidated against
  the database and must remain active. The request snapshots method type,
  display name, account name, destination, and instructions when selected.
- Verified staging configuration is payment-enabled and manual-payment-enabled,
  priced at 50 ETB, with one active test-only method whose instructions prohibit
  sending real money. No real financial destination is configured.

## Reference, receipt, and request state

- A reference is required, trimmed, 3–100 characters, and must match
  `[A-Za-z0-9][A-Za-z0-9._/-]{2,99}`. It is normalized to uppercase and is
  globally unique by `payment_requests.normalized_reference`, including
  cancelled requests. The database unique constraint is the final concurrency
  guard.
- A receipt must be a non-forwarded Telegram photo or document with valid
  Telegram `file_id`, `file_unique_id`, size 1–10 MiB, accepted MIME type
  (JPEG, PNG, PDF), and matching document extension. Photos are stored as JPEG
  metadata. Spring stores identifiers and metadata; it does not persist a
  physical receipt on the Render filesystem.
- The states are `SELECT_METHOD`, `AWAITING_REFERENCE`,
  `AWAITING_RECEIPT`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`, and
  `CANCELLED`. A request is created under a unique per-user creation key, and
  a unique `open_user_id` permits only one open request per student.
- A receipt submission moves to `PENDING_REVIEW`, sets `submitted_at`, writes
  audit events, and enqueues a unique `ADMIN_PENDING` notification. Receipt
  replay with identical metadata returns the existing request.
- Request/status/history lookups must filter by the Telegram user's own
  registered account. History is newest first, limited to five.

## Review and access grants

- Only Render's authenticated admin payment review UI approves or rejects.
  Approval is transactional and only accepts `PENDING_REVIEW`. It writes one
  `lifetime_access_grants` row per request/user (both unique), switches the
  existing entitlement to `LIFETIME`, records audit events, and enqueues one
  `USER_APPROVED` notification. Repeated approval of an already approved
  request returns without another grant or notification.
- Rejection requires a nonblank reason of at most 500 characters and only
  accepts `PENDING_REVIEW`. It grants no entitlement, records the reason and
  audit event, and enqueues one `USER_REJECTED` notification. The student may
  start a new request with a new reference; references already submitted remain
  reserved.
- `payment_notifications` is a durable outbox with unique
  `(request_id, kind)`, delivery attempts, leases, retries, and manual retry
  through the admin portal. Existing dispatcher is the delivery mechanism.
- Admin notice is sent to the configured Telegram admin ID. Its message
  contains request ID, snapshotted amount/currency and method, submission time,
  and an instruction to review in the secured web admin; it does not include
  receipt contents. User approval/rejection feedback is sent to that user.

## Staging Edge and admin boundary

- Staging `telegram-webhook` Edge version 6 is ACTIVE and wires the Phase 4
  student payment flow. The existing schema supports payment requests, method
  snapshots, receipt metadata, unique references, lifetime grants, audit, and
  notification outbox operations. No new schema was needed for Phase 4.
- The Edge student flow must never grant premium from a callback or message.
  It can only read the server-side entitlement after the Render admin approves.
- Authenticated Render admin review remains responsible for approving or
  rejecting requests. Staging approval and rejection E2E checks passed;
  approval granted lifetime access, while rejection left the separate test
  user FREE without a grant.
- Required hosted Edge secret names were verified for staging, including
  `TELEGRAM_ADMIN_ID`. Secret values are intentionally not documented or
  logged.
