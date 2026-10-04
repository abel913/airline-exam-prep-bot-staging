package com.airlineprep.bot.mock;
import java.time.*;
import java.util.*;
import com.airlineprep.bot.access.StudentAccess;
import com.airlineprep.bot.access.StudentAccess.Student;
import com.airlineprep.bot.common.*;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.airlineprep.bot.common.ExamStore.values;
@Service @Transactional
public class MockAttemptService {
 private final StudentAccess access;
 private final ExamStore store;
 private final StudentQuestionSelector selector;
 private final SettingsService settings;
 private final MockScoringService scoring;
 private final Clock clock;
 public MockAttemptService(StudentAccess a,ExamStore s,StudentQuestionSelector q,SettingsService settings,MockScoringService score,Clock c) {
  access=a;store=s;selector=q;this.settings=settings;scoring=score;clock=c;
 }
 public record Attempt(long id,long userId,long examId,String status,int count,Integer duration,int cursor,Instant started,
  Instant deadline,Instant firstAnswer,Instant submitted,Integer correct,Integer incorrect,Integer unanswered) {
  public boolean active() { return status.equals("READY")||status.equals("IN_PROGRESS"); }
 }
 public record Item(int sequence,long versionId,Integer selected,int revision) {}
 public record Intro(Student student,Attempt active,Integer duration) {}
 public record View(Student student,Attempt attempt,Item item,StudentQuestion question,MockScoringService.Score score,boolean review,Long secondsRemaining) {}
 public Intro introduction(long sender) {
  Student s=access.lock(sender);Attempt a=active(s);
  if(a!=null) { a=expire(a);if(!a.active()) a=null; }
  return new Intro(s,a,settings.current().getMockDurationMinutes());
 }
 public View prepare(long sender,String creationKey) {
  Student s=access.lock(sender);
  if(creationKey==null||!creationKey.matches("[a-zA-Z0-9-]{1,64}")) throw new ExamException("student.invalid");
  var existing=store.jdbc().query("SELECT * FROM mock_attempts WHERE user_id=? AND exam_type_id=? AND creation_key=?",(r,n)->read(r),s.id(),s.examId(),creationKey);
  if(!existing.isEmpty()) return display(s,expire(existing.getFirst()),null,false);
  Attempt current=active(s);
  if(current!=null) {
   current=expire(current);
   return display(s,current,null,false);
  }
  if(!s.lifetime()&&s.mockRemaining()==0) throw new ExamException("mock.limit");
  int count=s.grant().getQuestionsPerMock();
  var selected=selector.mock(s,count);
  if(selected.size()!=count) throw new ExamException("mock.empty");
  long id=store.insert("mock_attempts",values("user_id",s.id(),"active_user_id",s.id(),"exam_type_id",s.examId(),
   "creation_key",creationKey,"status","READY","question_count",count,"duration_minutes",settings.current().getMockDurationMinutes(),
   "cursor_position",0,"created_at",clock.instant()));
  for(int i=0;i<selected.size();i++) {
   // IDs were selected under the same content lock, so current versions cannot change mid-freeze.
   long version=selected.get(i).versionId();
   long logical=selected.get(i).questionId();
   store.insert("mock_items",values("attempt_id",id,"sequence_number",i,"question_id",logical,"version_id",version,"answer_revision",0));
  }
  return display(s,own(s,id),null,false);
 }
 public View open(long sender,long attempt,Integer position,boolean review) {
  Student s=access.lock(sender);Attempt a=expire(own(s,attempt));
  if(a.status().equals("READY")) {
   Instant now=clock.instant();Instant deadline=a.duration()==null?null:now.plusSeconds(a.duration()*60L);
   store.jdbc().update("UPDATE mock_attempts SET status='IN_PROGRESS',started_at=?,deadline_at=? WHERE id=?",
    java.sql.Timestamp.from(now),deadline==null?null:java.sql.Timestamp.from(deadline),attempt);
   a=own(s,attempt);
  }
  return display(s,a,position,review);
 }
 public View answer(long sender,long attempt,int sequence,int option,int expectedRevision) {
  Student s=access.lock(sender);Attempt a=expire(own(s,attempt));
  if(!a.active()) return display(s,a,null,false);
  if(!a.status().equals("IN_PROGRESS")) throw new ExamException("mock.openFirst");
  Item item=item(a,sequence);selector.frozen(item.versionId()).option(option);
  if(item.revision()!=expectedRevision) return display(s,a,sequence,false);
  if(a.firstAnswer()==null) {
   if(!s.lifetime()&&s.mockRemaining()==0) throw new ExamException("mock.limit");
   if(!s.lifetime()) s.grant().setMocksUsed(s.grant().getMocksUsed()+1);
   store.jdbc().update("UPDATE mock_attempts SET first_answer_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant()),attempt);
  }
  store.jdbc().update("UPDATE mock_items SET selected_option=?,answered_at=?,answer_revision=answer_revision+1 WHERE attempt_id=? AND sequence_number=?",
   option,java.sql.Timestamp.from(clock.instant()),attempt,sequence);
  return display(s,own(s,attempt),sequence,false);
 }
 public View submit(long sender,long attempt) {
  Student s=access.lock(sender);Attempt a=expire(own(s,attempt));
  if(a.active()) {
   if(a.firstAnswer()==null) throw new ExamException("mock.answerFirst");
   finish(a,"SUBMITTED");a=own(s,attempt);
  }
  return display(s,a,null,false);
 }
 private Attempt expire(Attempt a) {
  if(a.active()&&a.deadline()!=null&&!clock.instant().isBefore(a.deadline())) {
   finish(a,"EXPIRED");
   return store.jdbc().queryForObject("SELECT * FROM mock_attempts WHERE id=?",(r,n)->read(r),a.id());
  }
  return a;
 }
 private void finish(Attempt a,String status) {
  var score=scoring.calculate(a.id());
  store.jdbc().update("UPDATE mock_attempts SET status=?,active_user_id=NULL,submitted_at=?,correct_count=?,incorrect_count=?,unanswered_count=? WHERE id=?",
   status,java.sql.Timestamp.from(clock.instant()),score.correct(),score.incorrect(),score.unanswered(),a.id());
 }
 private View display(Student s,Attempt a,Integer position,boolean review) {
  if(!a.active()&&(!review||a.firstAnswer()==null)) {
   var categories=scoring.calculate(a.id()).categories();
   return new View(s,a,null,null,new MockScoringService.Score(a.count(),a.correct(),a.incorrect(),a.unanswered(),categories),false,null);
  }
  int sequence=position==null?resumePosition(a):position;
  Item item=item(a,sequence);
  if(a.active()) store.jdbc().update("UPDATE mock_attempts SET cursor_position=? WHERE id=?",sequence,a.id());
  Long seconds=a.deadline()==null?null:Math.max(0,Duration.between(clock.instant(),a.deadline()).getSeconds());
  return new View(s,a,item,selector.frozen(item.versionId()),null,!a.active()&&review,seconds);
 }
 private int resumePosition(Attempt a) {
  if(!a.active()) return 0;
  var first=store.jdbc().query("SELECT sequence_number FROM mock_items WHERE attempt_id=? AND selected_option IS NULL ORDER BY sequence_number LIMIT 1",
   (r,n)->r.getInt(1),a.id());
  return first.isEmpty()?a.cursor():first.getFirst();
 }
 private Item item(Attempt a,int sequence) {
  var rows=store.jdbc().query("SELECT * FROM mock_items WHERE attempt_id=? AND sequence_number=?",
   (r,n)->new Item(r.getInt("sequence_number"),r.getLong("version_id"),r.getObject("selected_option",Integer.class),r.getInt("answer_revision")),a.id(),sequence);
  if(rows.isEmpty()) throw new ExamException("student.invalid");return rows.getFirst();
 }
 private Attempt active(Student s) {
  var rows=store.jdbc().query("SELECT * FROM mock_attempts WHERE active_user_id=? AND exam_type_id=?",(r,n)->read(r),s.id(),s.examId());
  return rows.isEmpty()?null:rows.getFirst();
 }
 private Attempt own(Student s,long id) {
  var rows=store.jdbc().query("SELECT * FROM mock_attempts WHERE id=? AND user_id=? AND exam_type_id=?",(r,n)->read(r),id,s.id(),s.examId());
  if(rows.isEmpty()) throw new ExamException("student.invalid");return rows.getFirst();
 }
 private Attempt read(java.sql.ResultSet r) throws java.sql.SQLException {
  return new Attempt(r.getLong("id"),r.getLong("user_id"),r.getLong("exam_type_id"),r.getString("status"),r.getInt("question_count"),
   r.getObject("duration_minutes",Integer.class),r.getInt("cursor_position"),ExamStore.instant(r,"started_at"),ExamStore.instant(r,"deadline_at"),
   ExamStore.instant(r,"first_answer_at"),ExamStore.instant(r,"submitted_at"),r.getObject("correct_count",Integer.class),
   r.getObject("incorrect_count",Integer.class),r.getObject("unanswered_count",Integer.class));
 }
}
