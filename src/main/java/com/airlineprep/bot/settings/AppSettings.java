package com.airlineprep.bot.settings;

import jakarta.persistence.*;
import com.airlineprep.bot.common.TimedEntity;

@Entity
@Table(name = "app_settings")
public class AppSettings extends TimedEntity {
    private boolean maintenanceEnabled;
    public boolean getMaintenanceEnabled() { return maintenanceEnabled; }
    public void setMaintenanceEnabled(boolean value) { maintenanceEnabled=value; }
    private Integer mockDurationMinutes;
    public Integer getMockDurationMinutes() { return mockDurationMinutes; }
    public void setMockDurationMinutes(Integer value) { mockDurationMinutes=value; }
    @Id
    private Long id;

    private int freePracticeLimit;

    private int freeMockLimit;

    private int questionsPerMock;
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal lifetimePrice;
    @Column(length = 3)
    private String currency;

    private boolean paymentEnabled;

    private boolean manualPaymentEnabled;
    @Column(length = 500)
    private String supportInfo;
    @Column(length = 64)
    private String phoneKeyFingerprint;
    public Long getId() { return id; }
    public int getFreePracticeLimit() { return freePracticeLimit; }
    public void setFreePracticeLimit(int value) { freePracticeLimit = value; }
    public int getFreeMockLimit() { return freeMockLimit; }
    public void setFreeMockLimit(int value) { freeMockLimit = value; }
    public int getQuestionsPerMock() { return questionsPerMock; }
    public void setQuestionsPerMock(int value) { questionsPerMock = value; }
    public java.math.BigDecimal getLifetimePrice() { return lifetimePrice; }
    public void setLifetimePrice(java.math.BigDecimal value) { lifetimePrice = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public boolean getPaymentEnabled() { return paymentEnabled; }
    public void setPaymentEnabled(boolean value) { paymentEnabled = value; }
    public boolean getManualPaymentEnabled() { return manualPaymentEnabled; }
    public void setManualPaymentEnabled(boolean value) { manualPaymentEnabled = value; }
    public String getSupportInfo() { return supportInfo; }
    public void setSupportInfo(String value) { supportInfo = value; }
    public String getPhoneKeyFingerprint() { return phoneKeyFingerprint; }
    public void setPhoneKeyFingerprint(String value) { phoneKeyFingerprint = value; }
}
