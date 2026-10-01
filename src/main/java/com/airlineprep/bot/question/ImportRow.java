package com.airlineprep.bot.question;
import jakarta.persistence.*;
import com.airlineprep.bot.common.TimedEntity;
@Entity @Table(name="question_import_rows")
public class ImportRow extends TimedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
 public Long getId() { return id; }

 Long batchId;
 public Long getBatchId() { return batchId; }

 int rowNumber;
 public int getRowNumber() { return rowNumber; }
 @Column(columnDefinition="text")
 String payload;
 public String getPayload() { return payload; }

 String questionPreview;
 public String getQuestionPreview() { return questionPreview; }

 String examCode;
 public String getExamCode() { return examCode; }

 String categoryCode;
 public String getCategoryCode() { return categoryCode; }

 String useStatus;
 public String getUseStatus() { return useStatus; }

 String errors;
 public String getErrors() { return errors; }
 @Enumerated(EnumType.STRING)
 DuplicateStatus duplicateStatus;
 public DuplicateStatus getDuplicateStatus() { return duplicateStatus; }

 String duplicateReason;
 public String getDuplicateReason() { return duplicateReason; }

 Long questionId;
 public Long getQuestionId() { return questionId; }

 Long removedQuestionId;
 public Long getRemovedQuestionId() { return removedQuestionId; }

}
