package com.airlineprep.bot.question;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QuestionImportBulkService {
 private final QuestionImportService imports;
 private final ImportRowRepository rows;
 private final QuestionService questions;
 private final QuestionImportBulkAudit audit;
 public QuestionImportBulkService(QuestionImportService imports,ImportRowRepository rows,QuestionService questions,QuestionImportBulkAudit audit) {
  this.imports=imports;this.rows=rows;this.questions=questions;this.audit=audit;
 }
 public record Failure(long questionId,String preview,String reason) {}
 public record Result(int imported,int published,int alreadyPublished,int skipped,List<Failure> failures) {}
 public Result reviewAndPublish(long batchId,String actor) {
  ImportBatch batch=imports.get(batchId);
  if(batch.getStatus()!=ImportStatus.IMPORTED) throw new IllegalArgumentException("Only successfully imported batches can be published.");
  int published=0,already=0,skipped=0; List<Failure> failures=new ArrayList<>();
  int page=0; org.springframework.data.domain.Page<ImportRow> slice;
  do {
   slice=rows.findByBatchIdAndQuestionIdIsNotNullOrderByRowNumber(batchId,org.springframework.data.domain.PageRequest.of(page++,100));
   for(ImportRow row:slice.getContent()) {
    long id=row.getQuestionId();
    try {
     Question q=questions.get(id);
     if(q.getStatus()==QuestionStatus.PUBLISHED) { already++; continue; }
     if(q.getStatus()==QuestionStatus.DRAFT) {
      questions.transition(id,QuestionStatus.REVIEWED,q.getRevision(),actor);
      q=questions.get(id);
     }
     if(q.getStatus()==QuestionStatus.REVIEWED) {
      questions.transition(id,QuestionStatus.PUBLISHED,q.getRevision(),actor); published++;
     } else {
      skipped++;failures.add(new Failure(id,preview(q),"Question is "+q.getStatus()+" and cannot be published from this state."));
     }
    } catch(IllegalArgumentException ex) {
     skipped++;String preview=row.getQuestionPreview();
     String reason=ex.getMessage()==null?"Lifecycle validation failed.":ex.getMessage();
     if(reason.contains("Resolve content rights")) {
      Question rejected=questions.get(id);reason+=" Rights status: "+rejected.getCurrentVersion().getContent().getUseStatus()+".";
     }
     failures.add(new Failure(id,preview,reason));
    }
   }
  } while(slice.hasNext());
  audit.record(actor,batchId,published,already,skipped);
  return new Result(batch.getImportedRows(),published,already,skipped,List.copyOf(failures));
 }
 private String preview(Question q) {
  String value=q.getCurrentVersion().getContent().getQuestionText();
  return value.length()>160?value.substring(0,160):value;
 }
}
