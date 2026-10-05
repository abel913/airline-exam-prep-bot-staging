package com.airlineprep.bot.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

/** Opt-in only: synthetic fixtures in the dedicated local Phase 5 PostgreSQL database. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password=",
    "registration.phone-hmac-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ="})
@Transactional
class Phase4PostgresIT {
    private static final java.util.Set<String> TEST_DATABASES = java.util.Set.of(
        "airline_exam_bot_phase5_test", "airline_exam_bot_phase5_v20_fresh_test", "airline_exam_bot_phase5_v20_final_test", "airline_exam_bot_phase5_v20_verify_test");

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) {
        String host = System.getenv("PHASE4_PG_HOST");
        String name = System.getenv("PHASE4_PG_DATABASE");
        String username = System.getenv("PHASE4_PG_TEST_USER");
        String password = System.getenv("PHASE4_PG_TEST_PASSWORD");
        String port = System.getenv("PHASE4_PG_PORT");
        if (!"127.0.0.1".equals(host) || !TEST_DATABASES.contains(name) || username == null || password == null
                || port == null || !port.matches("[0-9]{1,5}")) {
            throw new IllegalStateException("Phase 5 PostgreSQL tests require the dedicated local test database only.");
        }
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/" + name;
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
    @Autowired com.airlineprep.bot.practice.PracticeService practice;

    @Test
    void phase4MockPaymentAndBothJavaFlywayMigrationsOnPostgreSQL() throws Exception {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('16','17','18','19') AND success", Integer.class)).isEqualTo(4);

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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND ((table_name='access_entitlements' AND column_name='exam_type_id') OR (table_name='payment_requests' AND column_name='target_exam_type_id') OR (table_name='lifetime_access_grants' AND column_name='exam_type_id'))", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT exam_type_id FROM access_entitlements WHERE user_id=(SELECT id FROM bot_users WHERE telegram_user_id=?)", Long.class, sender)).isEqualTo(exam);

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
        int expectedQuestionCount = jdbc.queryForObject("""
            SELECT e.questions_per_mock FROM access_entitlements e
            JOIN bot_users u ON u.id=e.user_id WHERE u.telegram_user_id=?
            """, Integer.class, sender);
        assertThat(expectedQuestionCount).isEqualTo(3); // synthetic entitlement snapshot configured above
        assertThat(ready.attempt().count()).isEqualTo(expectedQuestionCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_items WHERE attempt_id=?", Integer.class, attempt))
            .isEqualTo(expectedQuestionCount);
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
        assertThat(score.total()).isEqualTo(expectedQuestionCount);
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
        var started = payments.start(sender, exam, "phase4-pay-" + suffix);
        var replay = payments.start(sender, exam, "phase4-pay-" + suffix);
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

        long rejected = payments.start(otherSender, exam, "phase4-reject-" + suffix).request().id();
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

    @Test
    void phase5MultiExamUsagePaymentsContactsAndUniquenessOnPostgreSQL() throws Exception {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("20");
        var before=SettingsForm.from(settings.current());
        settings.update(new SettingsForm(10,2,3,new BigDecimal("50.00"),"ETB",true,true,"Synthetic staging support",1),"phase5-local-test");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        long examA=catalog.save(false,null,new CatalogForm("phase5-a-"+suffix,"Synthetic Phase 5 Exam A","",true,0,null),"phase5-local-test");
        long examB=catalog.save(false,null,new CatalogForm("phase5-b-"+suffix,"Synthetic Phase 5 Exam B","",true,1,null),"phase5-local-test");
        long categoryA=catalog.save(true,null,new CatalogForm("phase5-ca-"+suffix,"Synthetic Phase 5 Category A","",true,0,examA),"phase5-local-test");
        long categoryB=catalog.save(true,null,new CatalogForm("phase5-cb-"+suffix,"Synthetic Phase 5 Category B","",true,0,examB),"phase5-local-test");
        // UUID hex can contain a-f; convert it to fixed-width numeric digits for
        // the application's Ethiopian-phone validation contract.
        String phoneSuffix=String.format(java.util.Locale.ROOT,"%04d",Integer.parseInt(suffix.substring(0,4),16)%10_000);
        String contactPhoneSuffix=String.format(java.util.Locale.ROOT,"%04d",(Integer.parseInt(phoneSuffix)+1)%10_000);

        long sender=910_000_000_000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%1_000_000_000L);
        registration.start(sender);registration.language(sender,"en");registration.exam(sender,examA);
        var typedRegistration=registration.manualPhone(sender,"091234"+phoneSuffix);
        assertThat(typedRegistration.status()).isEqualTo(RegistrationStatus.COMPLETED);
        assertThat(typedRegistration.errorKey()).isNull();
        long userId=jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?",Long.class,sender);
        var typedRow=jdbc.queryForMap("SELECT registration_status,selected_exam_type_id,phone_verification_status FROM bot_users WHERE id=?",userId);
        assertThat(typedRow.get("registration_status")).isEqualTo("COMPLETED");
        assertThat(((Number)typedRow.get("selected_exam_type_id")).longValue()).isEqualTo(examA);
        assertThat(typedRow.get("phone_verification_status")).isEqualTo("UNVERIFIED_TYPED");
        assertThat(jdbc.queryForObject("SELECT phone_e164 FROM bot_users WHERE id=?",String.class,userId)).startsWith("+251");
        assertThat(jdbc.queryForObject("SELECT phone_verification_status FROM bot_users WHERE id=?",String.class,userId)).isEqualTo("UNVERIFIED_TYPED");

        long contactSender=sender+1;
        registration.start(contactSender);registration.language(contactSender,"en");registration.exam(contactSender,examA);
        var contactRegistration=registration.contact(contactSender,contactSender,"+25191234"+contactPhoneSuffix);
        assertThat(contactRegistration.status()).isEqualTo(RegistrationStatus.COMPLETED);
        assertThat(contactRegistration.errorKey()).isNull();
        long contactUserId=jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?",Long.class,contactSender);
        assertThat(jdbc.queryForObject("SELECT phone_verification_status FROM bot_users WHERE id=?",String.class,contactUserId)).isEqualTo("VERIFIED_TELEGRAM_CONTACT");

        long[] qA=new long[3],qB=new long[3];
        for(int i=0;i<3;i++) {
            qA[i]=publish(examA,categoryA,"Synthetic exam A question "+i);
            qB[i]=publish(examB,categoryB,"Synthetic exam B question "+i);
        }
        var practiceA=practice.next(sender,null,null,false);practice.answer(sender,practiceA.delivery().id(),0);
        var mockA=mocks.prepare(sender,"phase5-a-"+suffix);mocks.open(sender,mockA.attempt().id(),0,false);
        mocks.answer(sender,mockA.attempt().id(),0,0,0);mocks.submit(sender,mockA.attempt().id());
        int practiceAUsed=grantCount(userId,examA,"practice_used"),mockAUsed=grantCount(userId,examA,"mocks_used");
        assertThat(practiceAUsed).isEqualTo(1);assertThat(mockAUsed).isEqualTo(1);

        registration.switchExams(sender);registration.exam(sender,examB);
        assertThat(grantCount(userId,examB,"practice_used")).isZero();assertThat(grantCount(userId,examB,"mocks_used")).isZero();
        var practiceB=practice.next(sender,null,null,false);assertThat(practiceB.question().examId()).isEqualTo(examB);
        practice.answer(sender,practiceB.delivery().id(),0);
        var mockB=mocks.prepare(sender,"phase5-b-"+suffix);mocks.open(sender,mockB.attempt().id(),0,false);
        mocks.answer(sender,mockB.attempt().id(),0,0,0);mocks.submit(sender,mockB.attempt().id());
        assertThat(grantCount(userId,examA,"practice_used")).isEqualTo(practiceAUsed);
        assertThat(grantCount(userId,examA,"mocks_used")).isEqualTo(mockAUsed);
        assertThat(grantCount(userId,examB,"practice_used")).isEqualTo(1);
        assertThat(grantCount(userId,examB,"mocks_used")).isEqualTo(1);
        registration.exam(sender,examA);
        assertThat(grantCount(userId,examA,"practice_used")).isEqualTo(practiceAUsed);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint WHERE conrelid='access_entitlements'::regclass AND contype='u' AND pg_get_constraintdef(oid) LIKE 'UNIQUE (user_id, exam_type_id)%'",Integer.class)).isEqualTo(1);
        long method=methods.save(null,new PaymentMethodService.Form("BANK_TRANSFER","STAGING TEST ONLY","TEST ONLY","NO REAL DESTINATION","Synthetic Phase 5 instructions",true,0,null),"phase5-local-test");
        long paymentA=createAndSubmitPayment(sender,examA,method,"PHASE5-A-"+suffix);
        assertThat(jdbc.queryForObject("SELECT target_exam_type_id FROM payment_requests WHERE id=?",Long.class,paymentA)).isEqualTo(examA);
        review.approve(paymentA,"phase5-local-test");review.approve(paymentA,"phase5-local-test");
        assertThat(jdbc.queryForObject("SELECT access_level FROM access_entitlements WHERE user_id=? AND exam_type_id=?",String.class,userId,examA)).isEqualTo("LIFETIME");
        assertThat(jdbc.queryForObject("SELECT access_level FROM access_entitlements WHERE user_id=? AND exam_type_id=?",String.class,userId,examB)).isEqualTo("FREE");
        registration.exam(sender,examB);
        long paymentB=createAndSubmitPayment(sender,examB,method,"PHASE5-B-"+suffix);
        assertThat(jdbc.queryForObject("SELECT target_exam_type_id FROM payment_requests WHERE id=?",Long.class,paymentB)).isEqualTo(examB);
        review.approve(paymentB,"phase5-local-test");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=?",Integer.class,userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT exam_type_id) FROM lifetime_access_grants WHERE user_id=?",Integer.class,userId)).isEqualTo(2);

        registration.exam(contactSender,examB);
        long rejected=createAndSubmitPayment(contactSender,examA,method,"PHASE5-REJECT-"+suffix);
        review.reject(rejected,"phase5-local-test","Synthetic Phase 5 rejection");
        long contactUser=jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?",Long.class,contactSender);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_requests WHERE id=?",String.class,rejected)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT access_level FROM access_entitlements WHERE user_id=? AND exam_type_id=?",String.class,contactUser,examB)).isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=?",Integer.class,contactUser)).isZero();
        assertThat(before).isNotNull(); // Outer transaction rolls back every synthetic Phase 5 row and settings change.
    }

    @Test
    void phase5V17ToV18BackfillPreservesLegacyStateOnPostgreSQL() throws Exception {
        String host=System.getenv("PHASE4_PG_HOST"),database=System.getenv("PHASE4_PG_DATABASE");
        String username=System.getenv("PHASE4_PG_TEST_USER"),password=System.getenv("PHASE4_PG_TEST_PASSWORD");
        if(!"127.0.0.1".equals(host)||!TEST_DATABASES.contains(database)||username==null||password==null)
            throw new IllegalStateException("V17-to-V18 migration integration requires the dedicated local Phase 5 PostgreSQL database.");
        String url="jdbc:postgresql://127.0.0.1:5432/"+database;
        String schema="phase5_v17_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        try {
            if(!schema.matches("phase5_v17_[a-f0-9]{12}")) throw new IllegalStateException("Unsafe generated schema name.");
            try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement()) { s.execute("CREATE SCHEMA \""+schema+"\""); }
            Flyway v17=Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("17")).load();
            assertThat(v17.migrate().targetSchemaVersion).isEqualTo("17");
            long exam,user,approved,rejected,unrelatedExam,category,question,version,delivery,mockAttempt;
            try(var c=DriverManager.getConnection(url,username,password)) {
                c.setSchema(schema);
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at) VALUES('legacy-phase5','Synthetic Legacy Exam','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) { r.next(); exam=r.getLong(1); }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at) VALUES('legacy-other','Synthetic Unrelated Exam','',TRUE,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) { r.next(); unrelatedExam=r.getLong(1); }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO categories(exam_type_id,code,name,name_am,active,display_order,created_at,updated_at) VALUES("+exam+",'legacy-cat','Synthetic Legacy Category','',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) { r.next(); category=r.getLong(1); }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO bot_users(telegram_user_id,preferred_language,selected_exam_type_id,registration_status,phone_identity_hash,registration_completed_at,created_at,updated_at) VALUES(987650001,'en',"+exam+",'COMPLETED','"+"a".repeat(64)+"',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) { r.next(); user=r.getLong(1); }
                try(var s=c.createStatement()) {
                    s.execute("INSERT INTO access_entitlements(user_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,practice_used,mocks_used,grant_source,granted_at,created_at,updated_at) VALUES("+user+",'"+"a".repeat(64)+"','LIFETIME',100,10,5,7,1,'LEGACY_TEST',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                    s.execute("INSERT INTO payment_methods(type,display_name,account_name,destination,instructions,active,display_order,created_at,updated_at) VALUES('BANK_TRANSFER','Synthetic method','Synthetic account','synthetic-only','Synthetic local test',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO questions(status,created_by,updated_by,created_at,updated_at) VALUES('DRAFT','phase5-v17-fixture','phase5-v17-fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) { r.next(); question=r.getLong(1); }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO question_versions(question_id,version_number,exam_type_id,category_id,exam_name,category_name,question_text,explanation,difficulty,source_type,source_title,source_reference,source_notes,use_status,free_pool,premium_pool,mock_pool,fingerprint,stem_fingerprint,created_by,created_at,updated_at) VALUES("+question+",1,"+exam+","+category+",'Synthetic Legacy Exam','Synthetic Legacy Category','Synthetic legacy question','Synthetic explanation','EASY','Original','Synthetic legacy source','','','ORIGINAL',TRUE,FALSE,TRUE,'"+"b".repeat(64)+"','"+"c".repeat(64)+"','phase5-v17-fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) { r.next(); version=r.getLong(1); }
                try(var s=c.createStatement()) {
                    s.execute("UPDATE questions SET current_version_id="+version+",status='PUBLISHED' WHERE id="+question);
                }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO practice_deliveries(user_id,question_id,version_id,category_filter,created_at) VALUES("+user+","+question+","+version+","+category+",CURRENT_TIMESTAMP) RETURNING id")) { r.next(); delivery=r.getLong(1); }
                try(var s=c.createStatement()) {
                    s.execute("INSERT INTO practice_usage(user_id,question_id,first_delivery_id,created_at) VALUES("+user+","+question+","+delivery+",CURRENT_TIMESTAMP)");
                    s.execute("INSERT INTO practice_sessions(user_id,current_delivery_id) VALUES("+user+","+delivery+")");
                }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO mock_attempts(user_id,active_user_id,exam_type_id,creation_key,status,question_count,duration_minutes,cursor_position,created_at,submitted_at,correct_count,incorrect_count,unanswered_count) VALUES("+user+",NULL,"+exam+",'legacy-mock','SUBMITTED',1,10,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,0,0) RETURNING id")) { r.next(); mockAttempt=r.getLong(1); }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO payment_requests(user_id,open_user_id,creation_key,status,amount,currency,method_id,method_type,method_name,account_name,destination,instructions,reference,normalized_reference,receipt_file_id,receipt_unique_id,receipt_type,receipt_mime,receipt_size,created_at,submitted_at,reviewed_at,reviewed_by) SELECT "+user+",NULL,'legacy-approved','APPROVED',50,'ETB',id,'BANK_TRANSFER','Synthetic method','Synthetic account','synthetic-only','Synthetic local test','REF-APPROVED','REF-APPROVED','synthetic-file-a','synthetic-receipt-a','PHOTO','image/jpeg',123,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'synthetic-reviewer' FROM payment_methods RETURNING id")) { r.next(); approved=r.getLong(1); }
                try(var s=c.createStatement();var r=s.executeQuery("INSERT INTO payment_requests(user_id,open_user_id,creation_key,status,amount,currency,method_id,method_type,method_name,account_name,destination,instructions,reference,normalized_reference,receipt_file_id,receipt_unique_id,receipt_type,receipt_mime,receipt_size,created_at,submitted_at,reviewed_at,reviewed_by,rejection_reason) SELECT "+user+",NULL,'legacy-rejected','REJECTED',50,'ETB',id,'BANK_TRANSFER','Synthetic method','Synthetic account','synthetic-only','Synthetic local test','REF-REJECTED','REF-REJECTED','synthetic-file-r','synthetic-receipt-r','PHOTO','image/jpeg',123,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'synthetic-reviewer','Synthetic rejection' FROM payment_methods RETURNING id")) { r.next(); rejected=r.getLong(1); }
                try(var s=c.createStatement()) {
                    s.execute("INSERT INTO lifetime_access_grants(user_id,payment_request_id,granted_at,granted_by) VALUES("+user+","+approved+",CURRENT_TIMESTAMP,'synthetic-reviewer')");
                }
            }
            Flyway latest=Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).load();
            assertThat(latest.migrate().targetSchemaVersion).isEqualTo("20");
            latest.validate();
            try(var c=DriverManager.getConnection(url,username,password)) {
                c.setSchema(schema);
                try(var s=c.createStatement();var r=s.executeQuery("SELECT u.selected_exam_type_id,e.exam_type_id,e.access_level,e.practice_used,e.mocks_used,u.phone_e164,u.phone_verification_status FROM bot_users u JOIN access_entitlements e ON e.user_id=u.id WHERE u.id="+user)) {
                    assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(exam); assertThat(r.getLong(2)).isEqualTo(exam);
                    assertThat(r.getString(3)).isEqualTo("LIFETIME"); assertThat(r.getInt(4)).isEqualTo(7); assertThat(r.getInt(5)).isEqualTo(1);
                    assertThat(r.getString(6)).isNull(); assertThat(r.getString(7)).isEqualTo("NOT_CAPTURED");
                }
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM access_entitlements WHERE user_id="+user+" AND exam_type_id="+unrelatedExam)) { r.next(); assertThat(r.getInt(1)).isZero(); }
                try(var s=c.createStatement();var r=s.executeQuery("SELECT d.exam_type_id,u.exam_type_id,s.exam_type_id FROM practice_deliveries d JOIN practice_usage u ON u.first_delivery_id=d.id JOIN practice_sessions s ON s.current_delivery_id=d.id WHERE d.id="+delivery)) {
                    assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(exam); assertThat(r.getLong(2)).isEqualTo(exam); assertThat(r.getLong(3)).isEqualTo(exam);
                }
                try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM mock_attempts WHERE id="+mockAttempt+" AND exam_type_id="+exam+" AND status='SUBMITTED'")) { r.next(); assertThat(r.getInt(1)).isEqualTo(1); }
                try(var s=c.createStatement();var r=s.executeQuery("SELECT target_exam_type_id,status FROM payment_requests WHERE user_id="+user+" ORDER BY id")) {
                    assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(exam); assertThat(r.getString(2)).isEqualTo("APPROVED");
                    assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(exam); assertThat(r.getString(2)).isEqualTo("REJECTED"); assertThat(r.next()).isFalse();
                }
                try(var s=c.createStatement();var r=s.executeQuery("SELECT exam_type_id FROM lifetime_access_grants WHERE user_id="+user+" AND payment_request_id="+approved)) { assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(exam); }
            }
        } finally {
            // The uniquely named namespace and every row in it are synthetic and are owned by this test only.
            try(var c=DriverManager.getConnection(url,username,password);var s=c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE"); }
        }
    }

    private long publish(long exam,long category,String text) {
        long id=questions.save(null,syntheticQuestion(exam,category,text,false),"phase5-local-test");
        questions.transition(id,QuestionStatus.REVIEWED,questions.get(id).getRevision(),"phase5-local-test");
        questions.transition(id,QuestionStatus.PUBLISHED,questions.get(id).getRevision(),"phase5-local-test");
        return id;
    }

    private int grantCount(long userId,long examId,String column) {
        if(!List.of("practice_used","mocks_used").contains(column)) throw new IllegalArgumentException("invalid entitlement counter");
        entitlements.flush();
        return jdbc.queryForObject("SELECT "+column+" FROM access_entitlements WHERE user_id=? AND exam_type_id=?",Integer.class,userId,examId);
    }

    private long createAndSubmitPayment(long sender,long targetExam,long method,String reference) {
        String creationKey="phase5-payment-"+reference.toLowerCase(java.util.Locale.ROOT);
        assertThat(creationKey).matches("[a-z0-9-]{1,64}");
        var started=payments.start(sender,targetExam,creationKey);
        long request=started.request().id();payments.select(sender,request,method);payments.reference(sender,request,reference);
        payments.receipt(sender,request,new ReceiptMetadata("synthetic-"+reference,"synthetic-unique-"+reference,"PHOTO",null,"image/jpeg",128));
        return request;
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
            INSERT INTO access_entitlements(user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,
              practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
            VALUES(?,?,?,'FREE',10,2,3,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """, userId, exam, phoneHash);
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
