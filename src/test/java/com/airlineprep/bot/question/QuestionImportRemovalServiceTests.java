package com.airlineprep.bot.question;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionImportRemovalServiceTests {
 @SuppressWarnings({"unchecked","rawtypes"})
 @Test void oneQuestionFailureDoesNotStopRemovalOfRestOfBatch() {
  var imports=mock(QuestionImportService.class);var rows=mock(ImportRowRepository.class);
  var questions=mock(QuestionService.class);var audit=mock(QuestionImportRemovalAudit.class);var jdbc=mock(JdbcTemplate.class);
  ImportBatch batch=new ImportBatch();batch.filename="fictional.csv";batch.status=ImportStatus.IMPORTED;
  when(imports.get(7L)).thenReturn(batch);
  List chunk=List.of(new QuestionImportRemovalService.Row(11,101L,null,"First fictional item"),
   new QuestionImportRemovalService.Row(12,102L,null,"Second fictional item"));
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(chunk,List.of());
  when(questions.assessImportHardDelete(101L,7L)).thenReturn(new QuestionService.HardDeleteAssessment(101,QuestionStatus.DRAFT,1,"First",true,null));
  when(questions.assessImportHardDelete(102L,7L)).thenReturn(new QuestionService.HardDeleteAssessment(102,QuestionStatus.REVIEWED,2,"Second",true,null));
  when(questions.hardDeleteUnusedImportQuestion(101L,7L,"admin")).thenThrow(new IllegalStateException("isolated failure"));
  when(questions.hardDeleteUnusedImportQuestion(102L,7L,"admin")).thenReturn(true);

  var result=new QuestionImportRemovalService(imports,rows,questions,audit,jdbc).remove(7L,"admin");

  assertThat(result.linked()).isEqualTo(2);assertThat(result.deleted()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);
  assertThat(result.issues()).singleElement().satisfies(issue->{assertThat(issue.questionId()).isEqualTo(101);assertThat(issue.previousStatus()).isEqualTo("DRAFT");assertThat(issue.action()).isEqualTo("HARD_DELETE");});
  verify(questions).hardDeleteUnusedImportQuestion(102L,7L,"admin");verify(audit).record("admin",7,"fictional.csv",2,1,0,0,0,1);
 }
}
