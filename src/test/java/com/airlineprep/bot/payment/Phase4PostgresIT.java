package com.airlineprep.bot.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import com.airlineprep.bot.access.AccessEntitlementRepository;
import com.airlineprep.bot.access.StudentAccess;
import com.airlineprep.bot.admin.CatalogForm;
import com.airlineprep.bot.admin.CatalogService;
import com.airlineprep.bot.mock.MockAttemptService;
import com.airlineprep.bot.question.Difficulty;
import com.airlineprep.bot.question.QuestionForm;
import com.airlineprep.bot.question.QuestionService;
import com.airlineprep.bot.question.QuestionStatus;
import com.airlineprep.bot.question.UseStatus;
import com.airlineprep.bot.settings.SettingsForm;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.user.RegistrationStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in only: synthetic fixtures in the dedicated local Phase 4 PostgreSQL database. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password="})
@Transactional
class Phase4PostgresIT {
    private static final String DB_NAME = "airline_exam_bot_phase4_test";

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) {
        String host = System.getenv("PHASE4_PG_HOST");
        String name = System.getenv("PHASE4_PG_DATABASE");
        String username = System.getenv("PHASE4_PG_TEST_USER");
        String password = System.getenv("PHASE4_PG_TEST_PASSWORD");
        String port = System.getenv("PHASE4_PG_PORT");
        if (!"127.0.0.1".equals(host) || !DB_NAME.equals(name) || username == null || password == null
                || port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Phase 4 PostgreSQL tests require the dedicated local test database only.");
        }
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/" + DB_NAME;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> username);
        registry.add("spring.flyway.password", () -> password);
    }

    @Autowired CatalogService catalog;
    @Autowired QuestionService questions;
    @Autowired MockAttemptService mocks;
    @Autowired SettingsService settings;
    @Autowired PaymentService payments;
    @Autowired PaymentMethodService methods;
    @Autowired PaymentReviewService review;
    @Autowired PaymentQueries paymentQueries;
    @Autowired AccessEntitlementRepository entitlements;
    @Autowired com.airlineprep.bot.user.RegistrationService registration;
    @Autowired JdbcTemplate jdbc;

    @Test
    void phase4MockPaymentAndBothJavaFlywayMigrationsOnPostgreSQL() throws Exception {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("17");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('16','17') AND success", Integer.class)).isEqualTo(2);

        var before = SettingsForm.from(settings.current());
        settings.update(new SettingsForm(10, 2, 3, new BigDecimal("1.00"), "ETB", true, true,
            "Synthetic local PostgreSQL test", 1), "phase4-local-test");
        String suffix = UUID.randomUUID().toString();
        long exam = catalog.save(false, null, new CatalogForm("phase4test",
            "Synthetic Phase 4 Exam", "", true, 0, null), "phase4-local-test");
        long categoryA = catalog.save(true, null, new CatalogForm("mock-a", "Synthetic Category A", "", true, 0, exam), "phase4-local-test");
        long categoryB = catalog.save(true, null, new CatalogForm("mock-b", "Synthetic Category B", "", true, 1, exam), "phase4-local-test");
        long sender = createSyntheticStudent(exam, "a-" + suffix);
        long otherSender = createSyntheticStudent(exam, "b-" + suffix);

        long[] questionIds = new long[5];
        for (int i = 0; i < questionIds.length; i++) {
            QuestionForm form = syntheticQuestion(exam, i % 2 == 0 ? categoryA : categoryB,
                "Synthetic original mock question " + i, i == 4);
            questionIds[i] = questions.save(null, form, "phase4-local-test");
            questions.transition(questionIds[i], QuestionStatus.REVIEWED, questions.get(questionIds[i]).getRevision(), "phase4-local-test");
            questions.transition(questionIds[i], QuestionStatus.PUBLISHED, questions.get(questionIds[i]).getRevision(), "phase4-local-test");
        }

        var ready = mocks.prepare(sender, "phase4-mock-" + suffix);
        long attempt = ready.attempt().id();
        assertThat(ready.attempt().count()).isEqualTo(3);
        assertThat(mockUsage(sender)).isZero();
        var opened = mocks.open(sender, attempt, 0, false);
        assertThat(opened.secondsRemaining()).isNotNull().isBetween(1L, 60L);
        assertThat(mockUsage(sender)).isZero();

        long frozenVersion = jdbc.queryForObject("SELECT version_id FROM mock_items WHERE attempt_id=? AND sequence_number=0", Long.class, attempt);
        long frozenQuestion = jdbc.queryForObject("SELECT question_id FROM mock_items WHERE attempt_id=? AND sequence_number=0", Long.class, attempt);
        QuestionForm revised = questions.form(frozenQuestion);
        revised.setQuestionText("Synthetic edited wording after attempt creation");
        questions.save(frozenQuestion, revised, "phase4-local-test");
        var frozenView = mocks.open(sender, attempt, 0, false);
        assertThat(frozenView.item().versionId()).isEqualTo(frozenVersion);
        assertThat(frozenView.question().text()).isEqualTo("Synthetic original mock question " + questionIndex(frozenQuestion, questionIds));

        mocks.answer(sender, attempt, 0, 0, 0);
        assertThat(mockUsage(sender)).isEqualTo(1);
        mocks.answer(sender, attempt, 0, 1, 0); // stale duplicate callback
        assertThat(mockUsage(sender)).isEqualTo(1);
        mocks.answer(sender, attempt, 1, 1, 0);
        assertThat(mockUsage(sender)).isEqualTo(1);
        assertThat(mocks.introduction(sender).active().id()).isEqualTo(attempt);
        var resumed = mocks.open(sender, attempt, null, false);
        assertThat(resumed.attempt().id()).isEqualTo(attempt);
        assertThat(resumed.item().sequence()).isEqualTo(2);
        var score = mocks.submit(sender, attempt).score();
        assertThat(score.total()).isEqualTo(3);
        assertThat(score.correct()).isEqualTo(1);
        assertThat(score.incorrect()).isEqualTo(1);
        assertThat(score.unanswered()).isEqualTo(1);
        assertThat(mocks.open(sender, attempt, 0, true).question().versionId()).isEqualTo(frozenVersion);

        var zeroAnswer = mocks.prepare(sender, "phase4-zero-" + suffix);
        mocks.open(sender, zeroAnswer.attempt().id(), 0, false);
        jdbc.update("UPDATE mock_attempts SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", zeroAnswer.attempt().id());
        var expiredEmpty = mocks.answer(sender, zeroAnswer.attempt().id(), 0, 0, 0);
        assertThat(expiredEmpty.attempt().status()).isEqualTo("EXPIRED");
        assertThat(mockUsage(sender)).isEqualTo(1);

        var answered = mocks.prepare(sender, "phase4-late-" + suffix);
        mocks.open(sender, answered.attempt().id(), 0, false);
        mocks.answer(sender, answered.attempt().id(), 0, 0, 0);
        assertThat(mockUsage(sender)).isEqualTo(2);
        jdbc.update("UPDATE mock_attempts SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?", answered.attempt().id());
        assertThat(mocks.answer(sender, answered.attempt().id(), 1, 0, 0).attempt().status()).isEqualTo("EXPIRED");
        assertThat(mockUsage(sender)).isEqualTo(2);

        long method = methods.save(null, new PaymentMethodService.Form("BANK_TRANSFER", "STAGING TEST ONLY",
            "TEST ONLY", "NO REAL DESTINATION", "Synthetic local test instructions", true, 0, null), "phase4-local-test");
        var started = payments.start(sender, "phase4-pay-" + suffix);
        var replay = payments.start(sender, "phase4-pay-" + suffix);
        long firstRequest = started.request().id();
        assertThat(replay.request().id()).isEqualTo(firstRequest);
        payments.select(sender, firstRequest, method);
        String reference = "PHASE4-" + suffix;
        payments.reference(sender, firstRequest, reference);
        payments.receipt(sender, firstRequest, new ReceiptMetadata("synthetic-file", "synthetic-unique", "PHOTO", null, "image/jpeg", 128));
        assertThat(paymentQueries.get(firstRequest).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        review.approve(firstRequest, "phase4-local-test");
        review.approve(firstRequest, "phase4-local-test");
        long firstUserId = jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?", Long.class, sender);
        assertThat(entitlements.findByUserId(firstUserId).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?", Integer.class, firstRequest)).isEqualTo(1);

        long rejected = payments.start(otherSender, "phase4-reject-" + suffix).request().id();
        payments.select(otherSender, rejected, method);
        assertThatThrownBy(() -> payments.reference(otherSender, rejected, reference)).hasMessage("payment.duplicateReference");
        payments.reference(otherSender, rejected, "REJECT-" + suffix);
        payments.receipt(otherSender, rejected, new ReceiptMetadata("synthetic-file-2", "synthetic-unique-2", "PHOTO", null, "image/jpeg", 128));
        review.reject(rejected, "phase4-local-test", "Synthetic test rejection");
        review.reject(rejected, "phase4-local-test", "Repeated synthetic rejection");
        assertThat(paymentQueries.get(rejected).status()).isEqualTo(PaymentStatus.REJECTED);
        long otherUserId = jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?", Long.class, otherSender);
        assertThat(entitlements.findByUserId(otherUserId).orElseThrow().getAccessLevel()).isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=?", Integer.class, otherUserId)).isZero();

        assertThat(before).isNotNull(); // @Transactional rolls all synthetic data and settings back after the test.
    }

    private long createSyntheticStudent(long exam, String seed) throws Exception {
        long telegramId = Math.abs(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits() % 8_000_000_000_000L) + 1_000_000_000L;
        String phoneHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
            INSERT INTO bot_users(telegram_user_id,preferred_language,selected_exam_type_id,registration_status,
              phone_identity_hash,registration_completed_at,created_at,updated_at)
            VALUES(?, 'en', ?, 'COMPLETED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, telegramId, exam, phoneHash);
        long userId = jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?", Long.class, telegramId);
        jdbc.update("""
            INSERT INTO access_entitlements(user_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,
              practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
            VALUES(?,?,'FREE',10,2,3,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, userId, phoneHash);
        return telegramId;
    }

    private QuestionForm syntheticQuestion(long exam, long category, String text, boolean premium) {
        var form = new QuestionForm();
        form.setExamTypeId(exam); form.setCategoryId(category); form.setQuestionText(text);
        form.setExplanation("Original synthetic Phase 4 explanation"); form.setDifficulty(Difficulty.EASY);
        form.setTags("synthetic,phase4"); form.setSourceType("Original"); form.setSourceTitle("Synthetic local integration fixture");
        form.setUseStatus(UseStatus.ORIGINAL); form.setFreePool(!premium); form.setPremiumPool(premium); form.setMockPool(true);
        form.getOptions().set(0,"Synthetic correct"); form.getOptions().set(1,"Synthetic incorrect"); form.setCorrectOption(0);
        return form;
    }

    private int mockUsage(long telegramId) {
        // The test class owns an outer transaction, so service-level JPA writes
        // have not reached PostgreSQL when this helper reads through JdbcTemplate.
        entitlements.flush();
        return jdbc.queryForObject("""
            SELECT e.mocks_used FROM access_entitlements e JOIN bot_users u ON u.id=e.user_id WHERE u.telegram_user_id=?
            """, Integer.class, telegramId);
    }

    private int questionIndex(long questionId, long[] questionIds) {
        for (int i = 0; i < questionIds.length; i++) if (questionIds[i] == questionId) return i;
        throw new AssertionError("Mock selected a question outside the synthetic fixture.");
    }
}
