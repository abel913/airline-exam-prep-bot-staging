package com.airlineprep.bot.question;
import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class QuestionBulkDeleteAudit {
 private final AdminChangeService changes;
 public QuestionBulkDeleteAudit(AdminChangeService changes) { this.changes=changes; }
 @Transactional
 public void record(String actor,long found,long deleted,long skipped,long failed) {
  changes.record(actor,"BULK_NON_PUBLISHED_QUESTIONS_DELETED","questions","found:"+found,
   "deleted:"+deleted+", skipped:"+skipped+", failed:"+failed);
 }
}
