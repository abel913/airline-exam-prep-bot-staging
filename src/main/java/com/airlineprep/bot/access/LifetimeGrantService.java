package com.airlineprep.bot.access;
import java.time.Clock;
import com.airlineprep.bot.common.ExamStore;
import com.airlineprep.bot.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class LifetimeGrantService {
 private final AccessEntitlementRepository grants;private final ExamStore store;private final PaymentAuditService audit;private final Clock clock;
 private final com.airlineprep.bot.settings.SettingsService settings;
 public LifetimeGrantService(AccessEntitlementRepository g,ExamStore s,PaymentAuditService a,Clock c,com.airlineprep.bot.settings.SettingsService settings) {grants=g;store=s;audit=a;clock=c;this.settings=settings;}
 @Transactional(propagation=Propagation.MANDATORY)
 public boolean grant(PaymentRequest request,String admin) {
  settings.lock();
  request.status().require(PaymentStatus.PENDING_REVIEW);
  var entitlement=grants.findByUserIdAndExamTypeId(request.userId(),request.examTypeId()).orElseThrow();
  if("LIFETIME".equals(entitlement.getAccessLevel())) return false;
  store.insert("lifetime_access_grants",ExamStore.values("user_id",request.userId(),"exam_type_id",request.examTypeId(),"payment_request_id",request.id(),"granted_at",clock.instant(),"granted_by",admin));
  entitlement.setAccessLevel("LIFETIME");
  // Registration grant fields, snapshots and usage counters retain their historical values.
  audit.append("WEB_ADMIN",admin,"LIFETIME_ACCESS_GRANTED","PAYMENT",request.id(),"provenance=approved-payment");
  return true;
 }
}
