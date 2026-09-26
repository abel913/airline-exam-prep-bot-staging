package com.airlineprep.bot.analytics;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import com.airlineprep.bot.question.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregate product data only. No identity hashes, Telegram IDs or payment evidence. */
@Service @Transactional(readOnly=true)
public class AnalyticsService {
 private final JdbcTemplate jdbc;private final Clock clock;
 public AnalyticsService(JdbcTemplate j,Clock c) { jdbc=j;clock=c; }
 public record Filter(String window,Long exam,Long category,String status,String difficulty,String tag,String text,String sort,Long student) {
  public Filter {
   if(window==null||window.isBlank()) window="30";
   if(!List.of("today","7","30","all").contains(window)) throw new IllegalArgumentException("Choose today, 7 days, 30 days or all time.");
   if(exam!=null&&exam<1||category!=null&&category<1||student!=null&&student<1) throw new IllegalArgumentException("Invalid ID.");
   status=status==null?"":status; difficulty=difficulty==null?"":difficulty;
   if(!status.isBlank()) QuestionStatus.valueOf(status);
   if(!difficulty.isBlank()) Difficulty.valueOf(difficulty);
   tag=QuestionTags.normalize(tag);if(tag.contains(",")) throw new IllegalArgumentException("Filter by one tag.");
   text=text==null?"":text.strip();if(text.length()>200) throw new IllegalArgumentException("Search is limited to 200 characters.");
   sort=sort==null||sort.isBlank()?"id":sort;
   if(!List.of("id","answers","accuracy").contains(sort)) throw new IllegalArgumentException("Invalid sort.");
  }
 }
 public record Report(List<String> headers,List<List<String>> rows,int page,boolean more) {}
 public Timestamp since(String window) {
  var today=LocalDate.now(clock.withZone(ZoneOffset.UTC));
  Instant start=switch(window) {case "today"->today.atStartOfDay(ZoneOffset.UTC).toInstant();case "7","30"->today.minusDays(Long.parseLong(window)-1).atStartOfDay(ZoneOffset.UTC).toInstant();case "all"->Instant.EPOCH;default->throw new IllegalArgumentException("Invalid window.");};
  return Timestamp.from(start);
 }
 private String questionFilter(Filter f,List<Object> args) {
  StringBuilder sql=new StringBuilder(" WHERE 1=1");
  if(f.exam()!=null) {sql.append(" AND v.exam_type_id=?");args.add(f.exam());}
  if(f.category()!=null) {sql.append(" AND v.category_id=?");args.add(f.category());}
  if(!f.status().isBlank()) {sql.append(" AND q.status=?");args.add(f.status());}
  if(!f.difficulty().isBlank()) {sql.append(" AND v.difficulty=?");args.add(f.difficulty());}
  if(!f.tag().isBlank()) {sql.append(" AND CONCAT(',',v.tags,',') LIKE ?");args.add("%,"+f.tag()+",%");}
  if(!f.text().isBlank()) {sql.append(" AND LOWER(v.question_text) LIKE ? ESCAPE '!'");args.add("%"+f.text().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
  return sql.toString();
 }
 public Report report(String type,Filter f,int page,int size) {
  if(page<0||page>100000||size<1||size>500) throw new IllegalArgumentException("Invalid report bounds.");
  List<Object> args=new ArrayList<>();String sql;Timestamp start=since(f.window());
  switch(type) {
   case "questions" -> {
    args.add(start);args.add(start);
    sql="""
     WITH p AS (SELECT d.question_id,COUNT(*) answers,SUM(CASE WHEN o.correct THEN 1 ELSE 0 END) correct
      FROM practice_usage u JOIN practice_deliveries d ON d.id=u.first_delivery_id
      JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option
      WHERE d.answered_at>=? GROUP BY d.question_id),
     m AS (SELECT i.question_id,COUNT(i.selected_option) answers,SUM(CASE WHEN o.correct THEN 1 ELSE 0 END) correct,
      SUM(CASE WHEN i.selected_option IS NULL THEN 1 ELSE 0 END) unanswered
      FROM mock_items i JOIN mock_attempts a ON a.id=i.attempt_id
      LEFT JOIN question_options o ON o.version_id=i.version_id AND o.position=i.selected_option
      WHERE a.status IN ('SUBMITTED','EXPIRED') AND a.submitted_at>=? GROUP BY i.question_id)
     SELECT q.id AS question_id,SUBSTRING(v.question_text,1,160) AS question,v.category_name AS category,v.difficulty,q.status,
      COALESCE(p.answers,0) AS practice_answers,COALESCE(p.correct,0) AS practice_correct,
      COALESCE(p.answers-p.correct,0) AS practice_incorrect,
      COALESCE(m.answers,0) AS mock_answers,COALESCE(m.correct,0) AS mock_correct,
      COALESCE(m.answers-m.correct,0) AS mock_incorrect,COALESCE(m.unanswered,0) AS mock_unanswered,
      COALESCE(p.answers,0)+COALESCE(m.answers,0) AS answers,
      CASE WHEN COALESCE(p.answers,0)+COALESCE(m.answers,0)=0 THEN 0 ELSE
       ROUND(100.0*(COALESCE(p.correct,0)+COALESCE(m.correct,0))/(COALESCE(p.answers,0)+COALESCE(m.answers,0)),2) END AS accuracy,
      CASE WHEN COALESCE(p.answers,0)+COALESCE(m.answers,0)=0 THEN 'Never answered'
       WHEN COALESCE(p.answers,0)+COALESCE(m.answers,0)<10 THEN 'Insufficient data'
       WHEN 100.0*(COALESCE(p.correct,0)+COALESCE(m.correct,0))/(COALESCE(p.answers,0)+COALESCE(m.answers,0))<30 THEN 'High error rate: review clarity'
       WHEN 100.0*(COALESCE(p.correct,0)+COALESCE(m.correct,0))/(COALESCE(p.answers,0)+COALESCE(m.answers,0))>=95 THEN 'Very high accuracy: review distractors'
       ELSE 'No signal' END AS quality_signal
     FROM questions q JOIN question_versions v ON v.id=q.current_version_id
     LEFT JOIN p ON p.question_id=q.id LEFT JOIN m ON m.question_id=q.id
     """+questionFilter(f,args)+" ORDER BY "+switch(f.sort()){case "answers"->"answers DESC,q.id";case "accuracy"->"accuracy,q.id";default->"q.id DESC";};
   }
   case "categories" -> {
    args.add(start);
    sql="""
     SELECT v.category_id,MAX(v.category_name) AS category,COUNT(*) AS first_answers,COUNT(DISTINCT u.user_id) AS students,
      SUM(CASE WHEN o.correct THEN 1 ELSE 0 END) AS correct,
      ROUND(100.0*SUM(CASE WHEN o.correct THEN 1 ELSE 0 END)/COUNT(*),2) AS accuracy,
      CASE WHEN COUNT(*)<5 THEN 'Insufficient data' WHEN 100.0*SUM(CASE WHEN o.correct THEN 1 ELSE 0 END)/COUNT(*)<60 THEN 'Needs more practice' ELSE 'No weak signal' END AS insight
     FROM practice_usage u JOIN practice_deliveries d ON d.id=u.first_delivery_id
     JOIN question_versions v ON v.id=d.version_id JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option
     WHERE d.answered_at>=? GROUP BY v.category_id ORDER BY first_answers DESC,v.category_id
     """;
   }
   case "registrations" -> {
    args.add(start);sql="SELECT e.id AS exam_id,e.name AS exam,u.preferred_language AS language,COUNT(*) AS registrations FROM bot_users u JOIN exam_types e ON e.id=u.selected_exam_type_id WHERE u.registration_completed_at>=? GROUP BY e.id,e.name,u.preferred_language ORDER BY e.id,u.preferred_language";
   }
   case "content" -> sql="""
    SELECT v.exam_type_id,v.exam_name AS exam,v.category_id,v.category_name AS category,v.difficulty,q.status,COUNT(*) AS questions,
     SUM(CASE WHEN v.free_pool THEN 1 ELSE 0 END) AS free_pool,SUM(CASE WHEN v.premium_pool THEN 1 ELSE 0 END) AS premium_pool,
     SUM(CASE WHEN v.mock_pool THEN 1 ELSE 0 END) AS mock_pool
    FROM questions q JOIN question_versions v ON v.id=q.current_version_id GROUP BY v.exam_type_id,v.exam_name,v.category_id,v.category_name,v.difficulty,q.status
    ORDER BY v.exam_type_id,v.exam_name,v.category_id,v.category_name,v.difficulty,q.status
    """;
   case "payments" -> {
    args.add(start);sql="""
     SELECT currency,status,COUNT(*) AS requests,SUM(amount) AS requested_amount,
      SUM(CASE WHEN status='APPROVED' THEN amount ELSE 0 END) AS approved_amount
     FROM payment_requests WHERE created_at>=? GROUP BY currency,status ORDER BY currency,status
     """;
   }
   case "mocks" -> {
    args.add(start);sql="""
     SELECT status,COUNT(*) AS attempts,SUM(CASE WHEN started_at IS NOT NULL THEN 1 ELSE 0 END) AS opened,
      SUM(CASE WHEN first_answer_at IS NOT NULL THEN 1 ELSE 0 END) AS answered,
      ROUND(AVG(100.0*correct_count/question_count),2) AS average_score,
      ROUND(AVG(correct_count*1.0),2) AS average_correct,ROUND(AVG(unanswered_count*1.0),2) AS average_unanswered
     FROM mock_attempts WHERE created_at>=? GROUP BY status ORDER BY status
     """;
   }
   case "students" -> {
    sql="""
     WITH activity AS (SELECT user_id,answered_at AS at FROM practice_deliveries WHERE answered_at IS NOT NULL
      UNION ALL SELECT user_id,COALESCE(submitted_at,first_answer_at,started_at,created_at) AS at FROM mock_attempts),
     last_seen AS (SELECT user_id,MAX(at) AS last_activity FROM activity GROUP BY user_id),
     p AS (SELECT user_id,COUNT(*) AS count FROM practice_usage GROUP BY user_id),
     m AS (SELECT user_id,COUNT(*) AS count FROM mock_attempts WHERE first_answer_at IS NOT NULL GROUP BY user_id)
     SELECT u.id AS student_id,e.name AS exam,u.registration_completed_at AS registered_at,g.access_level,
      COALESCE(p.count,0) AS unique_practice,COALESCE(m.count,0) AS answered_mocks,a.last_activity
     FROM bot_users u JOIN access_entitlements g ON g.user_id=u.id JOIN exam_types e ON e.id=u.selected_exam_type_id
     LEFT JOIN p ON p.user_id=u.id LEFT JOIN m ON m.user_id=u.id LEFT JOIN last_seen a ON a.user_id=u.id WHERE 1=1
     """;
    if(f.student()!=null) {sql+=" AND u.id=?";args.add(f.student());}
    sql+=" ORDER BY u.id DESC";
   }
   case "outbox" -> sql="SELECT id,status,attempts,next_attempt_at,sent_at,CASE WHEN status='FAILED' THEN 'Delivery failed; automatic retry policy applies' ELSE '' END AS safe_summary FROM payment_notifications ORDER BY id DESC";
   default -> throw new IllegalArgumentException("Unknown report.");
  }
  args.add(size+1);args.add((long)page*size);
  return query(sql+" LIMIT ? OFFSET ?",args,page,size);
 }
 private Report query(String sql,List<Object> args,int page,int size) {
  return jdbc.query(sql,rs->{
   List<String> headers=new ArrayList<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++) headers.add(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT));
   List<List<String>> rows=new ArrayList<>();while(rs.next()) {List<String> row=new ArrayList<>();for(int i=1;i<=headers.size();i++) row.add(Objects.toString(rs.getObject(i),""));rows.add(row);}
   return new Report(headers,rows.stream().limit(size).toList(),page,rows.size()>size);
  },args.toArray());
 }
 public Map<String,Object> overview(Filter f) {
  Timestamp start=since(f.window());Map<String,Object> result=new LinkedHashMap<>();
  result.put("Registered students (all time)",count("SELECT COUNT(*) FROM bot_users WHERE registration_status='COMPLETED'"));
  result.put("New registrations",count("SELECT COUNT(*) FROM bot_users WHERE registration_completed_at>=?",start));
  result.put("Active students",count("SELECT COUNT(DISTINCT user_id) FROM (SELECT user_id FROM practice_deliveries WHERE answered_at>=? UNION SELECT user_id FROM mock_attempts WHERE created_at>=? OR started_at>=? OR submitted_at>=? UNION SELECT a.user_id FROM mock_items i JOIN mock_attempts a ON a.id=i.attempt_id WHERE i.answered_at>=?) a",start,start,start,start,start));
  result.put("Submitted practice answers (including repeats)",count("SELECT COUNT(*) FROM practice_deliveries WHERE answered_at>=?",start));
  result.put("Unique students practicing",count("SELECT COUNT(DISTINCT user_id) FROM practice_deliveries WHERE answered_at>=?",start));
  result.put("First-answer practice accuracy %",jdbc.queryForObject("SELECT COALESCE(ROUND(100.0*SUM(CASE WHEN o.correct THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),2),0) FROM practice_usage u JOIN practice_deliveries d ON d.id=u.first_delivery_id JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option WHERE d.answered_at>=?",java.math.BigDecimal.class,start));
  result.put("Mock completion % (created cohort; submitted or expired)",jdbc.queryForObject("SELECT COALESCE(ROUND(100.0*SUM(CASE WHEN status IN ('SUBMITTED','EXPIRED') THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),2),0) FROM mock_attempts WHERE created_at>=?",java.math.BigDecimal.class,start));
  result.put("Payment approval % (decisions in window)",jdbc.queryForObject("SELECT COALESCE(ROUND(100.0*SUM(CASE WHEN status='APPROVED' THEN 1 ELSE 0 END)/NULLIF(COUNT(*),0),2),0) FROM payment_requests WHERE status IN ('APPROVED','REJECTED') AND reviewed_at>=?",java.math.BigDecimal.class,start));
  result.put("Lifetime users (all time)",count("SELECT COUNT(*) FROM access_entitlements WHERE access_level='LIFETIME'"));
  result.put("Lifetime grants in window",count("SELECT COUNT(*) FROM lifetime_access_grants WHERE granted_at>=?",start));
  result.put("Published questions (current)",count("SELECT COUNT(*) FROM questions WHERE status='PUBLISHED'"));
  result.put("Admin actions",count("SELECT COUNT(*) FROM admin_changes WHERE created_at>=?",start));
  result.put("Payment audit actions",count("SELECT COUNT(*) FROM payment_audit_events WHERE created_at>=?",start));
  result.put("Outbox pending (current)",count("SELECT COUNT(*) FROM payment_notifications WHERE status IN ('NEW','SENDING')"));
  result.put("Outbox failed (current)",count("SELECT COUNT(*) FROM payment_notifications WHERE status='FAILED'"));
  return result;
 }
 private long count(String sql,Object... args) {return jdbc.queryForObject(sql,Long.class,args);}
 public Report options(long question,Filter f,int page) {
  if(question<1||page<0||page>100000) throw new IllegalArgumentException("Invalid question/page.");
  return query("""
   SELECT v.version_number,o.position,o.option_text,o.correct,
    (SELECT COUNT(*) FROM practice_usage u JOIN practice_deliveries d ON d.id=u.first_delivery_id
      WHERE d.version_id=v.id AND d.selected_option=o.position AND d.answered_at>=?) AS practice_selections,
    (SELECT COUNT(*) FROM mock_items i JOIN mock_attempts a ON a.id=i.attempt_id
      WHERE i.version_id=v.id AND i.selected_option=o.position AND a.status IN ('SUBMITTED','EXPIRED') AND a.submitted_at>=?) AS mock_selections
   FROM question_versions v JOIN question_options o ON o.version_id=v.id WHERE v.question_id=?
   ORDER BY v.version_number DESC,o.position LIMIT ? OFFSET ?
   """,List.of(since(f.window()),since(f.window()),question,26,page*25),page,25);
 }
 public Map<String,Object> student(long id) {
  var rows=jdbc.queryForList("""
   SELECT u.id,u.preferred_language,u.registration_completed_at,g.access_level,g.practice_limit,g.practice_used,g.mock_limit,g.mocks_used,
    (SELECT COUNT(*) FROM practice_usage p WHERE p.user_id=u.id) AS unique_practice,
    (SELECT COUNT(*) FROM practice_usage p JOIN practice_deliveries d ON d.id=p.first_delivery_id
      JOIN question_options o ON o.version_id=d.version_id AND o.position=d.selected_option WHERE p.user_id=u.id AND o.correct=true) AS correct_first_answers,
    (SELECT COUNT(*) FROM mock_attempts m WHERE m.user_id=u.id AND m.status IN ('SUBMITTED','EXPIRED')) AS completed_mocks,
    (SELECT COUNT(*) FROM payment_requests p WHERE p.user_id=u.id AND p.status='PENDING_REVIEW') AS pending_payments,
    (SELECT COUNT(*) FROM payment_requests p WHERE p.user_id=u.id AND p.status='APPROVED') AS approved_payments
   FROM bot_users u JOIN access_entitlements g ON g.user_id=u.id WHERE u.id=?
   """,id);
  if(rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
  return rows.getFirst();
 }
 public Report studentMocks(long id,int page) {
  if(page<0||page>100000) throw new IllegalArgumentException("Invalid page.");
  student(id);
  return query("SELECT id,status,created_at,question_count,correct_count,incorrect_count,unanswered_count FROM mock_attempts WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",List.of(id,26,page*25),page,25);
 }
}
