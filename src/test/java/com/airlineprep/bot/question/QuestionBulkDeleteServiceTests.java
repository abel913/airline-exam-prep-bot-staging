package com.airlineprep.bot.question;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionBulkDeleteServiceTests {
 @Test void nonPublishedCleanupSkipsUnsafeRowsAndContinuesAfterIsolatedFailure() {
  var repository=mock(QuestionRepository.class);var questions=mock(QuestionService.class);var audit=mock(QuestionBulkDeleteAudit.class);
  var first=new QuestionService.HardDeleteAssessment(1,QuestionStatus.DRAFT,3,"First fictional question",true,null);
  var reviewed=new QuestionService.HardDeleteAssessment(2,QuestionStatus.REVIEWED,4,"Reviewed synthetic question",true,null);
  var archived=new QuestionService.HardDeleteAssessment(3,QuestionStatus.ARCHIVED,5,"Protected synthetic question",false,"Referenced by practice history.");
  when(repository.countNonPublished()).thenReturn(3L);
  when(repository.maximumId()).thenReturn(3L);
  when(repository.findNonPublishedIdsAfter(0L,3L,PageRequest.of(0,100))).thenReturn(List.of(1L,2L,3L));
  when(repository.findNonPublishedIdsAfter(3L,3L,PageRequest.of(0,100))).thenReturn(List.of());
  when(questions.assessNonPublishedHardDelete(List.of(1L,2L,3L))).thenReturn(List.of(first,reviewed,archived));
  when(questions.assessNonPublishedHardDelete(List.of(1L))).thenReturn(List.of(first));
  doThrow(new IllegalStateException("isolated simulated delete failure"))
   .when(questions).deleteSafeNonPublished(1L,3L,"admin");

  var service=new QuestionBulkDeleteService(repository,questions,audit);
  assertThat(service.preview()).isEqualTo(new QuestionBulkDeleteService.Plan(3,2,1));
  assertThatThrownBy(()->service.deleteAll("admin",1L)).isInstanceOf(IllegalArgumentException.class)
   .hasMessageContaining("eligible question count changed");

  var result=service.deleteAll("admin",2L);

  assertThat(result.found()).isEqualTo(3);assertThat(result.deleted()).isEqualTo(1);
  assertThat(result.failed()).isEqualTo(1);assertThat(result.skipped()).isEqualTo(1);
  assertThat(result.issues()).hasSize(2);
  verify(questions).deleteSafeNonPublished(2L,4L,"admin");
  verify(audit).record("admin",3,1,1,1);
 }
}
