package com.airlineprep.bot.question;

import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionImportRemovalAudit {
 private final AdminChangeService changes;
 public QuestionImportRemovalAudit(AdminChangeService changes) { this.changes=changes; }
 @Transactional
 public void record(String actor,long batch,String filename,int linked,int deleted,int archived,int inactive,int skipped,int failed) {
  changes.record(actor,"IMPORT_QUESTIONS_REMOVED","import:"+batch,"filename:"+filename,
   "linked:"+linked+", deleted:"+deleted+", archived:"+archived+", inactive:"+inactive+", skipped:"+skipped+", failed:"+failed);
 }
}
