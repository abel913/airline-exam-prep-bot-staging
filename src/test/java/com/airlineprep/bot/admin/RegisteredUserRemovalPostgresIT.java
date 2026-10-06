package com.airlineprep.bot.admin;

import java.math.BigDecimal;
import java.util.UUID;

import com.airlineprep.bot.access.AccessEntitlementRepository;
import com.airlineprep.bot.mock.MockAttemptService;
import com.airlineprep.bot.payment.PaymentMethodService;
import com.airlineprep.bot.payment.PaymentReviewService;
import com.airlineprep.bot.payment.PaymentService;
import com.airlineprep.bot.payment.ReceiptMetadata;
import com.airlineprep.bot.practice.PracticeService;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.settings.SettingsForm;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.user.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL FK and rollback verification. This class refuses every non-dedicated database. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password=",
    "registration.phone-hmac-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=",
    "payment.notifications.automatic=false"})
@Transactional
class RegisteredUserRemovalPostgresIT {
    private static final java.util.Set<String> TEST_DATABASES = java.util.Set.of(
        "airline_exam_bot_phase5_test", "airline_exam_bot_phase5_v20_fresh_test", "airline_exam_bot_phase5_v20_final_test", "airline_exam_bot_phase5_v21_test");

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) {
        String host=System.getenv("PHASE4_PG_HOST"),name=System.getenv("PHASE4_PG_DATABASE");
        String username=System.getenv("PHASE4_PG_TEST_USER"),password=System.getenv("PHASE4_PG_TEST_PASSWORD");
        String port=System.getenv("PHASE4_PG_PORT");
        if (!"127.0.0.1".equals(host) || !TEST_DATABASES.contains(name) || username==null || password==null || !"5432".equals(port))
            throw new IllegalStateException("Registered-user PostgreSQL tests require the dedicated loopback Phase 5 database only.");
        String url="jdbc:postgresql://127.0.0.1:5432/"+name;
        registry.add("spring.datasource.url",()->url); registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password); registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.url",()->url); registry.add("spring.flyway.user",()->username); registry.add("spring.flyway.password",()->password);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RegisteredUserRemovalService removal;
    @Autowired CatalogService catalog;
    @Autowired QuestionService questions;
    @Autowired RegistrationService registration;
    @Autowired PracticeService practice;
    @Autowired MockAttemptService mocks;
    @Autowired SettingsService settings;
    @Autowired PaymentMethodService methods;
    @Autowired PaymentService payments;
    @Autowired PaymentReviewService review;
    @Autowired AccessEntitlementRepository entitlements;

    @Test
    void removesOnlySelectedUsersCompleteHistoryAndAllowsSameIdentityToRegisterAgain() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isIn(TEST_DATABASES);
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("21");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        settings.update(new SettingsForm(10,2,1,new BigDecimal("1.00"),"ETB",true,true,"synthetic integration",1),"phase5-remove-test");
        long examA=catalog.save(false,null,new CatalogForm("rm-a-"+suffix,"Synthetic Removal Exam A","",true,0,null),"phase5-remove-test");
        long examB=catalog.save(false,null,new CatalogForm("rm-b-"+suffix,"Synthetic Removal Exam B","",true,1,null),"phase5-remove-test");
        long categoryA=catalog.save(true,null,new CatalogForm("rm-cat-a","Synthetic Removal Category A","",true,0,examA),"phase5-remove-test");
        long categoryB=catalog.save(true,null,new CatalogForm("rm-cat-b","Synthetic Removal Category B","",true,0,examB),"phase5-remove-test");
        long[] questionIds=new long[6];
        for(int i=0;i<3;i++) questionIds[i]=publish(examA,categoryA,"Synthetic removal exam A question "+suffix+" "+i);
        for(int i=0;i<3;i++) questionIds[3+i]=publish(examB,categoryB,"Synthetic removal exam B question "+suffix+" "+i);

        long telegramA=2_500_000_000L+Long.parseLong(suffix.substring(0,8),16);
        long telegramB=telegramA+1;
        long phoneSeed=Math.floorMod(Long.parseLong(suffix.substring(0,8),16),100_000_000L);
        String phoneA="09"+String.format(java.util.Locale.ROOT,"%08d",phoneSeed);
        String phoneB="09"+String.format(java.util.Locale.ROOT,"%08d",(phoneSeed+1)%100_000_000L);
        long userA=register(telegramA,examA,phoneA); registration.exam(telegramA,examB);
        long userB=register(telegramB,examA,phoneB); registration.exam(telegramB,examB);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,userA)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,userB)).isEqualTo(2);

        var deliveryA=practice.next(telegramA,null,null,false); practice.answer(telegramA,deliveryA.delivery().id(),0);
        long deliveryIdA=deliveryA.delivery().id();
        jdbc.update("UPDATE practice_deliveries SET next_delivery_id=?,review_delivery_id=? WHERE id=?",deliveryIdA,deliveryIdA,deliveryIdA);
        jdbc.update("INSERT INTO practice_update_receipts(update_id,user_id,question_id,delivery_id,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
            7_600_000_000_000L+Long.parseLong(suffix.substring(0,8),16),userA,deliveryA.question().id(),deliveryIdA);
        var deliveryB=practice.next(telegramB,null,null,false); practice.answer(telegramB,deliveryB.delivery().id(),0);
        long deliveryIdB=deliveryB.delivery().id();
        jdbc.update("UPDATE practice_deliveries SET next_delivery_id=?,review_delivery_id=? WHERE id=?",deliveryIdB,deliveryIdB,deliveryIdB);
        jdbc.update("INSERT INTO practice_update_receipts(update_id,user_id,question_id,delivery_id,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
            7_700_000_000_000L+Long.parseLong(suffix.substring(0,8),16),userB,deliveryB.question().id(),deliveryIdB);

        var mockA=mocks.prepare(telegramA,"remove-mock-a-"+suffix); mocks.open(telegramA,mockA.attempt().id(),0,false);
        mocks.answer(telegramA,mockA.attempt().id(),0,0,0); mocks.submit(telegramA,mockA.attempt().id());
        var mockB=mocks.prepare(telegramB,"remove-mock-b-"+suffix); mocks.open(telegramB,mockB.attempt().id(),0,false);
        mocks.answer(telegramB,mockB.attempt().id(),0,0,0); mocks.submit(telegramB,mockB.attempt().id());

        long paymentMethod=methods.save(null,new PaymentMethodService.Form("BANK_TRANSFER","Synthetic only","Synthetic account",
            "No real destination","Synthetic integration instructions",true,0,null),"phase5-remove-test");
        long paymentA=submitPayment(telegramA,examA,paymentMethod,"RM-A-"+suffix);
        long paymentB=submitPayment(telegramB,examA,paymentMethod,"RM-B-"+suffix);
        review.approve(paymentA,"phase5-remove-test"); review.approve(paymentB,"phase5-remove-test");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=?",Integer.class,userA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE request_id=?",Integer.class,paymentA)).isGreaterThanOrEqualTo(1);

        long questionCount=jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE id IN (?,?,?,?,?,?)",Long.class,
            questionIds[0],questionIds[1],questionIds[2],questionIds[3],questionIds[4],questionIds[5]);
        long versionCount=jdbc.queryForObject("SELECT COUNT(*) FROM question_versions WHERE question_id IN (?,?,?,?,?,?)",Long.class,
            questionIds[0],questionIds[1],questionIds[2],questionIds[3],questionIds[4],questionIds[5]);
        long optionCount=jdbc.queryForObject("SELECT COUNT(*) FROM question_options o JOIN question_versions v ON v.id=o.version_id WHERE v.question_id IN (?,?,?,?,?,?)",Long.class,
            questionIds[0],questionIds[1],questionIds[2],questionIds[3],questionIds[4],questionIds[5]);
        long examCount=jdbc.queryForObject("SELECT COUNT(*) FROM exam_types WHERE id IN (?,?)",Long.class,examA,examB);
        long methodCount=jdbc.queryForObject("SELECT COUNT(*) FROM payment_methods WHERE id=?",Long.class,paymentMethod);
        long adminCount=jdbc.queryForObject("SELECT COUNT(*) FROM admin_users",Long.class);
        String phoneHash=jdbc.queryForObject("SELECT phone_identity_hash FROM bot_users WHERE id=?",String.class,userA);

        assertThat(removal.remove(userA,"phase5-test-admin")).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=? OR telegram_user_id=?",Integer.class,userA,telegramA)).isZero();
        for(String table: new String[]{"access_entitlements","practice_sessions","practice_deliveries","practice_usage",
                "practice_update_receipts","mock_attempts","payment_requests","lifetime_access_grants"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,userA))
                .as("selected user's rows in %s",table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_items WHERE attempt_id=?",Integer.class,mockA.attempt().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE request_id=?",Integer.class,paymentA)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id=?",Integer.class,paymentA)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE phone_identity_hash=?",Integer.class,phoneHash)).isZero();

        // User B and every global catalog/configuration object remain intact.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?",Integer.class,userB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,userB)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_deliveries WHERE user_id=?",Integer.class,userB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_update_receipts WHERE user_id=?",Integer.class,userB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_attempts WHERE user_id=?",Integer.class,userB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE user_id=?",Integer.class,userB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=?",Integer.class,userB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE request_id=?",Integer.class,paymentB)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_type='PAYMENT' AND entity_id=?",Integer.class,paymentB)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_methods WHERE id=?",Long.class,paymentMethod)).isEqualTo(methodCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_users",Long.class)).isEqualTo(adminCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE id IN (?,?,?,?,?,?)",Long.class,
            questionIds[0],questionIds[1],questionIds[2],questionIds[3],questionIds[4],questionIds[5])).isEqualTo(questionCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_versions WHERE question_id IN (?,?,?,?,?,?)",Long.class,
            questionIds[0],questionIds[1],questionIds[2],questionIds[3],questionIds[4],questionIds[5])).isEqualTo(versionCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_options o JOIN question_versions v ON v.id=o.version_id WHERE v.question_id IN (?,?,?,?,?,?)",Long.class,
            questionIds[0],questionIds[1],questionIds[2],questionIds[3],questionIds[4],questionIds[5])).isEqualTo(optionCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_types WHERE id IN (?,?)",Long.class,examA,examB)).isEqualTo(examCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE id IN (?,?)",Integer.class,categoryA,categoryB)).isEqualTo(2);

        // Reuse both the same Telegram ID and phone identity after complete deletion.
        registration.start(telegramA); registration.language(telegramA,"en"); registration.exam(telegramA,examA);
        registration.contact(telegramA,telegramA,phoneA);
        Long replacement=jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?",Long.class,telegramA);
        assertThat(replacement).isNotEqualTo(userA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE phone_identity_hash=?",Integer.class,phoneHash)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,replacement)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void unexpectedForeignKeyFailureRollsBackAllEarlierDeletes() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10);
        String examCode="rm-rollback-"+suffix;
        long exam=jdbc.queryForObject("INSERT INTO exam_types(code,name,name_am,active,display_order,created_at,updated_at) VALUES(?,?,?,true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
            Long.class,examCode,"Synthetic rollback exam","Synthetic rollback exam");
        String hash="b".repeat(64);
        long tg=2_600_000_000L+Long.parseLong(suffix.substring(0,8),16);
        long userId=jdbc.queryForObject("INSERT INTO bot_users(telegram_user_id,preferred_language,selected_exam_type_id,registration_status,phone_identity_hash,phone_verification_status,registration_completed_at,created_at,updated_at) VALUES(?,'en',?,'COMPLETED',?,'NOT_CAPTURED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
            Long.class,tg,exam,hash);
        jdbc.update("INSERT INTO access_entitlements(user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,questions_per_mock,practice_used,mocks_used,grant_source,granted_at,created_at,updated_at) VALUES(?,?,?,'FREE',10,2,1,0,0,'REGISTRATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",userId,exam,hash);
        long attempt=jdbc.queryForObject("INSERT INTO mock_attempts(user_id,active_user_id,exam_type_id,creation_key,status,question_count,duration_minutes,cursor_position,created_at,submitted_at,correct_count,incorrect_count,unanswered_count) VALUES(?,NULL,?,'rollback-fixture','EXPIRED',1,10,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0,1) RETURNING id",
            Long.class,userId,exam);
        String rollbackActor="phase5-rollback-test";
        String removeActor="phase5-test-admin";
        Long category=null,question=null,paymentMethod=null,paymentId=null;
        SettingsForm originalSettings=null;
        String guard="phase5_removal_guard_"+suffix;
        if(!guard.matches("phase5_removal_guard_[a-f0-9]{10}")) throw new IllegalStateException("Unsafe generated test guard name.");
        try {
            originalSettings=SettingsForm.from(settings.current());
            settings.update(new SettingsForm(originalSettings.freePracticeLimit(),originalSettings.freeMockLimit(),
                originalSettings.questionsPerMock(),originalSettings.lifetimePrice(),originalSettings.currency(),
                true,true,originalSettings.supportInfo(),originalSettings.mockDurationMinutes()),rollbackActor);
            category=catalog.save(true,null,new CatalogForm("rm-rollback-cat-"+suffix,"Synthetic rollback category","",true,0,exam),rollbackActor);
            question=publish(exam,category,"Synthetic rollback question "+suffix);
            var delivery=practice.next(tg,null,null,false);
            practice.answer(tg,delivery.delivery().id(),0);
            paymentMethod=methods.save(null,new PaymentMethodService.Form("BANK_TRANSFER","Synthetic rollback only",
                "Synthetic account","No real destination","Synthetic rollback instructions",true,0,null),rollbackActor);
            paymentId=submitPayment(tg,exam,paymentMethod,"RB-"+suffix);
            jdbc.execute("CREATE TABLE "+guard+" (user_id BIGINT NOT NULL REFERENCES bot_users(id))");
            jdbc.update("INSERT INTO "+guard+"(user_id) VALUES(?)",userId);
            assertThatThrownBy(()->removal.remove(userId,removeActor))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?",Integer.class,userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_attempts WHERE id=?",Integer.class,attempt)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_deliveries WHERE user_id=?",Integer.class,userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_usage WHERE user_id=?",Integer.class,userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE id=? AND user_id=?",Integer.class,paymentId,userId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE request_id=?",Integer.class,paymentId)).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_changes WHERE action='REGISTERED_USER_REMOVED' AND target=?",Integer.class,"bot_user:"+userId)).isZero();
            jdbc.update("DELETE FROM "+guard+" WHERE user_id=?",userId);
            jdbc.execute("DROP TABLE "+guard);
            assertThat(removal.remove(userId,removeActor)).isPresent();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?",Integer.class,userId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_attempts WHERE id=?",Integer.class,attempt)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_deliveries WHERE user_id=?",Integer.class,userId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_usage WHERE user_id=?",Integer.class,userId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE id=?",Integer.class,paymentId)).isZero();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS "+guard);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?",Integer.class,userId)>0) {
                try { removal.remove(userId,removeActor); } catch(RuntimeException ignored) { /* targeted fallback below */ }
            }
            if(originalSettings!=null) settings.update(originalSettings,rollbackActor);
            jdbc.update("DELETE FROM admin_changes WHERE actor=? AND action='REGISTERED_USER_REMOVED' AND target=?",removeActor,"bot_user:"+userId);
            jdbc.update("DELETE FROM admin_changes WHERE actor=? AND target='settings:1'",rollbackActor);
            if(paymentMethod!=null) {
                jdbc.update("DELETE FROM payment_audit_events WHERE actor_type='WEB_ADMIN' AND actor=? AND entity_type='PAYMENT_METHOD' AND action='PAYMENT_METHOD_SAVED' AND entity_id=?",rollbackActor,paymentMethod);
                jdbc.update("DELETE FROM payment_methods WHERE id=?",paymentMethod);
            }
            if(question!=null) {
                jdbc.update("UPDATE questions SET current_version_id=NULL,status='DRAFT' WHERE id=?",question);
                jdbc.update("DELETE FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id=?)",question);
                jdbc.update("DELETE FROM question_versions WHERE question_id=?",question);
                jdbc.update("DELETE FROM questions WHERE id=?",question);
            }
            if(category!=null) {
                jdbc.update("DELETE FROM admin_changes WHERE actor=? AND action='CATALOG_CREATED' AND target=?",rollbackActor,"category:"+category);
                jdbc.update("DELETE FROM categories WHERE id=?",category);
            }
            jdbc.update("DELETE FROM admin_changes WHERE actor='phase5-removal-test' AND target IN (?,?,?)",
                "exam_type:"+exam,"category:"+category,"question:"+question);
            jdbc.update("DELETE FROM admin_changes WHERE actor=? AND target='settings:1'",rollbackActor);
            jdbc.update("DELETE FROM exam_types WHERE id=?",exam);
        }
    }

    private long register(long telegramId,long exam,String phone) {
        assertThat(registration.start(telegramId).status()).isEqualTo(com.airlineprep.bot.user.RegistrationStatus.LANGUAGE_REQUIRED);
        assertThat(registration.language(telegramId,"en").status()).isEqualTo(com.airlineprep.bot.user.RegistrationStatus.EXAM_TYPE_REQUIRED);
        assertThat(registration.exam(telegramId,exam).status()).isEqualTo(com.airlineprep.bot.user.RegistrationStatus.PHONE_REQUIRED);
        var completed=registration.contact(telegramId,telegramId,phone);
        assertThat(completed.status())
            .as("synthetic registration should complete (error key: %s)",completed.errorKey())
            .isEqualTo(com.airlineprep.bot.user.RegistrationStatus.COMPLETED);
        return jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?",Long.class,telegramId);
    }

    private long publish(long exam,long category,String text) {
        var form=new QuestionForm(); form.setExamTypeId(exam); form.setCategoryId(category); form.setQuestionText(text);
        form.setExplanation("Original synthetic explanation for local user-removal testing."); form.setDifficulty(Difficulty.EASY);
        form.setTags("synthetic,phase5-removal"); form.setSourceType("Original"); form.setSourceTitle("Synthetic local user-removal integration fixture");
        form.setUseStatus(UseStatus.ORIGINAL); form.setFreePool(true); form.setPremiumPool(false); form.setMockPool(true);
        form.getOptions().set(0,"Synthetic correct answer"); form.getOptions().set(1,"Synthetic incorrect answer"); form.setCorrectOption(0);
        long id=questions.save(null,form,"phase5-removal-test");
        questions.transition(id,QuestionStatus.REVIEWED,questions.get(id).getRevision(),"phase5-removal-test");
        questions.transition(id,QuestionStatus.PUBLISHED,questions.get(id).getRevision(),"phase5-removal-test");
        return id;
    }

    private long submitPayment(long telegramId,long exam,long method,String reference) {
        var request=payments.start(telegramId,exam,"remove-payment-"+reference.toLowerCase(java.util.Locale.ROOT)).request();
        payments.select(telegramId,request.id(),method); payments.reference(telegramId,request.id(),reference);
        payments.receipt(telegramId,request.id(),new ReceiptMetadata("synthetic-file-"+reference,
            "synthetic-unique-"+reference,"PHOTO",null,"image/jpeg",128));
        return request.id();
    }
}
