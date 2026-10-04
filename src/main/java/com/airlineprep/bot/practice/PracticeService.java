package com.airlineprep.bot.practice;
import java.time.*;
import java.util.*;
import com.airlineprep.bot.access.StudentAccess;
import com.airlineprep.bot.access.StudentAccess.Student;
import com.airlineprep.bot.common.*;
import com.airlineprep.bot.question.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.airlineprep.bot.common.ExamStore.values;
@Service @Transactional
public class PracticeService {
 private final StudentAccess access;
 private final ExamStore store;
 private final StudentQuestionSelector selector;
 private final Clock clock;
 public PracticeService(StudentAccess a,ExamStore s,StudentQuestionSelector q,Clock c) { access=a;store=s;selector=q;clock=c; }
 public record Delivery(long id,long userId,long questionId,long versionId,Long category,Long nextId,Long reviewId,Integer selected) {}
 public record View(Student student,Delivery delivery,StudentQuestion question) {
  public boolean answered() { return delivery.selected()!=null; }
  public boolean correct() { return answered()&&question.option(delivery.selected()).correct(); }
 }
 public List<StudentQuestionSelector.CategoryChoice> categories(long sender) { return selector.categories(access.lock(sender)); }
 public List<StudentQuestionSelector.CategoryChoice> categories(long sender,int page) { return selector.categories(access.lock(sender),page); }
 public View next(long sender,Long category,Long previous,boolean review) {
  Student s=access.lock(sender);
  Delivery old=previous==null?null:own(s,previous);
  if(old!=null) {
   category=old.category();
   Long linked=review?old.reviewId():old.nextId();
   if(linked!=null) return view(s,own(s,linked));
  }
  access.category(s,category);
  if(previous==null) {
   var active=store.jdbc().query("SELECT d.* FROM practice_sessions p JOIN practice_deliveries d ON d.id=p.current_delivery_id WHERE p.user_id=? AND p.exam_type_id=?",
    (r,n)->read(r),s.id(),s.examId());
   if(!active.isEmpty()&&active.getFirst().selected()==null&&Objects.equals(active.getFirst().category(),category)
     &&(!review||used(s,active.getFirst().questionId())))
    return view(s,active.getFirst());
  }
  if(!review&&!s.lifetime()&&s.practiceRemaining()==0) throw new ExamException("practice.limit");
  Long version=selector.practice(s,category,review);
  if(version==null&&s.lifetime()&&!review) version=selector.practice(s,category,true);
  if(version==null) throw new ExamException("practice.empty");
  StudentQuestion q=selector.frozen(version);
  long id=store.insert("practice_deliveries",values("user_id",s.id(),"exam_type_id",s.examId(),"question_id",q.id(),"version_id",version,
   "category_filter",category,"created_at",clock.instant()));
  if(old!=null) store.jdbc().update("UPDATE practice_deliveries SET "+(review?"review_delivery_id":"next_delivery_id")+"=? WHERE id=?",id,old.id());
  int changed=store.jdbc().update("UPDATE practice_sessions SET exam_type_id=?,current_delivery_id=? WHERE user_id=?",s.examId(),id,s.id());
  if(changed==0) store.jdbc().update("INSERT INTO practice_sessions(user_id,exam_type_id,current_delivery_id) VALUES (?,?,?)",s.id(),s.examId(),id);
  return view(s,own(s,id));
 }
 public View answer(long sender,long delivery,int option) {
  Student s=access.lock(sender);Delivery d=own(s,delivery);
  StudentQuestion q=selector.frozen(d.versionId());q.option(option);
  if(q.examId()!=s.examId()) throw new ExamException("student.invalid");
  if(d.selected()!=null) return new View(s,d,q);
  boolean used=used(s,q.id());
  if(!used&&!s.lifetime()&&s.practiceRemaining()==0) throw new ExamException("practice.limit");
  Instant now=clock.instant();
  store.jdbc().update("UPDATE practice_deliveries SET selected_option=?,answered_at=? WHERE id=?",option,java.sql.Timestamp.from(now),delivery);
  if(!used) {
   store.jdbc().update("INSERT INTO practice_usage(user_id,exam_type_id,question_id,first_delivery_id,created_at) VALUES (?,?,?,?,?)",
    s.id(),s.examId(),q.id(),delivery,java.sql.Timestamp.from(now));
   if(!s.lifetime()) s.grant().setPracticeUsed(s.grant().getPracticeUsed()+1);
  }
  return view(s,own(s,delivery));
 }
 public View delivery(long sender,long id) { Student s=access.lock(sender);return view(s,own(s,id)); }
 private boolean used(Student s,long question) {
  return store.jdbc().queryForObject("SELECT COUNT(*) FROM practice_usage WHERE user_id=? AND exam_type_id=? AND question_id=?",Long.class,s.id(),s.examId(),question)>0;
 }
 private View view(Student s,Delivery d) {
  var q=selector.frozen(d.versionId());
  if(q.examId()!=s.examId()) throw new ExamException("student.invalid");
  return new View(s,d,q);
 }
 private Delivery own(Student s,long id) {
  var rows=store.jdbc().query("SELECT * FROM practice_deliveries WHERE id=? AND user_id=?",(r,n)->read(r),id,s.id());
  if(rows.isEmpty()) throw new ExamException("student.invalid");return rows.getFirst();
 }
 private Delivery read(java.sql.ResultSet r) throws java.sql.SQLException {
  return new Delivery(r.getLong("id"),r.getLong("user_id"),r.getLong("question_id"),r.getLong("version_id"),
   r.getObject("category_filter",Long.class),r.getObject("next_delivery_id",Long.class),r.getObject("review_delivery_id",Long.class),r.getObject("selected_option",Integer.class));
 }
}
