package com.airlineprep.bot.access;

import jakarta.persistence.*;
import com.airlineprep.bot.common.TimedEntity;

@Entity
@Table(name = "access_entitlements")
public class AccessEntitlement extends TimedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long examTypeId;
    @Column(length = 64)
    private String phoneIdentityHash;

    private String accessLevel;

    private int practiceLimit;

    private int mockLimit;

    private int questionsPerMock;

    private int practiceUsed;

    private int mocksUsed;

    private String grantSource;

    private java.time.Instant grantedAt;
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getExamTypeId() { return examTypeId; }
    public void setExamTypeId(Long value) { examTypeId = value; }
    public String getPhoneIdentityHash() { return phoneIdentityHash; }
    public void setPhoneIdentityHash(String value) { phoneIdentityHash = value; }
    public String getAccessLevel() { return accessLevel; }
    public void setAccessLevel(String value) { accessLevel = value; }
    public int getPracticeLimit() { return practiceLimit; }
    public void setPracticeLimit(int value) { practiceLimit = value; }
    public int getMockLimit() { return mockLimit; }
    public void setMockLimit(int value) { mockLimit = value; }
    public int getQuestionsPerMock() { return questionsPerMock; }
    public void setQuestionsPerMock(int value) { questionsPerMock = value; }
    public int getPracticeUsed() { return practiceUsed; }
    public void setPracticeUsed(int value) { practiceUsed = value; }
    public int getMocksUsed() { return mocksUsed; }
    public void setMocksUsed(int value) { mocksUsed = value; }
    public String getGrantSource() { return grantSource; }
    public void setGrantSource(String value) { grantSource = value; }
    public java.time.Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(java.time.Instant value) { grantedAt = value; }
}
