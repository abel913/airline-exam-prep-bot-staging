package com.airlineprep.bot.settings;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.validation.annotation.Validated
public class SettingsService {
    private final AppSettingsRepository settings;
    private final com.airlineprep.bot.audit.AdminChangeService changes;
    public SettingsService(AppSettingsRepository settings, com.airlineprep.bot.audit.AdminChangeService changes) {
        this.settings = settings; this.changes = changes;
    }
    @Transactional(readOnly = true)
    public AppSettings current() { return settings.findById(1L).orElseThrow(); }
    @Transactional
    public void maintenance(boolean enabled,String actor) {
        AppSettings current=lock();
        if(current.getMaintenanceEnabled()==enabled) return;
        changes.record(actor,"MAINTENANCE_CHANGED","settings:1",Boolean.toString(current.getMaintenanceEnabled()),Boolean.toString(enabled));
        current.setMaintenanceEnabled(enabled);
    }
    // A short database lock serializes onboarding writes and offer changes.
    // No network calls are performed while this lock is held.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AppSettings lock() { return settings.lock(); }
    @Transactional
    public void update(@jakarta.validation.Valid SettingsForm form, String actor) {
        AppSettings current = lock();
        String before = SettingsForm.from(current).toString();
        current.setFreePracticeLimit(form.freePracticeLimit());
        current.setFreeMockLimit(form.freeMockLimit());
        current.setQuestionsPerMock(form.questionsPerMock());
        current.setLifetimePrice(form.lifetimePrice());
        current.setCurrency(form.currency());
        current.setPaymentEnabled(form.paymentEnabled());
        current.setManualPaymentEnabled(form.manualPaymentEnabled());
        current.setSupportInfo(form.supportInfo());
        current.setMockDurationMinutes(form.mockDurationMinutes());
        changes.record(actor,"SETTINGS_UPDATED","settings:1",before,form.toString());
    }

    @Transactional
    public void updateStudyReminderCampaign(StudyReminderCampaignForm form, String actor) {
        String error=form.validationError();
        if(error!=null) throw new IllegalArgumentException(error);
        AppSettings current=lock();
        String before=current.isStudyRemindersGloballyEnabled()+"|"+current.getStudyRemindersStartAt()+"|"+current.getStudyRemindersEndAt();
        current.setStudyRemindersGloballyEnabled(form.enabled());
        current.setStudyRemindersStartAt(form.startInstant());
        current.setStudyRemindersEndAt(form.endInstant());
        current.setStudyRemindersUpdatedAt(java.time.Instant.now());
        current.setStudyRemindersUpdatedBy(actor);
        String after=form.enabled()+"|"+form.startInstant()+"|"+form.endInstant();
        changes.record(actor,"STUDY_REMINDER_CAMPAIGN_UPDATED","settings:1",before,after);
    }
}
