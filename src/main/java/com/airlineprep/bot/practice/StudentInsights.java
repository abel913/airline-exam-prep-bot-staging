package com.airlineprep.bot.practice;
import java.util.*;
import com.airlineprep.bot.access.StudentAccess;
import com.airlineprep.bot.common.ExamException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class StudentInsights {
 private final StudentAccess access; private final JdbcTemplate jdbc;
 public StudentInsights(StudentAccess a,JdbcTemplate j) { access=a;jdbc=j; }
 public record History(long id,String date,String category,String status,Boolean correct,Integer total,Integer right,Integer wrong,Integer unanswered,Integer duration) {}
 public record Insight(long id,String name,long answered,long correct) {
  public double accuracy() { return answered==0?0:Math.round(10000.0*correct/answered)/100.0; }
  public String key() { return answered<5?"insights.more":accuracy()<60?"insights.weak":accuracy()>=80?"insights.strong":"insights.steady"; }
 }
 public record Page<T>(List<T> rows,int number,boolean more) {}
 private int offset(int page) { if(page<0||page>100000) throw new ExamException("student.invalid");return page*5; }
 private <T> Page<T> page(List<T> rows,int page) { return new Page<>(rows.stream().limit(5).toList(),page,rows.size()>5); }
 public Page<History> history(long sender,boolean mock,int page) {
  var s=access.lock(sender);int offset=offset(page);
  if(mock) return page(jdbc.query("""
   SELECT id,created_at,status,question_count,correct_count,incorrect_count,unanswered_count,duration_minutes
   FROM mock_attempts WHERE user_id=? AND exam_type_id=? ORDER BY id DESC LIMIT 6 OFFSET ?
   """,(r,n)->new History(r.getLong(1),r.getTimestamp(2).toInstant().toString(),"",r.getString(3),null,r.getInt(4),
    r.getObject(5,Integer.class),r.getObject(6,Integer.class),r.getObject(7,Integer.class),r.getObject(8,Integer.class)),s.id(),s.examId(),offset),page);
  return page(jdbc.query("""
   SELECT d.id,d.answered_at,v.category_name,o.correct
   FROM practice_deliveries d JOIN question_versions v ON v.id=d.version_id
   JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option
   WHERE d.user_id=? AND v.exam_type_id=? ORDER BY d.id DESC LIMIT 6 OFFSET ?
   """,(r,n)->new History(r.getLong(1),r.getTimestamp(2).toInstant().toString(),r.getString(3),"",r.getBoolean(4),null,null,null,null,null),s.id(),s.examId(),offset),page);
 }
 public Page<Insight> insights(long sender,int page) {
  var s=access.lock(sender);
  return page(jdbc.query("""
   SELECT v.category_id,MAX(v.category_name),COUNT(*),SUM(CASE WHEN o.correct THEN 1 ELSE 0 END)
   FROM practice_usage u JOIN practice_deliveries d ON d.id=u.first_delivery_id
   JOIN question_versions v ON v.id=d.version_id
   JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option
   WHERE u.user_id=? AND v.exam_type_id=? GROUP BY v.category_id ORDER BY v.category_id LIMIT 6 OFFSET ?
   """,(r,n)->new Insight(r.getLong(1),r.getString(2),r.getLong(3),r.getLong(4)),s.id(),s.examId(),offset(page)),page);
 }
 // Eligible unanswered questions only. Least-recently delivered category provides variety.
 public Long recommendation(long sender) {
  var s=access.lock(sender);
  var rows=jdbc.query("""
   SELECT v.category_id
   FROM practice_usage u JOIN practice_deliveries d ON d.id=u.first_delivery_id
   JOIN question_versions v ON v.id=d.version_id
   JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option
   WHERE u.user_id=? AND v.exam_type_id=? AND EXISTS (
    SELECT 1 FROM questions q JOIN question_versions cv ON cv.id=q.current_version_id
    JOIN categories c ON c.id=cv.category_id JOIN exam_types e ON e.id=cv.exam_type_id
    WHERE q.status='PUBLISHED' AND c.active=true AND e.active=true AND cv.category_id=v.category_id
     AND cv.exam_type_id=? AND (cv.free_pool=true OR (?=true AND cv.premium_pool=true))
     AND NOT EXISTS (SELECT 1 FROM practice_usage used WHERE used.user_id=? AND used.exam_type_id=? AND used.question_id=q.id))
   GROUP BY v.category_id HAVING COUNT(*)>=5 AND SUM(CASE WHEN o.correct THEN 1 ELSE 0 END)*100.0/COUNT(*)<60
   ORDER BY COALESCE((SELECT MAX(recent.id) FROM practice_deliveries recent JOIN question_versions rv ON rv.id=recent.version_id
    WHERE recent.user_id=? AND recent.exam_type_id=? AND rv.category_id=v.category_id),0),v.category_id LIMIT 1
   """,(r,n)->r.getLong(1),s.id(),s.examId(),s.examId(),s.lifetime(),s.id(),s.examId(),s.id(),s.examId());
  return rows.isEmpty()?null:rows.getFirst();
 }
 @Transactional(readOnly=true)
 public String maintenanceLanguage(long sender) {
  var rows=jdbc.query("SELECT COALESCE(u.preferred_language,'en') FROM app_settings s LEFT JOIN bot_users u ON u.telegram_user_id=? WHERE s.id=1 AND s.maintenance_enabled=true",(r,n)->r.getString(1),sender);
  return rows.isEmpty()?null:rows.getFirst();
 }
}
