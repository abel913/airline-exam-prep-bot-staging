package com.airlineprep.bot.payment;
import java.math.BigDecimal;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.admin.CatalogForm;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @Transactional
class PaymentServiceTests extends PaymentFixture {
 @Autowired PracticeService practice;@Autowired MockAttemptService mocks;@Autowired StudentProgressService progress;
 @BeforeEach void setup() {setupPayment();}
 @Test void disabledFlagsAndLifetimeBlockNewRequests() {
  configure(false,true,new BigDecimal("50"));assertThatThrownBy(()->payments.start(sender,exam,"disabled")).hasMessage("payment.disabled");
  configure(true,false,new BigDecimal("50"));assertThatThrownBy(()->payments.start(sender,exam,"manual")).hasMessage("payment.disabled");
  grant().setAccessLevel("LIFETIME");assertThatThrownBy(()->payments.start(sender,exam,"paid")).hasMessage("payment.lifetime");
 }
 @Test void priceAndCurrencySnapshotDoNotFollowSettings() {
  long id=payments.start(sender,exam,"first").request().id();configure(true,true,new BigDecimal("70"));
  assertThat(queries.get(id).amount()).isEqualByComparingTo("50");assertThat(queries.get(id).currency()).isEqualTo("ETB");
  prepareFixture(2);assertThat(payments.start(sender,exam,"second").request().amount()).isEqualByComparingTo("70");
 }
 @Test void methodSnapshotSurvivesEditAndDeactivation() {
  long id=selected();var old=methods.get(method).details();
  methods.save(method,new PaymentMethodService.Form("BANK_TRANSFER","Changed","Changed","NEW","NEW",false,0,old.revision()),"test-admin");
  assertThat(queries.get(id).destination()).isEqualTo("DEVELOPMENT-ONLY");assertThat(queries.get(id).methodType()).isEqualTo("TELEBIRR");
  assertThat(methods.list(true,0)).isEmpty();
  payments.reference(sender,id,"DEVTEST-OLD");payments.receipt(sender,id,receipt("old"));
  assertThat(review.approve(id,"test-admin").status()).isEqualTo(PaymentStatus.APPROVED);
 }
 @Test void invalidInactiveMethodsAndStaleEditsRejected() {
  long inactive=methods.save(null,methodForm("BANK_TRANSFER",false),"test-admin");
  long id=payments.start(sender,exam,"methods").request().id();
  assertThat(payments.status(sender).methods()).extracting(PaymentMethodService.Method::id).containsExactly(method);
  assertThatThrownBy(()->payments.select(sender,id,inactive)).hasMessage("payment.methodInvalid");
  assertThatThrownBy(()->payments.select(sender,id,999999)).hasMessage("payment.methodInvalid");
  assertThatThrownBy(()->methods.save(method,methodForm("TELEBIRR",true),"test-admin")).hasMessage("payment.stale");
 }
 @Test void oneOpenRequestResumeAndRetryKeepStableEvidenceAndAudit() {
  long id=selected();assertThat(payments.start(sender,exam,"another").request().id()).isEqualTo(id);
  payments.select(sender,id,method);payments.reference(sender,id,"  DEVTEST-ab.c/1  ");payments.reference(sender,id,"devtest-AB.C/1");
  assertThat(payments.status(sender).request().status()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);
  var receipt=receipt("stable");payments.receipt(sender,id,receipt);payments.receipt(sender,id,receipt);
  assertThat(queries.get(id).reference()).isEqualTo("DEVTEST-ab.c/1");
  assertThat(queries.get(id).normalizedReference()).isEqualTo("DEVTEST-AB.C/1");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_id=? AND entity_type='PAYMENT' AND action='PAYMENT_REFERENCE_SUBMITTED'",Integer.class,id)).isEqualTo(1);
  assertThatThrownBy(()->payments.receipt(sender,id,receipt("different"))).hasMessage("payment.state");
  assertThatThrownBy(()->payments.cancel(sender,id)).hasMessage("payment.state");
 }
 @Test void cancelledAndRejectedReferencesRemainReserved() {
  long id=selected();payments.reference(sender,id,"Reserved-001");payments.cancel(sender,id);payments.cancel(sender,id);
  long second=selected();assertThatThrownBy(()->payments.reference(sender,second,"reserved-001")).hasMessage("payment.duplicateReference");
  payments.reference(sender,second,"Rejected-001");payments.receipt(sender,second,receipt("rejected"));review.reject(second,"test-admin","Unable to verify.");
  long third=selected();assertThatThrownBy(()->payments.reference(sender,third,"rejected-001")).hasMessage("payment.duplicateReference");
  assertThat(grant().getAccessLevel()).isEqualTo("FREE");
 }
 @Test void disabledPaymentsStillAcceptEvidenceForSelectedRequestsAndAllowReview() {
  long id=selected();configure(false,false,new BigDecimal("70"));
  payments.reference(sender,id,"DEVTEST-disabled");payments.receipt(sender,id,receipt("disabled"));review.approve(id,"test-admin");
  assertThat(grant().getAccessLevel()).isEqualTo("LIFETIME");
 }
 @Test void ownershipAndUnregisteredAccessNeverLeak() {
  long id=selected();prepareFixture(2);
  assertThatThrownBy(()->payments.select(sender,id,method)).hasMessage("payment.notFound");
  assertThatThrownBy(()->payments.reference(sender,id,"DEVTEST-forged")).hasMessage("payment.notFound");
  assertThatThrownBy(()->payments.receipt(sender,id,receipt("forged"))).hasMessage("payment.notFound");
  assertThatThrownBy(()->payments.cancel(sender,id)).hasMessage("payment.notFound");
  assertThatThrownBy(()->payments.status(Long.MAX_VALUE)).hasMessage("student.register");
 }
 @Test void approvalPreservesHistoryAndEnablesUnlimitedPremiumPracticeAndMocks() {
  content(2);var p=practice.next(sender,null,null,false);practice.answer(sender,p.delivery().id(),0);
  var m=mocks.prepare(sender,"before");mocks.open(sender,m.attempt().id(),0,false);mocks.answer(sender,m.attempt().id(),0,0,0);
  int practiceUsed=grant().getPracticeUsed(),mockUsed=grant().getMocksUsed();long id=pending();
  review.approve(id,"test-admin");review.approve(id,"test-admin");
  assertThat(grant().getPracticeUsed()).isEqualTo(practiceUsed);assertThat(grant().getMocksUsed()).isEqualTo(mockUsed);
  assertThat(progress.get(sender).answered()).isEqualTo(1);assertThat(mocks.submit(sender,m.attempt().id()).score().correct()).isEqualTo(1);
  grant().setPracticeLimit(0);grant().setMockLimit(0);
  question("Premium fictional",false,true,false);
  var next=practice.next(sender,null,p.delivery().id(),false);practice.answer(sender,next.delivery().id(),0);
  var premium=practice.next(sender,null,next.delivery().id(),false);assertThat(premium.question().text()).isEqualTo("Premium fictional");
  for(int i=0;i<3;i++) {var a=mocks.prepare(sender,"lifetime"+i);mocks.open(sender,a.attempt().id(),0,false);mocks.answer(sender,a.attempt().id(),0,0,0);mocks.submit(sender,a.attempt().id());}
  assertThat(grant().getMocksUsed()).isEqualTo(mockUsed);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,id)).isEqualTo(1);
 }
 @Test void reviewTransitionsAreTerminalAndReasonsRequired() {
  long id=pending();assertThatThrownBy(()->review.reject(id,"test-admin"," ")).hasMessage("payment.reasonRequired");
  review.reject(id,"test-admin","No matching transaction.");review.reject(id,"test-admin","Retry");
  assertThat(queries.get(id).rejectionReason()).isEqualTo("No matching transaction.");
  assertThatThrownBy(()->review.approve(id,"test-admin")).hasMessage("payment.state");
  long second=pending();review.approve(second,"test-admin");assertThatThrownBy(()->review.reject(second,"test-admin","bad")).hasMessage("payment.state");
 }
 @Test void alreadyLifetimeReviewDoesNotCreateDuplicateGrant() {
  long id=pending();grant().setAccessLevel("LIFETIME");review.approve(id,"test-admin");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,id)).isZero();
  assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.APPROVED);
 }
 @Test void duplicateReceiptIsReviewSignalNotAutomaticRejection() {
  long id=pending();String unique=queries.get(id).receipt().uniqueId();review.reject(id,"test-admin","Test rejection");
  long second=selected();payments.reference(sender,second,"DEVTEST-DUP-RECEIPT");payments.receipt(sender,second,receipt(unique));
  assertThat(queries.duplicateReceipts(queries.get(second))).isEqualTo(1);
  assertThat(queries.get(second).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
 }

 @Test void explicitPurchaseTargetIsIndependentFromCurrentExamAndIdempotencyScope() {
  long examA=exam;long userId=users.findByTelegramUserId(sender).orElseThrow().getId();
  long examB=catalog.save(false,null,new CatalogForm("payment-target-"+sender,"Synthetic second exam","",true,1,null),"test-admin");
  registration.exam(sender,examB);registration.exam(sender,examA);
  assertSelected(examA);

  long requestA=submitForExam(examA,"same-key","TARGET-A-"+sender);
  assertThat(payments.start(sender,examA,"same-key").request().id()).isEqualTo(requestA);
  review.approve(requestA,"test-admin");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow().getAccessLevel()).isEqualTo("FREE");
  assertSelected(examA);

  long requestB=submitForExam(examB,"same-key","TARGET-B-"+sender);
  assertThat(requestB).isNotEqualTo(requestA);assertThat(queries.get(requestB).examTypeId()).isEqualTo(examB);
  review.approve(requestB,"test-admin");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=? AND exam_type_id IN (?,?)",Integer.class,userId,examA,examB)).isEqualTo(2);
  assertSelected(examA);
 }

 @Test void explicitTargetLifetimeAndInactiveValidationDoNotUseCurrentExamTier() {
  long examA=exam;long userId=users.findByTelegramUserId(sender).orElseThrow().getId();
  long examB=catalog.save(false,null,new CatalogForm("payment-validation-"+sender,"Synthetic validation exam","",true,1,null),"test-admin");
  registration.exam(sender,examB);registration.exam(sender,examA);
  var a=grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow();
  var b=grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow();
  b.setAccessLevel("LIFETIME");
  assertThatThrownBy(()->payments.start(sender,examB,"target-paid")).hasMessage("payment.lifetime");
  b.setAccessLevel("FREE");a.setAccessLevel("LIFETIME");
  assertThat(payments.start(sender,examB,"target-free").request().examTypeId()).isEqualTo(examB);
  assertSelected(examA);
  assertThatThrownBy(()->payments.start(sender,Long.MAX_VALUE,"target-missing")).hasMessage("registration.examUnavailable");
 }

 @Test void inactivePurchaseTargetIsRejectedWithoutCreatingRequestOrGrant() {
  long examA=exam;long userId=users.findByTelegramUserId(sender).orElseThrow().getId();
  long examB=catalog.save(false,null,new CatalogForm("payment-inactive-"+sender,"Synthetic inactive exam","",true,1,null),"test-admin");
  registration.exam(sender,examB);registration.exam(sender,examA);
  catalog.save(false,examB,new CatalogForm("payment-inactive-"+sender,"Synthetic inactive exam","",false,1,null),"test-admin");
  int before=jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE user_id=?",Integer.class,userId);
  assertThatThrownBy(()->payments.start(sender,examB,"inactive-target")).hasMessage("registration.examUnavailable");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE user_id=?",Integer.class,userId)).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE user_id=? AND exam_type_id=?",Integer.class,userId,examB)).isZero();
  assertSelected(examA);
 }

 @Test void rejectionOfExplicitOtherExamTargetDoesNotGrantOrChangeSelection() {
  long examA=exam;long userId=users.findByTelegramUserId(sender).orElseThrow().getId();
  long examB=catalog.save(false,null,new CatalogForm("payment-reject-"+sender,"Synthetic rejection exam","",true,1,null),"test-admin");
  registration.exam(sender,examB);registration.exam(sender,examA);
  long request=submitForExam(examB,"reject-target","TARGET-REJECT-"+sender);
  assertThat(queries.get(request).examTypeId()).isEqualTo(examB);
  review.reject(request,"test-admin","Synthetic rejection");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow().getAccessLevel()).isEqualTo("FREE");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow().getAccessLevel()).isEqualTo("FREE");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,request)).isZero();
  assertSelected(examA);
 }

 private long submitForExam(long target,String key,String reference) {
  long id=payments.start(sender,target,key).request().id();payments.select(sender,id,method);
  payments.reference(sender,id,reference);payments.receipt(sender,id,receipt("target_"+id));return id;
 }
 private void assertSelected(long expected) {
  assertThat(users.findByTelegramUserId(sender).orElseThrow().getSelectedExamTypeId()).isEqualTo(expected);
 }
}
