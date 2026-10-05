package com.airlineprep.bot.payment;
import java.time.Clock;
import java.util.*;
import com.airlineprep.bot.access.StudentAccess;
import com.airlineprep.bot.access.StudentAccess.Student;
import com.airlineprep.bot.common.*;
import com.airlineprep.bot.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional
public class ManualPaymentService implements PaymentService {
 private final StudentAccess access;private final SettingsService settings;private final ExamStore store;
 private final PaymentQueries queries;private final PaymentMethodService methods;private final PaymentAuditService audit;
 private final PaymentNotificationOutbox outbox;private final Clock clock;
 public ManualPaymentService(StudentAccess a,SettingsService s,ExamStore db,PaymentQueries q,PaymentMethodService m,PaymentAuditService log,PaymentNotificationOutbox o,Clock c) {
  access=a;settings=s;store=db;queries=q;methods=m;audit=log;outbox=o;clock=c;
 }
 public View status(long sender) {
  var current=access.lock(sender);var open=queries.open(current.id());
  var s=open==null?current:access.lock(sender,open.examTypeId());
  return view(s,open,0);
 }
 public View start(long sender,long targetExamId,String key) {
  var s=access.lock(sender,targetExamId);
  if(s.lifetime()) throw new ExamException("payment.lifetime");
  if(key==null||!key.matches("[a-z0-9-]{1,64}")) throw new ExamException("student.invalid");
  var old=queries.created(s.id(),targetExamId,key);if(old!=null) return view(s,old,0);
  var open=queries.open(s.id());if(open!=null) {
   if(open.examTypeId()!=targetExamId) throw new ExamException("payment.state");
   return view(s,open,0);
  }
  requireEnabled();var cfg=settings.current();
  long id=store.insert("payment_requests",ExamStore.values("user_id",s.id(),"target_exam_type_id",targetExamId,"open_user_id",s.id(),"creation_key",key,
   "status","SELECT_METHOD","amount",cfg.getLifetimePrice(),"currency",cfg.getCurrency(),"created_at",clock.instant()));
  log(s,id,"PAYMENT_REQUEST_CREATED");return view(s,queries.get(id),0);
 }
 public View methods(long sender,long request,int page) {
  var current=access.lock(sender);var p=queries.own(request,current.id());var s=access.lock(sender,p.examTypeId());p.status().require(PaymentStatus.SELECT_METHOD);
  if(page<0||page>1000000) throw new ExamException("student.invalid");return view(s,p,page);
 }
 public View select(long sender,long request,long method) {
  var current=access.lock(sender);var p=queries.own(request,current.id());var s=access.lock(sender,p.examTypeId());
  if(Objects.equals(p.methodId(),method)) return view(s,p,0);
  p.status().require(PaymentStatus.SELECT_METHOD);requireEnabled();
  var m=methods.get(method).details();if(!m.active()) throw new ExamException("payment.methodInvalid");
  store.jdbc().update("UPDATE payment_requests SET status='AWAITING_REFERENCE',method_id=?,method_type=?,method_name=?,account_name=?,destination=?,instructions=? WHERE id=?",
   method,m.type(),m.displayName(),m.accountName(),m.destination(),m.instructions(),request);
  log(s,request,"PAYMENT_METHOD_SELECTED");return view(s,queries.get(request),0);
 }
 public static String normalizeReference(String input) {
  if(input==null) throw new ExamException("payment.referenceInvalid");
  String original=input.strip();
  if(!original.matches("[A-Za-z0-9][A-Za-z0-9._/-]{2,99}")) throw new ExamException("payment.referenceInvalid");
  return original.toUpperCase(Locale.ROOT);
 }
 public View reference(long sender,long request,String input) {
  var current=access.lock(sender);var p=queries.own(request,current.id());var s=access.lock(sender,p.examTypeId());String normalized=normalizeReference(input);
  if(normalized.equals(p.normalizedReference())) return view(s,p,0);
  p.status().require(PaymentStatus.AWAITING_REFERENCE);
  if(store.jdbc().queryForObject("SELECT COUNT(*) FROM payment_requests WHERE normalized_reference=?",Long.class,normalized)>0)
   throw new ExamException("payment.duplicateReference");
  store.jdbc().update("UPDATE payment_requests SET reference=?,normalized_reference=?,status='AWAITING_RECEIPT' WHERE id=?",input.strip(),normalized,request);
  log(s,request,"PAYMENT_REFERENCE_SUBMITTED");return view(s,queries.get(request),0);
 }
 public View submitProof(long sender,long request,String input) {
  var current=access.lock(sender);var p=queries.own(request,current.id());var s=access.lock(sender,p.examTypeId());
  String proof=normalizeProof(input);
  if(p.status()==PaymentStatus.PENDING_REVIEW&&proof.equals(p.paymentProofText())) return view(s,p,0);
  if(p.status()!=PaymentStatus.AWAITING_REFERENCE&&p.status()!=PaymentStatus.AWAITING_RECEIPT)
   throw new ExamException("payment.state");
  String reference=null,normalized=null;
  try {normalized=normalizeReference(proof);reference=proof;}
  catch(ExamException ignored) { /* Full provider messages are stored as proof without parsing. */ }
  if(normalized!=null&&store.jdbc().queryForObject("SELECT COUNT(*) FROM payment_requests WHERE normalized_reference=? AND id<>?",Long.class,normalized,request)>0)
   throw new ExamException("payment.duplicateReference");
  store.jdbc().update("UPDATE payment_requests SET payment_proof_text=?,reference=COALESCE(?,reference),normalized_reference=COALESCE(?,normalized_reference),status='PENDING_REVIEW',submitted_at=? WHERE id=?",
   proof,reference,normalized,java.sql.Timestamp.from(clock.instant()),request);
  log(s,request,"PAYMENT_PROOF_SUBMITTED");log(s,request,"PAYMENT_SUBMITTED_FOR_REVIEW");
  outbox.enqueue(request,"ADMIN_PENDING",s);return view(s,queries.get(request),0);
 }
 public static String normalizeProof(String input) {
  if(input==null) throw new ExamException("payment.proofInvalid");
  String proof=input.strip();
  if(proof.isEmpty()||proof.length()>4096||proof.indexOf('\0')>=0) throw new ExamException("payment.proofInvalid");
  return proof;
 }
 public View receipt(long sender,long request,ReceiptMetadata receipt) {
  var current=access.lock(sender);var p=queries.own(request,current.id());var s=access.lock(sender,p.examTypeId());
  if(receipt==null) throw new ExamException("payment.receiptInvalid");
  if(receipt.equals(p.receipt())) return view(s,p,0);
  p.status().require(PaymentStatus.AWAITING_RECEIPT);
  store.jdbc().update("UPDATE payment_requests SET receipt_file_id=?,receipt_unique_id=?,receipt_type=?,receipt_filename=?,receipt_mime=?,receipt_size=?,status='PENDING_REVIEW',submitted_at=? WHERE id=?",
   receipt.fileId(),receipt.uniqueId(),receipt.type(),receipt.filename(),receipt.mime(),receipt.size(),java.sql.Timestamp.from(clock.instant()),request);
  log(s,request,"PAYMENT_RECEIPT_SUBMITTED");log(s,request,"PAYMENT_SUBMITTED_FOR_REVIEW");
  outbox.enqueue(request,"ADMIN_PENDING",s);return view(s,queries.get(request),0);
 }
 public View cancel(long sender,long request) {
  var current=access.lock(sender);var p=queries.own(request,current.id());var s=access.lock(sender,p.examTypeId());
  if(p.status()==PaymentStatus.CANCELLED) return view(s,p,0);
  if(!p.status().cancellable()) throw new ExamException("payment.state");
  store.jdbc().update("UPDATE payment_requests SET status='CANCELLED',open_user_id=NULL WHERE id=?",request);
  log(s,request,"PAYMENT_CANCELLED");return view(s,queries.get(request),0);
 }
 private void requireEnabled() {if(!enabled()) throw new ExamException("payment.disabled");}
 private boolean enabled() {var c=settings.current();return c.getPaymentEnabled()&&c.getManualPaymentEnabled();}
 private View view(Student s,PaymentRequest p,int page) {
  var cfg=settings.current();
  return new View(s,p,p!=null&&p.status()==PaymentStatus.SELECT_METHOD&&enabled()?methods.list(true,page):List.of(),
   enabled(),cfg.getLifetimePrice(),cfg.getCurrency(),cfg.getSupportInfo(),page,queries.history(s.id(),s.examId()));
 }
 private void log(Student s,long id,String action) {audit.append("USER",Long.toString(s.id()),action,"PAYMENT",id,"");}
}
