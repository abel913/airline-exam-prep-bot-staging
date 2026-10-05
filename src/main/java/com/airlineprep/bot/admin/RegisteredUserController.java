package com.airlineprep.bot.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class RegisteredUserController {
    private static final Logger log = LoggerFactory.getLogger(RegisteredUserController.class);
    private final JdbcTemplate jdbc;
    private final RegisteredUserRemovalService removal;
    public RegisteredUserController(JdbcTemplate jdbc, RegisteredUserRemovalService removal) {
        this.jdbc = jdbc;
        this.removal = removal;
    }

    @GetMapping("/admin/users")
    public String users(Model model) {
        model.addAttribute("users", jdbc.query("""
            SELECT u.id,u.telegram_user_id,u.preferred_language,u.registration_status,u.phone_e164,
              COALESCE(u.phone_verification_status,'NOT_CAPTURED') phone_verification_status,
              e.name current_exam,u.created_at,
              (SELECT COUNT(*) FROM access_entitlements a WHERE a.user_id=u.id) exam_count
            FROM bot_users u LEFT JOIN exam_types e ON e.id=u.selected_exam_type_id
            ORDER BY u.created_at DESC,u.id DESC
            """, (r,n) -> new UserRow(r.getLong("id"),r.getLong("telegram_user_id"),r.getString("preferred_language"),
                r.getString("registration_status"),r.getString("phone_e164"),r.getString("phone_verification_status"),
                r.getString("current_exam"),r.getTimestamp("created_at").toInstant(),r.getLong("exam_count"))));
        return "admin/users";
    }

    @GetMapping("/admin/users/{id}/remove")
    public String confirmRemoval(@PathVariable long id, Model model) {
        RemovalSummary summary = removalSummary(id);
        if (summary == null) return "admin/user-not-found";
        model.addAttribute("summary", summary);
        model.addAttribute("telegramUsername", "Not stored by this application");
        return "admin/user-remove-confirm";
    }

    @PostMapping("/admin/users/{id}/remove")
    public String remove(@PathVariable long id, @RequestParam(required=false) String confirmation,
                         org.springframework.security.core.Authentication authentication,
                         RedirectAttributes flash, Model model) {
        if (!"REMOVE".equals(confirmation)) {
            RemovalSummary summary = removalSummary(id);
            if (summary == null) return "admin/user-not-found";
            model.addAttribute("summary", summary);
            model.addAttribute("telegramUsername", "Not stored by this application");
            model.addAttribute("error", "Type REMOVE exactly to confirm permanent deletion.");
            return "admin/user-remove-confirm";
        }
        String actor = authentication == null ? "unknown-admin" : authentication.getName();
        try {
            var removed = removal.remove(id, actor);
            if (removed.isEmpty()) {
                flash.addFlashAttribute("error", "User not found or already removed.");
            } else {
                flash.addFlashAttribute("success", "Registered user removed successfully.");
            }
            return "redirect:/admin/users";
        } catch (DataAccessException | org.springframework.transaction.TransactionException | IllegalStateException exception) {
            log.error("Registered user removal failed for userId={}; transaction rolled back", id, exception);
            flash.addFlashAttribute("error", "User could not be removed. No changes were committed.");
            return "redirect:/admin/users";
        }
    }

    private RemovalSummary removalSummary(long id) {
        var rows = jdbc.query("""
            SELECT u.id,u.telegram_user_id,u.phone_e164,u.phone_verification_status,e.name current_exam,u.created_at,
              (SELECT COUNT(*) FROM access_entitlements a WHERE a.user_id=u.id) activated_exams,
              (SELECT COUNT(*) FROM practice_usage p WHERE p.user_id=u.id) practice_progress,
              (SELECT COUNT(*) FROM practice_deliveries d WHERE d.user_id=u.id AND d.selected_option IS NOT NULL) practice_answers,
              (SELECT COUNT(*) FROM mock_attempts m WHERE m.user_id=u.id) mock_attempts,
              (SELECT COUNT(*) FROM payment_requests p WHERE p.user_id=u.id) payment_requests,
              (SELECT COUNT(*) FROM lifetime_access_grants g WHERE g.user_id=u.id) lifetime_grants
            FROM bot_users u LEFT JOIN exam_types e ON e.id=u.selected_exam_type_id WHERE u.id=?
            """, (rs,n) -> new RemovalSummary(rs.getLong("id"), rs.getLong("telegram_user_id"),
                maskPhone(rs.getString("phone_e164")),
                rs.getString("phone_verification_status") == null ? "NOT_CAPTURED" : rs.getString("phone_verification_status"),
                rs.getString("current_exam"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("activated_exams"), rs.getLong("practice_progress"), rs.getLong("practice_answers"),
                rs.getLong("mock_attempts"), rs.getLong("payment_requests"), rs.getLong("lifetime_grants")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "Not captured before Phase 5";
        int visible = Math.min(4, phone.length());
        return "•".repeat(Math.max(4, phone.length() - visible)) + phone.substring(phone.length() - visible);
    }

    @GetMapping("/admin/users/{id}")
    public String user(@PathVariable long id, Model model) {
        var users = jdbc.query("""
            SELECT u.id,u.telegram_user_id,u.preferred_language,u.registration_status,u.phone_e164,
              COALESCE(u.phone_verification_status,'NOT_CAPTURED') phone_verification_status,
              e.name current_exam,u.created_at
            FROM bot_users u LEFT JOIN exam_types e ON e.id=u.selected_exam_type_id WHERE u.id=?
            """, (r,n) -> new UserDetail(r.getLong("id"),r.getLong("telegram_user_id"),r.getString("preferred_language"),
                r.getString("registration_status"),r.getString("phone_e164"),r.getString("phone_verification_status"),
                r.getString("current_exam"),r.getTimestamp("created_at").toInstant()), id);
        if (users.isEmpty()) return "admin/user-not-found";
        model.addAttribute("user",users.getFirst());
        model.addAttribute("entitlements",jdbc.query("""
            SELECT e.id,e.code,e.name,a.access_level,a.practice_limit,a.practice_used,a.mock_limit,a.mocks_used,a.questions_per_mock,
              (SELECT COUNT(*) FROM practice_usage p WHERE p.user_id=a.user_id AND p.exam_type_id=a.exam_type_id) answered,
              (SELECT COUNT(*) FROM mock_attempts m WHERE m.user_id=a.user_id AND m.exam_type_id=a.exam_type_id AND m.status IN ('SUBMITTED','EXPIRED')) mock_history
            FROM access_entitlements a JOIN exam_types e ON e.id=a.exam_type_id
            WHERE a.user_id=? ORDER BY e.display_order,e.id
            """,(r,n)->new ExamAccess(r.getString("code"),r.getString("name"),r.getString("access_level"),r.getInt("practice_limit"),
                r.getInt("practice_used"),Math.max(0,r.getInt("practice_limit")-r.getInt("practice_used")),r.getInt("mock_limit"),
                r.getInt("mocks_used"),Math.max(0,r.getInt("mock_limit")-r.getInt("mocks_used")),r.getInt("questions_per_mock"),
                r.getLong("answered"),r.getLong("mock_history")), id));
        model.addAttribute("payments",jdbc.query("""
            SELECT p.id,e.code exam_code,p.status,p.amount,p.currency,p.created_at,p.reviewed_at
            FROM payment_requests p JOIN exam_types e ON e.id=p.target_exam_type_id
            WHERE p.user_id=? ORDER BY p.id DESC
            """,(r,n)->new ExamPayment(r.getLong("id"),r.getString("exam_code"),r.getString("status"),r.getBigDecimal("amount"),
                r.getString("currency"),r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toInstant()), id));
        return "admin/user-detail";
    }

    public record UserRow(long id,long telegramId,String language,String status,String phone,String verification,String currentExam,
                          java.time.Instant registered,long examCount) {}
    public record UserDetail(long id,long telegramId,String language,String status,String phone,String verification,String currentExam,
                             java.time.Instant registered) {}
    public record ExamAccess(String code,String name,String tier,int practiceLimit,int practiceUsed,int practiceRemaining,
                             int mockLimit,int mocksUsed,int mocksRemaining,
                             int questionsPerMock,long answered,long mockHistory) {}
    public record ExamPayment(long id,String examCode,String status,java.math.BigDecimal amount,String currency,
                              java.time.Instant created,java.time.Instant reviewed) {}
    public record RemovalSummary(long id,long telegramId,String maskedPhone,String verification,String currentExam,
                                 java.time.Instant registered,long activatedExams,long practiceProgress,long practiceAnswers,
                                 long mockAttempts,long paymentRequests,long lifetimeGrants) {}
}
