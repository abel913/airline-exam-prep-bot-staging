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
 public record Plan(int drafts,int eligible,int protectedCount) {}
 public record Issue(long questionId,String preview,String reason,String kind) {}
 public record Result(int draftsFound,int deleted,int skipped,int failed,List<Issue> issues) {}
 public Plan preview() {
  int drafts=0,eligible=0;long cursor=0;List<Long> ids;
  do {
   ids=repository.findIdsAfter(QuestionStatus.DRAFT,cursor,PageRequest.of(0,CHUNK));
   if(!ids.isEmpty()) {
    cursor=ids.getLast();drafts+=ids.size();
    eligible+=(int)questions.assessHardDelete(ids).stream().filter(QuestionService.HardDeleteAssessment::eligible).count();
   }
  } while(ids.size()==CHUNK);
  return new Plan(drafts,eligible,drafts-eligible);
 }
 public Result deleteAll(String actor) {
  int found=0,deleted=0,skipped=0,failed=0;long cursor=0;List<Long> ids;List<Issue> issues=new ArrayList<>();
  do {
   ids=repository.findIdsAfter(QuestionStatus.DRAFT,cursor,PageRequest.of(0,CHUNK));
   if(ids.isEmpty()) continue;
   cursor=ids.getLast();found+=ids.size();
   Map<Long,QuestionService.HardDeleteAssessment> assessments=new HashMap<>();
   questions.assessHardDelete(ids).forEach(a->assessments.put(a.id(),a));
   for(Long id:ids) {
    var assessment=assessments.get(id);
    if(assessment==null) { skipped++;issues.add(new Issue(id,"","Question was removed by another admin action.","skipped"));continue; }
    if(!assessment.eligible()) { skipped++;issues.add(new Issue(id,assessment.preview(),assessment.reason(),"skipped"));continue; }
    try { questions.deleteSafeDraft(id,assessment.revision(),actor);deleted++; }
    catch(RuntimeException ex) {
     var current=questions.assessHardDelete(List.of(id)).stream().findFirst().orElse(null);
     if(current==null) { skipped++;issues.add(new Issue(id,assessment.preview(),"Question was removed by another admin action.","skipped")); }
     else if(!current.eligible()) { skipped++;issues.add(new Issue(id,current.preview(),current.reason(),"skipped")); }
     else { failed++;issues.add(new Issue(id,current.preview(),"Could not safely delete this question. Reload and review it before retrying.","failed")); }
    }
   }
  } while(ids.size()==CHUNK);
  audit.record(actor,found,deleted,skipped,failed);
  return new Result(found,deleted,skipped,failed,List.copyOf(issues));
 }
}
