package com.airlineprep.bot.question;
import java.util.*;
import com.airlineprep.bot.access.StudentAccess.Student;
import com.airlineprep.bot.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class StudentQuestionSelector {
 private final JdbcTemplate jdbc;
 public StudentQuestionSelector(JdbcTemplate j) { jdbc=j; }
 private static final String ELIGIBLE="""
  FROM questions q JOIN question_versions v ON v.id=q.current_version_id
  JOIN exam_types e ON e.id=v.exam_type_id
  JOIN categories c ON c.id=v.category_id AND c.exam_type_id=e.id
  WHERE q.status='PUBLISHED' AND e.active=true AND c.active=true AND e.id=?
  """;
 public record CategoryChoice(long id,String name,String nameAm) {}
 public List<CategoryChoice> categories(Student s) {
  return categories(s,0);
 }
 public List<CategoryChoice> categories(Student s,int page) {
  if(page<0||page>1000000) throw new ExamException("student.invalid");
  return jdbc.query("SELECT DISTINCT c.id,c.name,c.name_am "+ELIGIBLE+
   " AND (v.free_pool=true OR (?=true AND v.premium_pool=true)) ORDER BY c.id LIMIT 21 OFFSET ?",
   (r,n)->new CategoryChoice(r.getLong(1),r.getString(2),r.getString(3)),s.examId(),s.lifetime(),page*20);
 }
 public Long practice(Student s,Long category,boolean review) {
  String sql="SELECT v.id "+ELIGIBLE+" AND (v.free_pool=true OR (?=true AND v.premium_pool=true))"+
   (category==null?"":" AND c.id=?")+
   (review?" AND EXISTS":" AND NOT EXISTS")+
   " (SELECT 1 FROM practice_usage u WHERE u.user_id=? AND u.exam_type_id=? AND u.question_id=q.id)"+
   " ORDER BY COALESCE((SELECT MAX(d.id) FROM practice_deliveries d WHERE d.user_id=? AND d.exam_type_id=? AND d.question_id=q.id),0),q.id LIMIT 1";
  List<Object> args=new ArrayList<>(List.of(s.examId(),s.lifetime()));
  if(category!=null) args.add(category);args.add(s.id());args.add(s.examId());args.add(s.id());args.add(s.examId());
  var ids=jdbc.query(sql,(r,n)->r.getLong(1),args.toArray());return ids.isEmpty()?null:ids.getFirst();
 }
 // Randomization is encapsulated in this selector; selection stays bounded in the database.
 public record FrozenSelection(long questionId,long versionId) {}
 public List<FrozenSelection> mock(Student s,int count) {
  return jdbc.query("SELECT q.id,v.id "+ELIGIBLE+" AND v.mock_pool=true ORDER BY RANDOM() LIMIT ?",
   (r,n)->new FrozenSelection(r.getLong(1),r.getLong(2)),s.examId(),count);
 }
 public StudentQuestion frozen(long version) {
  var rows=jdbc.query("SELECT * FROM question_versions WHERE id=?",(r,n)->new StudentQuestion(
   r.getLong("question_id"),r.getLong("id"),r.getLong("exam_type_id"),r.getLong("category_id"),
   r.getString("category_name"),r.getString("question_text"),List.of(),r.getString("explanation")),version);
  if(rows.isEmpty()) throw new ExamException("student.invalid");
  var v=rows.getFirst();
  var options=jdbc.query("SELECT position,option_text,correct FROM question_options WHERE version_id=? ORDER BY position",
   (r,n)->new StudentQuestion.Option(r.getInt(1),r.getString(2),r.getBoolean(3)),version);
  return new StudentQuestion(v.id(),v.versionId(),v.examId(),v.categoryId(),v.categoryName(),v.text(),options,v.explanation());
 }
}
