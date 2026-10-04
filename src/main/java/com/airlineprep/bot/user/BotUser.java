package com.airlineprep.bot.user;

import jakarta.persistence.*;
import com.airlineprep.bot.common.TimedEntity;

@Entity
@Table(name = "bot_users")
public class BotUser extends TimedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long telegramUserId;

    private String preferredLanguage;

    private Long selectedExamTypeId;
    @Enumerated(EnumType.STRING) @Column(length = 24)
    private RegistrationStatus registrationStatus;
    @Column(length = 64)
    private String phoneIdentityHash;

    @Column(name = "phone_e164", length = 16)
    private String phoneE164;

    @Column(name = "phone_verification_status", length = 32)
    private String phoneVerificationStatus;

    private java.time.Instant registrationCompletedAt;
    public Long getId() { return id; }
    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long value) { telegramUserId = value; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String value) { preferredLanguage = value; }
    public Long getSelectedExamTypeId() { return selectedExamTypeId; }
    public void setSelectedExamTypeId(Long value) { selectedExamTypeId = value; }
    public RegistrationStatus getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(RegistrationStatus value) { registrationStatus = value; }
    public String getPhoneIdentityHash() { return phoneIdentityHash; }
    public void setPhoneIdentityHash(String value) { phoneIdentityHash = value; }
    public String getPhoneE164() { return phoneE164; }
    public void setPhoneE164(String value) { phoneE164 = value; }
    public String getPhoneVerificationStatus() { return phoneVerificationStatus; }
    public void setPhoneVerificationStatus(String value) { phoneVerificationStatus = value; }
    public java.time.Instant getRegistrationCompletedAt() { return registrationCompletedAt; }
    public void setRegistrationCompletedAt(java.time.Instant value) { registrationCompletedAt = value; }
}
