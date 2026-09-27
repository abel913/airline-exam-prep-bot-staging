package com.airlineprep.bot.user;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.airlineprep.bot.IsolatedDatabaseSupport;
import com.airlineprep.bot.access.AccessEntitlementRepository;
import com.airlineprep.bot.examtype.*;
import com.airlineprep.bot.settings.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class RegistrationServiceTests extends IsolatedDatabaseSupport {
    static final AtomicLong IDS = new AtomicLong(10000);
    @Autowired RegistrationService registration;
    @Autowired BotUserRepository users;
    @Autowired AccessEntitlementRepository entitlements;
    @Autowired ExamTypeRepository exams;
    @Autowired AppSettingsRepository settings;
    @Autowired PlatformTransactionManager transactionManager;
    long sender;
    ExamType active;
    @BeforeEach void prepare() {
        sender = IDS.incrementAndGet();
        active = new ExamType();
        active.setCode("test-" + sender); active.setName("Pilot"); active.setNameAm("አብራሪ");
        active.setActive(true); exams.saveAndFlush(active);
    }
    void phoneStep(long id) {
        registration.start(id); registration.language(id, "en"); registration.exam(id, active.getId());
    }
    @Test void startIsPersistentAndIdempotent() {
        assertThat(registration.start(sender).status()).isEqualTo(RegistrationStatus.LANGUAGE_REQUIRED);
        var id = users.findByTelegramUserId(sender).orElseThrow().getId();
        registration.start(sender);
        assertThat(users.findByTelegramUserId(sender).orElseThrow().getId()).isEqualTo(id);
    }
    @Test void languagePersistsAndDuplicateCallbacksDoNotResetProgress() {
        registration.start(sender);
        assertThat(registration.language(sender, "am").language()).isEqualTo("am");
        registration.language(sender, "en");
        assertThat(registration.start(sender).language()).isEqualTo("am");
        assertThat(registration.start(sender).status()).isEqualTo(RegistrationStatus.EXAM_TYPE_REQUIRED);
    }
    @Test void malformedLanguageIsIgnored() {
        registration.start(sender);
        assertThat(registration.language(sender, "xx").status()).isEqualTo(RegistrationStatus.LANGUAGE_REQUIRED);
    }
    @Test void noActiveExamTypesIsSafe() {
        exams.findAll().forEach(e -> e.setActive(false)); exams.flush();
        registration.start(sender);
        assertThat(registration.language(sender,"en").exams()).isEmpty();
        assertThat(registration.start(sender).status()).isEqualTo(RegistrationStatus.EXAM_TYPE_REQUIRED);
    }
    @Test void changedHmacKeyCannotCreateFreshIdentities() {
        phoneStep(sender);
        settings.lock().setPhoneKeyFingerprint("different-key-fingerprint");
        assertThat(registration.contact(sender,sender,"0912345678").errorKey()).isEqualTo("registration.unavailable");
        assertThat(users.findByTelegramUserId(sender).orElseThrow().getPhoneIdentityHash()).isNull();
    }
    @Test void examSelectionAndResumeUsePersistedState() {
        phoneStep(sender);
        assertThat(registration.start(sender).status()).isEqualTo(RegistrationStatus.PHONE_REQUIRED);
        assertThat(users.findByTelegramUserId(sender).orElseThrow().getSelectedExamTypeId()).isEqualTo(active.getId());
    }
    @Test void missingOrInactiveExamIsRejectedAndNotOffered() {
        active.setActive(false); exams.flush();
        registration.start(sender); registration.language(sender, "en");
        assertThat(registration.exam(sender, active.getId()).errorKey()).isEqualTo("registration.examUnavailable");
        assertThat(registration.exam(sender, Long.MAX_VALUE).status()).isEqualTo(RegistrationStatus.EXAM_TYPE_REQUIRED);
        assertThat(registration.start(sender).exams()).noneMatch(e -> e.id().equals(active.getId()));
    }
    @Test void deactivationBeforeContactReturnsToSelection() {
        phoneStep(sender);
        active.setActive(false); exams.flush();
        assertThat(registration.contact(sender, sender, "0912345678").status()).isEqualTo(RegistrationStatus.EXAM_TYPE_REQUIRED);
        assertThat(users.findByTelegramUserId(sender).orElseThrow().getPhoneIdentityHash()).isNull();
    }
    @Test void manualInputOnlyPromptsAtPhoneStepAndNeverGrantsAccess() {
        assertThat(registration.manualPhoneInput(sender)).isEmpty();
        assertThat(users.findByTelegramUserId(sender)).isEmpty();
        registration.start(sender);
        assertThat(registration.manualPhoneInput(sender)).isEmpty();
        registration.language(sender,"en");
        assertThat(registration.manualPhoneInput(sender)).isEmpty();
        registration.exam(sender,active.getId());
        assertThat(registration.manualPhoneInput(sender)).hasValueSatisfying(v -> {
            assertThat(v.errorKey()).isEqualTo("registration.manualPhone");
            assertThat(v.status()).isEqualTo(RegistrationStatus.PHONE_REQUIRED);
        });
        var user=users.findByTelegramUserId(sender).orElseThrow();
        assertThat(user.getPhoneIdentityHash()).isNull();
        assertThat(entitlements.findByUserId(user.getId())).isEmpty();
        registration.contact(sender,sender,"0912345678");
        assertThat(registration.manualPhoneInput(sender)).isEmpty();
    }
    @Test void missingAndForeignContactOwnershipNeverCompletes() {
        phoneStep(sender);
        assertThat(registration.contact(sender, null, "0912345678").errorKey()).isEqualTo("registration.ownContact");
        assertThat(registration.contact(sender, sender + 1, "0912345678").errorKey()).isEqualTo("registration.ownContact");
        assertThat(users.findByTelegramUserId(sender).orElseThrow().getRegistrationCompletedAt()).isNull();
    }
    @Test void malformedPhoneDoesNotComplete() {
        phoneStep(sender);
        assertThat(registration.contact(sender, sender, "+254123").errorKey()).isEqualTo("registration.invalidPhone");
        assertThat(registration.start(sender).status()).isEqualTo(RegistrationStatus.PHONE_REQUIRED);
    }
    @Test void completionGrantsExactlyOnceAndRepeatedStartIsSafe() {
        phoneStep(sender);
        assertThat(registration.contact(sender, sender, "0912345678").status()).isEqualTo(RegistrationStatus.COMPLETED);
        var user = users.findByTelegramUserId(sender).orElseThrow();
        var entitlement = entitlements.findByUserId(user.getId()).orElseThrow();
        assertThat(entitlement.getPracticeLimit()).isEqualTo(100);
        assertThat(entitlement.getMockLimit()).isEqualTo(2);
        assertThat(entitlement.getQuestionsPerMock()).isEqualTo(50);
        assertThat(entitlement.getPracticeUsed()).isZero();
        assertThat(entitlement.getMocksUsed()).isZero();
        assertThat(entitlement.getAccessLevel()).isEqualTo("FREE");
        assertThat(user.getPhoneIdentityHash()).hasSize(64).doesNotContain("912345678");
        registration.contact(sender, sender, "+251912345678"); registration.start(sender);
        assertThat(entitlements.findByUserId(user.getId()).orElseThrow().getId()).isEqualTo(entitlement.getId());
    }
    @Test void anotherAccountCannotReuseCanonicalPhone() {
        phoneStep(sender); phoneStep(sender + 1000000);
        registration.contact(sender, sender, "0912345678");
        assertThat(registration.contact(sender + 1000000, sender + 1000000, "+251912345678").errorKey())
            .isEqualTo("registration.duplicatePhone");
        assertThat(users.findByTelegramUserId(sender + 1000000).orElseThrow().getPhoneIdentityHash()).isNull();
    }
    @Test void snapshotsRemainIndependentOfChangedDefaults() {
        phoneStep(sender); registration.contact(sender, sender, "0912345678");
        var offer = settings.lock();
        offer.setFreePracticeLimit(30); offer.setFreeMockLimit(1); offer.setQuestionsPerMock(25);
        phoneStep(sender + 1000000); registration.contact(sender + 1000000, sender + 1000000, "0712345678");
        assertThat(registration.start(sender).practiceLimit()).isEqualTo(100);
        assertThat(registration.start(sender).mockLimit()).isEqualTo(2);
        assertThat(registration.start(sender).questionsPerMock()).isEqualTo(50);
        assertThat(registration.start(sender + 1000000).practiceLimit()).isEqualTo(30);
        assertThat(registration.start(sender + 1000000).mockLimit()).isEqualTo(1);
        assertThat(registration.start(sender + 1000000).questionsPerMock()).isEqualTo(25);
    }
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void transactionFailureRollsBackIdentityAndEntitlementTogether() {
        phoneStep(sender);
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            registration.contact(sender, sender, "0999999998");
            throw new IllegalStateException("simulated failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        var user = users.findByTelegramUserId(sender).orElseThrow();
        assertThat(user.getPhoneIdentityHash()).isNull();
        assertThat(user.getRegistrationStatus()).isEqualTo(RegistrationStatus.PHONE_REQUIRED);
        assertThat(entitlements.findByUserId(user.getId())).isEmpty();
    }
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoAccountsRacingForOnePhoneReceiveOnlyOneGrant() throws Exception {
        phoneStep(sender); phoneStep(sender + 1000000);
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return registration.contact(sender, sender, "0999999999"); });
            var b = pool.submit(() -> { gate.await(); return registration.contact(sender + 1000000, sender + 1000000, "+251999999999"); });
            gate.countDown();
            var results = java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(v -> v.status() == RegistrationStatus.COMPLETED).count()).isEqualTo(1);
            assertThat(results.stream().filter(v -> "registration.duplicatePhone".equals(v.errorKey())).count()).isEqualTo(1);
        }
    }
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repeatedConcurrentContactForSameUserIsIdempotent() throws Exception {
        phoneStep(sender);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> registration.contact(sender,sender,"0988888888"));
            var b = pool.submit(() -> registration.contact(sender,sender,"+251988888888"));
            assertThat(a.get(15,TimeUnit.SECONDS).status()).isEqualTo(RegistrationStatus.COMPLETED);
            assertThat(b.get(15,TimeUnit.SECONDS).status()).isEqualTo(RegistrationStatus.COMPLETED);
            assertThat(entitlements.findByUserId(users.findByTelegramUserId(sender).orElseThrow().getId())).isPresent();
        }
    }
}
