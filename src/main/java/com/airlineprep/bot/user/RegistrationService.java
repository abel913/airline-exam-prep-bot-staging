package com.airlineprep.bot.user;

import java.time.Instant;
import java.util.List;
import com.airlineprep.bot.access.*;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import com.airlineprep.bot.settings.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RegistrationService {
    public record PurchaseExam(long id, String name, String nameAm, boolean current, String tier) {}
    private final BotUserRepository users;
    private final AccessEntitlementRepository entitlements;
    private final ExamTypeRepository exams;
    private final SettingsService settings;
    private final EthiopianPhoneNormalizer normalizer;
    private final PhoneIdentity identity;

    public RegistrationService(BotUserRepository users, AccessEntitlementRepository entitlements,
            ExamTypeRepository exams, SettingsService settings, EthiopianPhoneNormalizer normalizer,
            PhoneIdentity identity) {
        this.users = users; this.entitlements = entitlements; this.exams = exams;
        this.settings = settings; this.normalizer = normalizer; this.identity = identity;
    }

    public RegistrationView start(long telegramId) {
        settings.lock();
        if (telegramId <= 0) throw new IllegalArgumentException("Invalid Telegram sender");
        BotUser user = users.findByTelegramUserId(telegramId).orElseGet(() -> {
            BotUser created = new BotUser();
            created.setTelegramUserId(telegramId);
            created.setRegistrationStatus(RegistrationStatus.LANGUAGE_REQUIRED);
            return users.save(created);
        });
        return view(user, null);
    }

    public RegistrationView language(long telegramId, String language) {
        settings.lock();
        BotUser user = users.findByTelegramUserId(telegramId).orElse(null);
        if (user != null && user.getRegistrationStatus() == RegistrationStatus.LANGUAGE_REQUIRED
                && ("en".equals(language) || "am".equals(language))) {
            user.setPreferredLanguage(language);
            user.setRegistrationStatus(RegistrationStatus.EXAM_TYPE_REQUIRED);
        }
        return view(user, null);
    }

    public RegistrationView exam(long telegramId, long examId) {
        settings.lock();
        BotUser user = users.findByTelegramUserId(telegramId).orElse(null);
        if (user == null || (user.getRegistrationStatus() != RegistrationStatus.EXAM_TYPE_REQUIRED
                && user.getRegistrationStatus() != RegistrationStatus.COMPLETED))
            return view(user, null);
        var exam = exams.findById(examId);
        if (exam.isEmpty() || !exam.get().getActive()) return view(user, "registration.examUnavailable");
        if (user.getRegistrationStatus() == RegistrationStatus.COMPLETED) {
            if (entitlements.findByUserIdAndExamTypeId(user.getId(), examId).isEmpty()) {
                AppSettings cfg = settings.current();
                AccessEntitlement entitlement = new AccessEntitlement();
                entitlement.setUserId(user.getId()); entitlement.setExamTypeId(examId);
                entitlement.setPhoneIdentityHash(user.getPhoneIdentityHash()); entitlement.setAccessLevel("FREE");
                entitlement.setPracticeLimit(cfg.getFreePracticeLimit()); entitlement.setMockLimit(cfg.getFreeMockLimit());
                entitlement.setQuestionsPerMock(cfg.getQuestionsPerMock()); entitlement.setPracticeUsed(0); entitlement.setMocksUsed(0);
                entitlement.setGrantSource("EXAM_ACTIVATION"); entitlement.setGrantedAt(Instant.now());
                entitlements.saveAndFlush(entitlement);
            }
            user.setSelectedExamTypeId(examId);
            return view(user, null);
        }
        user.setSelectedExamTypeId(examId);
        user.setRegistrationStatus(RegistrationStatus.PHONE_REQUIRED);
        return view(user, null);
    }

    public RegistrationView switchExams(long telegramId) {
        settings.lock();
        BotUser user = users.findByTelegramUserId(telegramId).orElse(null);
        if (user == null || user.getRegistrationStatus() != RegistrationStatus.COMPLETED) return view(user, null);
        var options = exams.findAllByActiveTrueOrderByDisplayOrderAscIdAsc().stream()
            .map(e -> new RegistrationView.ExamOption(e.getId(), e.getName(), e.getNameAm(), e.getId().equals(user.getSelectedExamTypeId())))
            .toList();
        return new RegistrationView(RegistrationStatus.EXAM_SWITCH_REQUIRED, user.getPreferredLanguage(), options, null, null, null, null);
    }

    public List<PurchaseExam> purchaseExams(long telegramId) {
        settings.lock();
        BotUser user = users.findByTelegramUserId(telegramId).orElse(null);
        if (user == null || user.getRegistrationStatus() != RegistrationStatus.COMPLETED)
            throw new com.airlineprep.bot.common.ExamException("student.register");
        return exams.findAllByActiveTrueOrderByDisplayOrderAscIdAsc().stream()
            .map(exam -> entitlements.findByUserIdAndExamTypeId(user.getId(), exam.getId())
                .map(grant -> new PurchaseExam(exam.getId(), exam.getName(), exam.getNameAm(),
                    exam.getId().equals(user.getSelectedExamTypeId()), grant.getAccessLevel()))
                .orElse(null))
            .filter(java.util.Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<RegistrationView> manualPhoneInput(long telegramId) {
        // Inspect only existing registration state; text must never become a phone identity.
        return users.findByTelegramUserId(telegramId)
            .filter(user -> user.getRegistrationStatus() == RegistrationStatus.PHONE_REQUIRED)
            .map(user -> new RegistrationView(RegistrationStatus.PHONE_REQUIRED,
                user.getPreferredLanguage() == null ? "en" : user.getPreferredLanguage(),
                List.of(), "registration.manualPhone", null, null, null));
    }

    public RegistrationView contact(long telegramId, Long contactOwner, String rawPhone) {
        if (contactOwner == null || contactOwner.longValue() != telegramId) {
            return view(users.findByTelegramUserId(telegramId).orElse(null), "registration.ownContact");
        }
        return savePhone(telegramId, rawPhone, true);
    }

    public RegistrationView manualPhone(long telegramId, String rawPhone) {
        return savePhone(telegramId, rawPhone, false);
    }

    private RegistrationView savePhone(long telegramId, String rawPhone, boolean verifiedContact) {
        AppSettings offer = settings.lock();
        BotUser user = users.findByTelegramUserId(telegramId).orElse(null);
        if (user == null || (user.getRegistrationStatus() != RegistrationStatus.PHONE_REQUIRED
                && !(verifiedContact && user.getRegistrationStatus() == RegistrationStatus.COMPLETED)))
            return view(user, null);
        if (!identity.configured() || (offer.getPhoneKeyFingerprint() != null
                && !offer.getPhoneKeyFingerprint().equals(identity.fingerprint())))
            return view(user, "registration.unavailable");
        if (!exams.findById(user.getSelectedExamTypeId()).orElseThrow().getActive()) {
            user.setSelectedExamTypeId(null);
            user.setRegistrationStatus(RegistrationStatus.EXAM_TYPE_REQUIRED);
            return view(user, "registration.examUnavailable");
        }
        String canonical;
        try { canonical = normalizer.normalize(rawPhone); }
        catch (IllegalArgumentException exception) { return view(user, "registration.invalidPhone"); }
        String hash = identity.hash(canonical);
        var existing = users.findByPhoneIdentityHash(hash).orElse(null);
        if (existing != null && !existing.getId().equals(user.getId())) return view(user, "registration.duplicatePhone");
        if (user.getRegistrationStatus() == RegistrationStatus.COMPLETED) {
            if (!hash.equals(user.getPhoneIdentityHash())) return view(user, "registration.duplicatePhone");
            user.setPhoneE164(canonical);
            user.setPhoneVerificationStatus("VERIFIED_TELEGRAM_CONTACT");
            return view(user, null);
        }
        offer.setPhoneKeyFingerprint(identity.fingerprint());
        user.setPhoneIdentityHash(hash);
        user.setPhoneE164(canonical);
        user.setPhoneVerificationStatus(verifiedContact ? "VERIFIED_TELEGRAM_CONTACT" : "UNVERIFIED_TYPED");
        user.setRegistrationCompletedAt(Instant.now());
        user.setRegistrationStatus(RegistrationStatus.COMPLETED);
        // Flush the referenced identity first; both writes still commit atomically.
        users.saveAndFlush(user);
        AccessEntitlement entitlement = new AccessEntitlement();
        entitlement.setUserId(user.getId());
        entitlement.setExamTypeId(user.getSelectedExamTypeId());
        entitlement.setPhoneIdentityHash(hash);
        entitlement.setAccessLevel("FREE");
        entitlement.setPracticeLimit(offer.getFreePracticeLimit());
        entitlement.setMockLimit(offer.getFreeMockLimit());
        entitlement.setQuestionsPerMock(offer.getQuestionsPerMock());
        entitlement.setGrantSource("REGISTRATION");
        entitlement.setGrantedAt(Instant.now());
        entitlements.saveAndFlush(entitlement);
        return view(user, null);
    }

    private RegistrationView view(BotUser user, String error) {
        if (user == null) return new RegistrationView(RegistrationStatus.LANGUAGE_REQUIRED, "en",
                List.of(), error, null, null, null);
        if (user.getRegistrationStatus() == RegistrationStatus.PHONE_REQUIRED
                && !exams.findById(user.getSelectedExamTypeId()).orElseThrow().getActive()) {
            user.setSelectedExamTypeId(null);
            user.setRegistrationStatus(RegistrationStatus.EXAM_TYPE_REQUIRED);
        }
        var options = user.getRegistrationStatus() == RegistrationStatus.EXAM_TYPE_REQUIRED
                ? exams.findAllByActiveTrueOrderByDisplayOrderAscIdAsc().stream()
                    .map(e -> new RegistrationView.ExamOption(e.getId(), e.getName(), e.getNameAm(), false)).toList()
                : List.<RegistrationView.ExamOption>of();
        var grant = user.getRegistrationStatus() == RegistrationStatus.COMPLETED
                ? entitlements.findByUserIdAndExamTypeId(user.getId(), user.getSelectedExamTypeId()).orElseThrow() : null;
        return new RegistrationView(user.getRegistrationStatus(),
                user.getPreferredLanguage() == null ? "en" : user.getPreferredLanguage(), options, error,
                grant == null ? null : grant.getPracticeLimit(), grant == null ? null : grant.getMockLimit(),
                grant == null ? null : grant.getQuestionsPerMock());
    }
}
