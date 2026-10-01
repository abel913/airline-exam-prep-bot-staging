package com.airlineprep.bot.question;
import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class QuestionImportBulkAudit {
 private final AdminChangeService changes;
 public QuestionImportBulkAudit(AdminChangeService changes) { this.changes=changes; }
 @Transactional
 public void record(String actor,long batch,int published,int already,int skipped) {
  changes.record(actor,"IMPORT_BULK_REVIEW_PUBLISH","import:"+batch,"","published:"+published+", already_published:"+already+", skipped:"+skipped);
 }
}
