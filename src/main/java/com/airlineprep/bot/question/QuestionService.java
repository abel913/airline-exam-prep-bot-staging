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
@Service @Transactional
public class QuestionService {
 private final QuestionRepository questions;
 private final QuestionVersionRepository versions;
 private final QuestionValidationService validation;
 private final SettingsService settings;
 private final AdminChangeService changes;
 private final ExamTypeRepository exams;
 private final CategoryRepository categories;
 public QuestionService(QuestionRepository q,QuestionVersionRepository v,QuestionValidationService validation,
   SettingsService s,AdminChangeService a,ExamTypeRepository e,CategoryRepository c) {
  questions=q; versions=v; this.validation=validation; settings=s; changes=a; exams=e; categories=c;
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
  q.status=target; q.updatedBy=actor;
  changes.record(actor,"QUESTION_"+target,"question:"+id,old.name(),target.name());
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
