package com.airlineprep.bot.question;
import jakarta.persistence.*;
@Embeddable
public class QuestionContent {
 @Column(length=263)
 String tags = "";
 public String getTags() { return tags; }
 public void setTags(String value) { tags=value; }

 Long examTypeId;
 public Long getExamTypeId() { return examTypeId; }
 public void setExamTypeId(Long value) { examTypeId=value; }

 Long categoryId;
 public Long getCategoryId() { return categoryId; }
 public void setCategoryId(Long value) { categoryId=value; }
 @Column(columnDefinition="text")
 String questionText = "";
 public String getQuestionText() { return questionText; }
 public void setQuestionText(String value) { questionText=value; }
 @Column(columnDefinition="text")
 String explanation = "";
 public String getExplanation() { return explanation; }
 public void setExplanation(String value) { explanation=value; }
 @Enumerated(EnumType.STRING)
 Difficulty difficulty = Difficulty.UNSPECIFIED;
 public Difficulty getDifficulty() { return difficulty; }
 public void setDifficulty(Difficulty value) { difficulty=value; }

 String sourceType = "";
 public String getSourceType() { return sourceType; }
 public void setSourceType(String value) { sourceType=value; }

 String sourceTitle = "";
 public String getSourceTitle() { return sourceTitle; }
 public void setSourceTitle(String value) { sourceTitle=value; }

 String sourceReference = "";
 public String getSourceReference() { return sourceReference; }
 public void setSourceReference(String value) { sourceReference=value; }

 Integer sourceYear;
 public Integer getSourceYear() { return sourceYear; }
 public void setSourceYear(Integer value) { sourceYear=value; }

 String sourceNotes = "";
 public String getSourceNotes() { return sourceNotes; }
 public void setSourceNotes(String value) { sourceNotes=value; }
 @Enumerated(EnumType.STRING)
 UseStatus useStatus = UseStatus.UNKNOWN_REVIEW_REQUIRED;
 public UseStatus getUseStatus() { return useStatus; }
 public void setUseStatus(UseStatus value) { useStatus=value; }

 boolean freePool;
 public boolean getFreePool() { return freePool; }
 public void setFreePool(boolean value) { freePool=value; }

 boolean premiumPool;
 public boolean getPremiumPool() { return premiumPool; }
 public void setPremiumPool(boolean value) { premiumPool=value; }

 boolean mockPool;
 public boolean getMockPool() { return mockPool; }
 public void setMockPool(boolean value) { mockPool=value; }
}
