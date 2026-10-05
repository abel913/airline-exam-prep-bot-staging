package com.airlineprep.bot.admin;

import java.util.List;

import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes one registered student and only that student's dependent state. */
@Service
public class RegisteredUserRemovalService {
    public record RemovedUser(long id, long telegramUserId) {}

    private final JdbcTemplate jdbc;
    private final AdminChangeService changes;

    public RegisteredUserRemovalService(JdbcTemplate jdbc, AdminChangeService changes) {
        this.jdbc = jdbc;
        this.changes = changes;
    }

    @Transactional
    public java.util.Optional<RemovedUser> remove(long userId, String actor) {
        if (userId <= 0) return java.util.Optional.empty();

        List<Long> telegramIds = jdbc.query(
            "SELECT telegram_user_id FROM bot_users WHERE id=? FOR UPDATE",
            (rs, row) -> rs.getLong(1), userId);
        if (telegramIds.isEmpty()) return java.util.Optional.empty();

        // The audit row intentionally survives user removal and contains no phone or identity hash.
        changes.record(actor, "REGISTERED_USER_REMOVED", "bot_user:" + userId, "registered", "removed");

        // practice_sessions references both bot_users and its current delivery.
        jdbc.update("DELETE FROM practice_sessions WHERE user_id=?", userId);
        // Update receipts and progress rows reference the selected user's deliveries.
        jdbc.update("DELETE FROM practice_update_receipts WHERE user_id=?", userId);
        jdbc.update("DELETE FROM practice_usage WHERE user_id=?", userId);
        // Delivery links are self-referential. Clear links only on this user's rows.
        jdbc.update("""
            UPDATE practice_deliveries
            SET next_delivery_id = CASE WHEN next_delivery_id IN
                    (SELECT id FROM practice_deliveries WHERE user_id=?) THEN NULL ELSE next_delivery_id END,
                review_delivery_id = CASE WHEN review_delivery_id IN
                    (SELECT id FROM practice_deliveries WHERE user_id=?) THEN NULL ELSE review_delivery_id END
            WHERE user_id=?
              AND (next_delivery_id IN (SELECT id FROM practice_deliveries WHERE user_id=?)
                OR review_delivery_id IN (SELECT id FROM practice_deliveries WHERE user_id=?))
            """, userId, userId, userId, userId, userId);
        jdbc.update("DELETE FROM practice_deliveries WHERE user_id=?", userId);

        // Mock answers depend on attempts; attempts themselves reference the user and exam entitlement.
        jdbc.update("DELETE FROM mock_items WHERE attempt_id IN (SELECT id FROM mock_attempts WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM mock_attempts WHERE user_id=? OR active_user_id=?", userId, userId);

        // Payment notifications, audit events and grants are scoped to the selected user's request IDs.
        jdbc.update("DELETE FROM payment_notifications WHERE request_id IN (SELECT id FROM payment_requests WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id IN (SELECT id FROM payment_requests WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM lifetime_access_grants WHERE user_id=?", userId);
        jdbc.update("DELETE FROM payment_requests WHERE user_id=? OR open_user_id=?", userId, userId);

        // V18 makes practice history and grants children of the per-exam entitlement.
        jdbc.update("DELETE FROM access_entitlements WHERE user_id=?", userId);

        int removed = jdbc.update("DELETE FROM bot_users WHERE id=?", userId);
        if (removed != 1) throw new IllegalStateException("Locked registered user disappeared during removal.");
        return java.util.Optional.of(new RemovedUser(userId, telegramIds.getFirst()));
    }
}
