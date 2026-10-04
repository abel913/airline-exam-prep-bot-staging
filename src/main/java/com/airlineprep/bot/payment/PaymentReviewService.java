package com.airlineprep.bot.payment;
import java.time.Clock;
import com.airlineprep.bot.access.*;
import com.airlineprep.bot.common.*;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.user.BotUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional
public class PaymentReviewService {
 private final SettingsService settings;private final PaymentQueries queries;private final LifetimeGrantService grants;
 private final ExamStore store;private final PaymentAuditService audit;private final PaymentNotificationOutbox outbox;
 private final StudentAccess access;private final BotUserRepository users;private final Clock clock;
 public PaymentReviewService(SettingsService s,PaymentQueries q,LifetimeGrantService g,ExamStore db,PaymentAuditService a,
  PaymentNotificationOutbox o,StudentAccess access,BotUserRepository u,Clock c) {settings=s;queries=q;grants=g;store=db;audit=a;outbox=o;this.access=access;users=u;clock=c;}
 public PaymentRequest approve(long id,String admin) {return review(id,admin,true,null);}
 public PaymentRequest reject(long id,String admin,String reason) {return review(id,admin,false,reason);}
 private PaymentRequest review(long id,String admin,boolean approve,String reason) {
  settings.lock();var p=queries.get(id);var target=approve?PaymentStatus.APPROVED:PaymentStatus.REJECTED;
  if(p.status()==target) return p;
  p.status().require(PaymentStatus.PENDING_REVIEW);
  if(!approve&&(reason==null||reason.isBlank()||reason.length()>500||reason.chars().anyMatch(c->c<32&&c!='\n')))
   throw new ExamException("payment.reasonRequired");
  boolean granted=approve&&grants.grant(p,admin);
  store.jdbc().update("UPDATE payment_requests SET status=?,open_user_id=NULL,reviewed_at=?,reviewed_by=?,rejection_reason=? WHERE id=?",
   target.name(),java.sql.Timestamp.from(clock.instant()),admin,approve?null:reason.strip(),id);
  audit.append("WEB_ADMIN",admin,approve?"PAYMENT_APPROVED":"PAYMENT_REJECTED","PAYMENT",id,approve?(granted?"lifetime=granted":"lifetime=already-active"):"user-visible-reason-recorded");
  var student=access.lock(users.findById(p.userId()).orElseThrow().getTelegramUserId(),p.examTypeId());
  outbox.enqueue(id,approve?"USER_APPROVED":"USER_REJECTED",student);return queries.get(id);
 }
}
