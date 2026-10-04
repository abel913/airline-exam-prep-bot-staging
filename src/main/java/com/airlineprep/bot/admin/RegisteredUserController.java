package com.airlineprep.bot.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class RegisteredUserController {
    private final JdbcTemplate jdbc;
    public RegisteredUserController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
}
