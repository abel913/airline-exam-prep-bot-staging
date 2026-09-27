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
        if (user == null || user.getRegistrationStatus() != RegistrationStatus.EXAM_TYPE_REQUIRED)
            return view(user, null);
        var exam = exams.findById(examId);
        if (exam.isEmpty() || !exam.get().getActive()) return view(user, "registration.examUnavailable");
        user.setSelectedExamTypeId(examId);
        user.setRegistrationStatus(RegistrationStatus.PHONE_REQUIRED);
        return view(user, null);
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
        AppSettings offer = settings.lock();
        BotUser user = users.findByTelegramUserId(telegramId).orElse(null);
        if (contactOwner == null || contactOwner != telegramId) return view(user, "registration.ownContact");
        if (user == null || user.getRegistrationStatus() != RegistrationStatus.PHONE_REQUIRED)
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
        if (users.findByPhoneIdentityHash(hash).isPresent()) return view(user, "registration.duplicatePhone");
        offer.setPhoneKeyFingerprint(identity.fingerprint());
        user.setPhoneIdentityHash(hash);
        user.setRegistrationCompletedAt(Instant.now());
        user.setRegistrationStatus(RegistrationStatus.COMPLETED);
        // Flush the referenced identity first; both writes still commit atomically.
        users.saveAndFlush(user);
        AccessEntitlement entitlement = new AccessEntitlement();
        entitlement.setUserId(user.getId());
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
                    .map(e -> new RegistrationView.ExamOption(e.getId(), e.getName(), e.getNameAm())).toList()
                : List.<RegistrationView.ExamOption>of();
        var grant = user.getRegistrationStatus() == RegistrationStatus.COMPLETED
                ? entitlements.findByUserId(user.getId()).orElseThrow() : null;
        return new RegistrationView(user.getRegistrationStatus(),
                user.getPreferredLanguage() == null ? "en" : user.getPreferredLanguage(), options, error,
                grant == null ? null : grant.getPracticeLimit(), grant == null ? null : grant.getMockLimit(),
                grant == null ? null : grant.getQuestionsPerMock());
    }
}
