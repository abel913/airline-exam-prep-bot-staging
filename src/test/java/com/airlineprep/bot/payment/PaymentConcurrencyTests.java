package com.airlineprep.bot.payment;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentConcurrencyTests extends PaymentFixture {
 @Autowired PlatformTransactionManager manager;
 @BeforeEach void setup() {setupPayment();}
 <T> List<T> race(Supplier<T> a,Supplier<T> b) throws Exception {
  var gate=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)) {
   var first=executor.submit(()->{gate.await();return a.get();});var second=executor.submit(()->{gate.await();return b.get();});gate.countDown();
   return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
  }
 }
 String outcome(Runnable action) {try {action.run();return "OK";} catch(com.airlineprep.bot.common.ExamException e) {return e.key();}}
 @Test void simultaneousCreationHasOneOpenRequest() throws Exception {
  var ids=race(()->payments.start(sender,exam,"a").request().id(),()->payments.start(sender,exam,"b").request().id());
  assertThat(ids.getFirst()).isEqualTo(ids.getLast());
 }
 @Test void duplicateReferencesAcrossUsersHaveOneWinner() throws Exception {
  long firstSender=sender,first=selected();prepareFixture(2);long second=selected();
  var results=race(()->outcome(()->payments.reference(firstSender,first,"DEVTEST-RACE")),()->outcome(()->payments.reference(sender,second,"devtest-race")));
  assertThat(results).containsExactlyInAnyOrder("OK","payment.duplicateReference");
 }
 @Test void concurrentApprovalsGrantOnceAndAppendOnce() throws Exception {
  long id=pending();race(()->review.approve(id,"admin-a"),()->review.approve(id,"admin-b"));
  assertThat(grant().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_id=? AND action='PAYMENT_APPROVED'",Integer.class,id)).isEqualTo(1);
 }
 @Test void approveAgainstRejectHasOneTerminalOutcome() throws Exception {
  long id=pending();var result=race(()->outcome(()->review.approve(id,"admin-a")),()->outcome(()->review.reject(id,"admin-b","No match")));
  assertThat(result).containsExactlyInAnyOrder("OK","payment.state");
  assertThat(grant().getAccessLevel()).isEqualTo(queries.get(id).status()==PaymentStatus.APPROVED?"LIFETIME":"FREE");
 }
 @Test void receiptAgainstCancelHasOneCoherentOutcome() throws Exception {
  long id=selected();payments.reference(sender,id,"DEVTEST-CANCEL-RACE");
  var result=race(()->outcome(()->payments.receipt(sender,id,receipt("race"))),()->outcome(()->payments.cancel(sender,id)));
  assertThat(result).containsExactlyInAnyOrder("OK","payment.state");
  var p=queries.get(id);assertThat(p.status()).isIn(PaymentStatus.CANCELLED,PaymentStatus.PENDING_REVIEW);
  assertThat(p.receipt()!=null).isEqualTo(p.status()==PaymentStatus.PENDING_REVIEW);
 }
 @Test void rollbackRestoresDecisionGrantAuditAndOutboxTogether() {
  long id=pending();int before=jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_id=? AND entity_type='PAYMENT'",Integer.class,id);
  new TransactionTemplate(manager).executeWithoutResult(status->{review.approve(id,"test-admin");status.setRollbackOnly();});
  assertThat(grant().getAccessLevel()).isEqualTo("FREE");assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,id)).isZero();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_id=? AND entity_type='PAYMENT'",Integer.class,id)).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE request_id=? AND kind='USER_APPROVED'",Integer.class,id)).isZero();
 }
}
