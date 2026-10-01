package com.airlineprep.bot.question;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QuestionImportRemovalService {
 private static final int CHUNK=100;
 private final QuestionImportService imports;
 private final ImportRowRepository rows;
 private final QuestionService questions;
 private final QuestionImportRemovalAudit audit;
 private final JdbcTemplate jdbc;
 public QuestionImportRemovalService(QuestionImportService imports,ImportRowRepository rows,QuestionService questions,QuestionImportRemovalAudit audit,JdbcTemplate jdbc) {
  this.imports=imports;this.rows=rows;this.questions=questions;this.audit=audit;this.jdbc=jdbc;
 }
 public record Issue(long questionId,String preview,String previousStatus,String action,String reason) {}
 public record Plan(String filename,int linked,int safeDelete,int archive,int alreadyInactive) {}
 public record Result(String filename,int linked,int deleted,int archived,int alreadyInactive,int skipped,int failed,List<Issue> issues) {}
 record Row(long id,Long questionId,Long removedId,String preview) {}
 public Plan preview(long batchId) {
  ImportBatch batch=imports.get(batchId);
  List<Row> linked=linkedRows(batchId);
  int deleted=0,archive=0,inactive=0;
  List<Long> active=linked.stream().filter(r->r.questionId()!=null).map(Row::questionId).toList();
  Map<Long,QuestionService.HardDeleteAssessment> assessments=new HashMap<>();
  questions.assessImportHardDeletes(active,batchId).forEach(a->assessments.put(a.id(),a));
  for(Row row:linked) {
   if(row.questionId()==null) { inactive++;continue; }
   var assessment=assessments.get(row.questionId());
   if(assessment.status()==QuestionStatus.ARCHIVED) inactive++;
   else if(assessment.eligible()) deleted++;
   else archive++;
  }
  return new Plan(batch.filename,linked.size(),deleted,archive,inactive);
 }
 public Result remove(long batchId,String actor) {
  ImportBatch batch=imports.get(batchId);
  if(batch.status!=ImportStatus.IMPORTED) throw new IllegalArgumentException("Only imported batches have created questions to remove.");
  int linked=0,deleted=0,archived=0,inactive=0,skipped=0,failed=0;
  List<Issue> issues=new ArrayList<>();long after=0;
  List<Row> chunk;
  do {
   chunk=rowsAfter(batchId,after);if(!chunk.isEmpty()) after=chunk.getLast().id();
   linked+=chunk.size();
   for(Row row:chunk) {
    if(row.questionId()==null) { inactive++;continue; }
    String previousStatus="UNKNOWN",action="REMOVE_OR_ARCHIVE";
    try {
     var assessment=questions.assessImportHardDelete(row.questionId(),batchId);
     previousStatus=assessment.status().name();
     if(assessment.status()==QuestionStatus.ARCHIVED) { inactive++;continue; }
     if(assessment.eligible()) {
      action="HARD_DELETE";
      if(questions.hardDeleteUnusedImportQuestion(row.questionId(),batchId,actor)) deleted++;
      else { skipped++;issues.add(new Issue(row.questionId(),row.preview(),assessment.status().name(),"HARD_DELETE","Question safety checks changed; question was not removed.")); }
     } else {
      action="ARCHIVE";
      questions.transition(row.questionId(),QuestionStatus.ARCHIVED,assessment.revision(),actor);archived++;
     }
    } catch(RuntimeException ex) {
     failed++;
     String reason=ex.getMessage()==null?"Operation failed.":ex.getMessage();
     if(reason.length()>240) reason=reason.substring(0,240);
     issues.add(new Issue(row.questionId(),row.preview(),previousStatus,action,reason));
    }
   }
  } while(chunk.size()==CHUNK);
  audit.record(actor,batchId,batch.filename,linked,deleted,archived,inactive,skipped,failed);
  return new Result(batch.filename,linked,deleted,archived,inactive,skipped,failed,List.copyOf(issues));
 }
 private List<Row> linkedRows(long batchId) {
  return jdbc.query("SELECT id,question_id,removed_question_id,question_preview FROM question_import_rows WHERE batch_id=? AND (question_id IS NOT NULL OR removed_question_id IS NOT NULL) ORDER BY id",
   (rs,n)->new Row(rs.getLong("id"),(Long)rs.getObject("question_id"),(Long)rs.getObject("removed_question_id"),rs.getString("question_preview")),batchId);
 }
 private List<Row> rowsAfter(long batchId,long after) {
  return jdbc.query("SELECT id,question_id,removed_question_id,question_preview FROM question_import_rows WHERE batch_id=? AND id>? AND (question_id IS NOT NULL OR removed_question_id IS NOT NULL) ORDER BY id LIMIT ?",
   (rs,n)->new Row(rs.getLong("id"),(Long)rs.getObject("question_id"),(Long)rs.getObject("removed_question_id"),rs.getString("question_preview")),batchId,after,CHUNK);
 }
}
