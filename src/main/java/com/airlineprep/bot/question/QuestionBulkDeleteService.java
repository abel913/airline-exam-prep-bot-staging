package com.airlineprep.bot.question;

import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class QuestionBulkDeleteService {
 private static final int CHUNK=100;
 private final QuestionRepository repository;
 private final QuestionService questions;
 private final QuestionBulkDeleteAudit audit;
 public QuestionBulkDeleteService(QuestionRepository repository,QuestionService questions,QuestionBulkDeleteAudit audit) {
  this.repository=repository;this.questions=questions;this.audit=audit;
 }
 public record Plan(long nonPublished,long eligible,long protectedCount) {}
 public record Issue(long questionId,String preview,String reason,String kind) {}
 public record Result(long found,long deleted,long skipped,long failed,List<Issue> issues) {}
 public Plan preview() {
  long nonPublished=repository.countNonPublished(),eligible=0,cursor=0;List<Long> ids;
  long upperBound=repository.maximumId();
  do {
   ids=repository.findNonPublishedIdsAfter(cursor,upperBound,PageRequest.of(0,CHUNK));
   if(!ids.isEmpty()) {
    cursor=ids.getLast();
    eligible+=questions.assessNonPublishedHardDelete(ids).stream().filter(QuestionService.HardDeleteAssessment::eligible).count();
   }
  } while(ids.size()==CHUNK);
  return new Plan(nonPublished,eligible,nonPublished-eligible);
 }
 public Result deleteAll(String actor,long expectedEligible) {
  Plan current=preview();
  if(current.eligible()!=expectedEligible) throw new IllegalArgumentException("The eligible question count changed. Review the updated confirmation page.");
  long found=0,deleted=0,skipped=0,failed=0,cursor=0;List<Long> ids;List<Issue> issues=new ArrayList<>();
  long upperBound=repository.maximumId();
  do {
   ids=repository.findNonPublishedIdsAfter(cursor,upperBound,PageRequest.of(0,CHUNK));
   if(ids.isEmpty()) break;
   cursor=ids.getLast();found+=ids.size();
   Map<Long,QuestionService.HardDeleteAssessment> assessments=new HashMap<>();
   questions.assessNonPublishedHardDelete(ids).forEach(a->assessments.put(a.id(),a));
   for(Long id:ids) {
    var assessment=assessments.get(id);
    if(assessment==null) { skipped++;issues.add(new Issue(id,"","Question was removed by another admin action.","skipped"));continue; }
    if(!assessment.eligible()) { skipped++;issues.add(new Issue(id,assessment.preview(),assessment.reason(),"skipped"));continue; }
    try { questions.deleteSafeNonPublished(id,assessment.revision(),actor);deleted++; }
    catch(RuntimeException ex) {
     var currentAssessment=questions.assessNonPublishedHardDelete(List.of(id)).stream().findFirst().orElse(null);
     if(currentAssessment==null) { skipped++;issues.add(new Issue(id,assessment.preview(),"Question was removed by another admin action.","skipped")); }
     else if(!currentAssessment.eligible()) { skipped++;issues.add(new Issue(id,currentAssessment.preview(),currentAssessment.reason(),"skipped")); }
     else { failed++;issues.add(new Issue(id,currentAssessment.preview(),"Could not safely delete this question. Reload and review it before retrying.","failed")); }
    }
   }
  } while(ids.size()==CHUNK);
  audit.record(actor,found,deleted,skipped,failed);
  return new Result(found,deleted,skipped,failed,List.copyOf(issues));
 }
}
