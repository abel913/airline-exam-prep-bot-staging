package com.airlineprep.bot.question;
import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class QuestionBulkDeleteAudit {
 private final AdminChangeService changes;
 public QuestionBulkDeleteAudit(AdminChangeService changes) { this.changes=changes; }
 @Transactional
 public void record(String actor,int found,int deleted,int skipped,int failed) {
  changes.record(actor,"BULK_DRAFTS_DELETED","questions","found:"+found,
   "deleted:"+deleted+", skipped:"+skipped+", failed:"+failed);
 }
}
