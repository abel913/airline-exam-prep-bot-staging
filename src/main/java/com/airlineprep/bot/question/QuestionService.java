package com.airlineprep.bot.question;
import java.time.Instant;
import java.util.*;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import com.airlineprep.bot.category.CategoryRepository;
import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
@Service @Transactional
public class QuestionService {
 private final QuestionRepository questions;
 private final QuestionVersionRepository versions;
 private final QuestionValidationService validation;
 private final SettingsService settings;
 private final AdminChangeService changes;
 private final ExamTypeRepository exams;
 private final CategoryRepository categories;
 private final org.springframework.jdbc.core.JdbcTemplate jdbc;
 @PersistenceContext private EntityManager entityManager;
 public QuestionService(QuestionRepository q,QuestionVersionRepository v,QuestionValidationService validation,
   SettingsService s,AdminChangeService a,ExamTypeRepository e,CategoryRepository c,org.springframework.jdbc.core.JdbcTemplate jdbc) {
  questions=q; versions=v; this.validation=validation; settings=s; changes=a; exams=e; categories=c;this.jdbc=jdbc;
 }
 @Transactional(readOnly=true)
 public Question get(long id) {
  Question q=questions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  q.currentVersion.options.size();
  return q;
 }
 @Transactional(readOnly=true)
 public QuestionForm form(long id) { return QuestionForm.from(get(id)); }
 public long save(Long id,QuestionForm f,String actor) {
  settings.lock();
  validation.validate(f,false);
  Question q=id==null?new Question():get(id);
  if(id!=null) {
   stale(q,f.getExpectedRevision());
   if(q.status==QuestionStatus.ARCHIVED) throw new IllegalArgumentException("Archived questions cannot be edited.");
  }
  if(id==null) { q.createdBy=actor; q.updatedBy=actor; questions.saveAndFlush(q); }
  QuestionVersion v=q.currentVersion;
  if(v==null || v.publishedAt!=null) {
   int number=v==null?1:v.versionNumber+1;
   v=new QuestionVersion(); v.questionId=q.id; v.versionNumber=number; v.createdBy=actor;
  }
  org.springframework.beans.BeanUtils.copyProperties(f,v.content);
  v.options.clear();
  for(int i=0;i<f.getOptions().size();i++) if(!f.getOptions().get(i).isBlank())
   v.options.add(new AnswerOption(f.getOptions().get(i).strip(),Objects.equals(f.getCorrectOption(),i)));
  v.examName=f.examTypeId==null?"":exams.findById(f.examTypeId).orElseThrow().getName();
  v.categoryName=f.categoryId==null?"":categories.findById(f.categoryId).orElseThrow().getName();
  v.fingerprint=QuestionDuplicateService.fingerprint(f); v.stemFingerprint=QuestionDuplicateService.stem(f);
  v.reviewedAt=null; v.reviewedBy=null;
  versions.saveAndFlush(v);
  q.currentVersion=v; q.status=QuestionStatus.DRAFT; q.updatedBy=actor;
  // Always dirty the logical row so optimistic revisions advance independently of clock resolution.
  q.contentRevision++;
  questions.saveAndFlush(q);
  changes.record(actor,"QUESTION_SAVED","question:"+q.id,"","version:"+v.versionNumber);
  return q.id;
 }
 public void transition(long id,QuestionStatus target,Long expected,String actor) {
  settings.lock(); Question q=get(id); stale(q,expected);
  QuestionStatus old=q.status;
  boolean allowed=(old==QuestionStatus.DRAFT&&target==QuestionStatus.REVIEWED)
    || (old==QuestionStatus.REVIEWED&&(target==QuestionStatus.PUBLISHED||target==QuestionStatus.DRAFT))
    || (old!=QuestionStatus.ARCHIVED&&target==QuestionStatus.ARCHIVED);
  if(!allowed) throw new IllegalArgumentException("This lifecycle transition is not allowed.");
  if(target==QuestionStatus.REVIEWED||target==QuestionStatus.PUBLISHED) {
   if(q.currentVersion.options.stream().filter(o->o.correct).count()!=1)
    throw new IllegalArgumentException("Exactly one correct answer is required.");
   validation.validate(QuestionForm.from(q),true);
  }
  if(target==QuestionStatus.REVIEWED) { q.currentVersion.reviewedAt=Instant.now(); q.currentVersion.reviewedBy=actor; }
  if(target==QuestionStatus.DRAFT) { q.currentVersion.reviewedAt=null; q.currentVersion.reviewedBy=null; }
  if(target==QuestionStatus.PUBLISHED) q.currentVersion.publishedAt=Instant.now();
  q.status=target; q.updatedBy=actor; questions.saveAndFlush(q);
  changes.record(actor,"QUESTION_"+target,"question:"+id,old.name(),target.name());
 }
 public void restoreToDraft(long id,String actor) {
  settings.lock(); Question q=get(id);
  if(q.status!=QuestionStatus.ARCHIVED) throw new IllegalArgumentException("Only archived questions can be restored.");
  QuestionVersion previous=q.currentVersion;
  if(previous.publishedAt!=null) {
   QuestionVersion draft=new QuestionVersion(); draft.questionId=q.id; draft.versionNumber=previous.versionNumber+1;
   draft.createdBy=actor; org.springframework.beans.BeanUtils.copyProperties(previous.content,draft.content);
   draft.examName=previous.examName; draft.categoryName=previous.categoryName;
   draft.fingerprint=previous.fingerprint; draft.stemFingerprint=previous.stemFingerprint;
   for(AnswerOption option:previous.options) draft.options.add(new AnswerOption(option.text,option.correct));
   versions.saveAndFlush(draft); q.currentVersion=draft;
  } else {
   previous.reviewedAt=null; previous.reviewedBy=null;
  }
  q.status=QuestionStatus.DRAFT; q.updatedBy=actor; q.contentRevision++; questions.saveAndFlush(q);
  changes.record(actor,"QUESTION_RESTORED","question:"+id,"ARCHIVED","DRAFT");
 }
 public record HardDeleteAssessment(long id,QuestionStatus status,long revision,String preview,boolean eligible,String reason) {}
 @Transactional(readOnly=true)
 public List<HardDeleteAssessment> assessHardDelete(List<Long> ids) {
  return assessHardDelete(ids,false);
 }
 @Transactional(readOnly=true)
 public List<HardDeleteAssessment> assessNonPublishedHardDelete(List<Long> ids) {
  return assessHardDelete(ids,true);
 }
 private List<HardDeleteAssessment> assessHardDelete(List<Long> ids,boolean allowAllNonPublished) {
  if(ids==null||ids.isEmpty()) return List.of();
  String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
  String sql="SELECT q.id,q.status,q.revision,COALESCE(v.question_text,'') AS preview,"
   +"EXISTS(SELECT 1 FROM question_versions pv WHERE pv.question_id=q.id AND pv.published_at IS NOT NULL) AS previously_published,"
   +"EXISTS(SELECT 1 FROM practice_deliveries pd WHERE pd.question_id=q.id) AS practice_used,"
   +"EXISTS(SELECT 1 FROM practice_usage pu WHERE pu.question_id=q.id) AS practice_usage,"
   +"EXISTS(SELECT 1 FROM mock_items mi WHERE mi.question_id=q.id) AS mock_used,"
   +"EXISTS(SELECT 1 FROM question_import_rows ir WHERE ir.question_id=q.id) AS import_linked "
   +"FROM questions q LEFT JOIN question_versions v ON v.id=q.current_version_id WHERE q.id IN ("+marks+") ORDER BY q.id";
  return jdbc.query(sql,(rs,n)->{
   long id=rs.getLong("id");QuestionStatus status=QuestionStatus.valueOf(rs.getString("status"));
   boolean published=rs.getBoolean("previously_published"),practice=rs.getBoolean("practice_used")||rs.getBoolean("practice_usage"),mock=rs.getBoolean("mock_used"),imported=rs.getBoolean("import_linked");
   String reason=null;
   if(published) reason="Previously published. Archive it instead.";
   else if(status==QuestionStatus.PUBLISHED) reason="Published questions are protected.";
   else if(!allowAllNonPublished&&status!=QuestionStatus.DRAFT) reason="Question is currently "+status+" and cannot be deleted.";
   else if(practice) reason="Referenced by practice history.";
   else if(mock) reason="Linked to mock history.";
   else if(imported) reason="Protected by import/history relationship.";
   String preview=rs.getString("preview");if(preview.length()>160)preview=preview.substring(0,160);
    return new HardDeleteAssessment(rs.getLong("id"),status,rs.getLong("revision"),preview,reason==null,reason);
  },ids.toArray());
 }
 public void deleteSafeDraft(long id,Long expected,String actor) {
  settings.lock();Question q=questions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));stale(q,expected);
  HardDeleteAssessment assessment=assessHardDelete(List.of(id)).getFirst();
  if(!assessment.eligible()) throw new IllegalArgumentException(assessment.reason());
  hardDelete(q,actor,"safe unpublished question removed");
 }
 @Transactional
 public void deleteSafeNonPublished(long id,Long expected,String actor) {
  settings.lock();Question q=questions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));stale(q,expected);
  HardDeleteAssessment assessment=assessNonPublishedHardDelete(List.of(id)).getFirst();
  if(!assessment.eligible()) throw new IllegalArgumentException(assessment.reason());
  hardDelete(q,actor,"safe non-published question removed");
 }
 @Transactional
 public boolean hardDeleteUnusedImportQuestion(long id,long batchId,String actor) {
  settings.lock();
  HardDeleteAssessment assessment=assessImportHardDelete(id,batchId);
  if(!assessment.eligible()) return false;
  Question q=questions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  int marked=jdbc.update("UPDATE question_import_rows SET question_id=NULL,removed_question_id=? WHERE batch_id=? AND question_id=?",id,batchId,id);
  if(marked!=1) throw new IllegalArgumentException("Question is no longer linked to this import batch.");
  hardDelete(q,actor,"safe unused question removed from import batch "+batchId);
  return true;
 }
 @Transactional(readOnly=true)
 public HardDeleteAssessment assessImportHardDelete(long id,long batchId) {
  return assessImportHardDeletes(List.of(id),batchId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
 }
 @Transactional(readOnly=true)
 public List<HardDeleteAssessment> assessImportHardDeletes(List<Long> ids,long batchId) {
  if(ids==null||ids.isEmpty()) return List.of();
  String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
  String sql="SELECT q.id,q.status,q.revision,COALESCE(v.question_text,'') AS preview,"
   +"EXISTS(SELECT 1 FROM question_versions pv WHERE pv.question_id=q.id AND pv.published_at IS NOT NULL) AS previously_published,"
   +"EXISTS(SELECT 1 FROM practice_deliveries pd WHERE pd.question_id=q.id) AS practice_used,"
   +"EXISTS(SELECT 1 FROM mock_items mi WHERE mi.question_id=q.id) AS mock_used,"
   +"EXISTS(SELECT 1 FROM question_import_rows ir WHERE ir.question_id=q.id AND ir.batch_id<>?) AS other_import "
   +"FROM questions q LEFT JOIN question_versions v ON v.id=q.current_version_id WHERE q.id IN ("+marks+") AND EXISTS(SELECT 1 FROM question_import_rows ir WHERE ir.question_id=q.id AND ir.batch_id=?) ORDER BY q.id";
  Object[] args=new Object[ids.size()+2];args[0]=batchId;for(int i=0;i<ids.size();i++)args[i+1]=ids.get(i);args[args.length-1]=batchId;
  return jdbc.query(sql,(rs,n)->{
   QuestionStatus status=QuestionStatus.valueOf(rs.getString("status"));
   boolean published=rs.getBoolean("previously_published"),practice=rs.getBoolean("practice_used"),mock=rs.getBoolean("mock_used"),other=rs.getBoolean("other_import");
   String reason=null;
   if(published) reason="Previously published; preserve its versions and archive it.";
   else if(status!=QuestionStatus.DRAFT&&status!=QuestionStatus.REVIEWED) reason="Question is currently "+status+".";
   else if(practice) reason="Referenced by practice history.";
   else if(mock) reason="Linked to mock history.";
   else if(other) reason="Question has another import relationship.";
   String preview=rs.getString("preview");if(preview.length()>160)preview=preview.substring(0,160);
   return new HardDeleteAssessment(rs.getLong("id"),status,rs.getLong("revision"),preview,reason==null,reason);
  },args);
 }
 private void hardDelete(Question q,String actor,String detail) {
  long id=q.id;
  changes.record(actor,"QUESTION_DELETED","question:"+id,q.status.name(),detail);
  // The database permits a missing current version only while the logical row is DRAFT.
  q.status=QuestionStatus.DRAFT;
  q.currentVersion=null;questions.saveAndFlush(q);
  jdbc.update("DELETE FROM question_options WHERE version_id IN (SELECT id FROM question_versions WHERE question_id=?)",id);
  jdbc.update("DELETE FROM question_versions WHERE question_id=?",id);
  if(questions.deleteNonPublishedById(id)!=1) throw new IllegalArgumentException("Published questions are protected.");
  entityManager.detach(q);
 }
 private void stale(Question q,Long expected) {
  if(expected==null || expected!=q.revision) throw new IllegalArgumentException("This question changed. Reload before saving.");
 }
 @Transactional(readOnly=true)
 public Page<QuestionVersion> history(long id,int page) {
  get(id);
  var result=versions.findByQuestionIdOrderByVersionNumberDesc(id,PageRequest.of(Math.max(0,page),10));
  result.forEach(v->v.options.size()); return result;
 }
 @Transactional(readOnly=true)
 public Page<Question> search(String text,Long exam,Long category,QuestionStatus status,String pool,UseStatus rights,int page,String sort) {
  return search(text,exam,category,status,pool,rights,page,sort,null,"");
 }
 @Transactional(readOnly=true)
 public Page<Question> search(String text,Long exam,Long category,QuestionStatus status,String pool,UseStatus rights,int page,String sort,Difficulty difficulty,String tag) {
  if(page<0||page>100000||text!=null&&text.length()>200) throw new IllegalArgumentException("Invalid search bounds.");
  String normalized=QuestionTags.normalize(tag);
  if(normalized.contains(",")) throw new IllegalArgumentException("Filter by one tag.");
  Specification<Question> spec=(root,query,cb)->{
   var v=root.join("currentVersion"); var c=v.get("content");
   List<jakarta.persistence.criteria.Predicate> p=new ArrayList<>();
   if(status==null) p.add(cb.notEqual(root.get("status"),QuestionStatus.ARCHIVED)); else p.add(cb.equal(root.get("status"),status));
   if(exam!=null) p.add(cb.equal(c.get("examTypeId"),exam));
   if(category!=null) p.add(cb.equal(c.get("categoryId"),category));
   if(rights!=null) p.add(cb.equal(c.get("useStatus"),rights));
   if(difficulty!=null) p.add(cb.equal(c.get("difficulty"),difficulty));
   if(!normalized.isEmpty()) p.add(cb.like(cb.concat(cb.concat(",",c.get("tags")),","),"%,"+normalized+",%"));
   if(pool!=null && !pool.isBlank()) {
    if(!List.of("freePool","premiumPool","mockPool").contains(pool)) throw new IllegalArgumentException("Choose a valid pool.");
    p.add(cb.isTrue(c.get(pool)));
   }
   if(text!=null&&!text.isBlank()) {
    String escaped=text.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
    p.add(cb.like(cb.lower(c.get("questionText")),"%"+escaped+"%",'!'));
   }
   return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
  };
  String order="createdAt".equals(sort)?"createdAt":"updatedAt";
  return questions.findAll(spec,PageRequest.of(Math.max(0,page),20,Sort.by(Sort.Direction.DESC,order,"id")));
 }
}
