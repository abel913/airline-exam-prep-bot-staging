package com.airlineprep.bot.question;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionBulkDeleteServiceTests {
 @Test void oneQuestionFailureDoesNotStopOtherEligibleDrafts() {
  var repository=mock(QuestionRepository.class);var questions=mock(QuestionService.class);var audit=mock(QuestionBulkDeleteAudit.class);
  var first=new QuestionService.HardDeleteAssessment(1,QuestionStatus.DRAFT,3,"First fictional question",true,null);
  var second=new QuestionService.HardDeleteAssessment(2,QuestionStatus.DRAFT,4,"Second fictional question",true,null);
  when(repository.findIdsAfter(eq(QuestionStatus.DRAFT),eq(0L),any(PageRequest.class))).thenReturn(List.of(1L,2L));
  when(repository.findIdsAfter(eq(QuestionStatus.DRAFT),eq(2L),any(PageRequest.class))).thenReturn(List.of());
  when(questions.assessHardDelete(List.of(1L,2L))).thenReturn(List.of(first,second));
  when(questions.assessHardDelete(List.of(1L))).thenReturn(List.of(first));
  doThrow(new IllegalStateException("isolated simulated delete failure"))
   .when(questions).deleteSafeDraft(1L,3L,"admin");

  var result=new QuestionBulkDeleteService(repository,questions,audit).deleteAll("admin");

  assertThat(result.draftsFound()).isEqualTo(2);assertThat(result.deleted()).isEqualTo(1);
  assertThat(result.failed()).isEqualTo(1);assertThat(result.skipped()).isZero();
  assertThat(result.issues()).singleElement().satisfies(issue->{assertThat(issue.questionId()).isEqualTo(1);assertThat(issue.kind()).isEqualTo("failed");});
  verify(questions).deleteSafeDraft(2L,4L,"admin");verify(audit).record("admin",2,1,0,1);
 }
}
