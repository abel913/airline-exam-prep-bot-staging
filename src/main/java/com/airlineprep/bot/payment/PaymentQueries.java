package com.airlineprep.bot.payment;
import java.time.*;
import java.util.*;
import com.airlineprep.bot.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class PaymentQueries {
 private final JdbcTemplate jdbc;
 public PaymentQueries(JdbcTemplate j) {jdbc=j;}
 public PaymentRequest get(long id) {
  var rows=jdbc.query("SELECT * FROM payment_requests WHERE id=?",(r,n)->read(r),id);
  if(rows.isEmpty()) throw new ExamException("payment.notFound");return rows.getFirst();
 }
 public PaymentRequest own(long id,long user) {var p=get(id);if(p.userId()!=user) throw new ExamException("payment.notFound");return p;}
 public PaymentRequest open(long user,long exam) {
  var rows=jdbc.query("SELECT * FROM payment_requests WHERE open_user_id=? AND target_exam_type_id=?",(r,n)->read(r),user,exam);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PaymentRequest open(long user) {
  var rows=jdbc.query("SELECT * FROM payment_requests WHERE open_user_id=? ORDER BY id DESC LIMIT 1",(r,n)->read(r),user);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public PaymentRequest created(long user,long exam,String key) {
  var rows=jdbc.query("SELECT * FROM payment_requests WHERE user_id=? AND target_exam_type_id=? AND creation_key=?",(r,n)->read(r),user,exam,key);
  return rows.isEmpty()?null:rows.getFirst();
 }
 public List<PaymentRequest> history(long user,long exam) {
  return jdbc.query("SELECT * FROM payment_requests WHERE user_id=? AND target_exam_type_id=? ORDER BY id DESC LIMIT 5",(r,n)->read(r),user,exam);
 }
 public List<PaymentRequest> list(String status,Long method,LocalDate date,Long user,int page) {
  String sql="SELECT * FROM payment_requests WHERE 1=1";var args=new ArrayList<Object>();
  if(status!=null&&!status.isBlank()) {sql+=" AND status=?";args.add(status);}
  if(method!=null) {sql+=" AND method_id=?";args.add(method);}
  if(user!=null) {sql+=" AND user_id=?";args.add(user);}
  if(date!=null) {sql+=" AND created_at>=? AND created_at<?";args.add(java.sql.Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()));args.add(java.sql.Timestamp.from(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));}
  args.add(Math.min(Math.max(page,0),1000000)*25);
  return jdbc.query(sql+" ORDER BY id DESC LIMIT 25 OFFSET ?",(r,n)->read(r),args.toArray());
 }
 public long duplicateReceipts(PaymentRequest p) {
  if(p.receipt()==null) return 0;
  return jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE receipt_unique_id=? AND id<>?",Long.class,p.receipt().uniqueId(),p.id());
 }
 public Map<String,Long> counts() {
  var result=new LinkedHashMap<String,Long>();
  for(String state:List.of("PENDING_REVIEW","APPROVED","REJECTED")) result.put(state,jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE status=?",Long.class,state));
  result.put("LIFETIME",jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE access_level='LIFETIME'",Long.class));return result;
 }
 private PaymentRequest read(java.sql.ResultSet r) throws java.sql.SQLException {
  ReceiptMetadata receipt=r.getString("receipt_file_id")==null?null:new ReceiptMetadata(r.getString("receipt_file_id"),r.getString("receipt_unique_id"),
   r.getString("receipt_type"),r.getString("receipt_filename"),r.getString("receipt_mime"),r.getLong("receipt_size"));
  return new PaymentRequest(r.getLong("id"),r.getLong("user_id"),r.getLong("target_exam_type_id"),PaymentStatus.valueOf(r.getString("status")),r.getBigDecimal("amount"),r.getString("currency"),
   r.getObject("method_id",Long.class),r.getString("method_type"),r.getString("method_name"),r.getString("account_name"),r.getString("destination"),
   r.getString("instructions"),r.getString("reference"),r.getString("normalized_reference"),r.getString("payment_proof_text"),receipt,ExamStore.instant(r,"created_at"),
   ExamStore.instant(r,"submitted_at"),ExamStore.instant(r,"reviewed_at"),r.getString("reviewed_by"),r.getString("rejection_reason"));
 }
}
