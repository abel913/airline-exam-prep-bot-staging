package com.airlineprep.bot.payment;
import java.math.BigDecimal;
import java.util.UUID;
import com.airlineprep.bot.admin.*;
import com.airlineprep.bot.user.*;
import com.airlineprep.bot.settings.*;
import com.airlineprep.bot.access.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
/** Explicit development PostgreSQL verification. Scenario writes are rolled back. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password=",
  "registration.phone-hmac-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ="})
@Transactional
class PaymentPostgresIT {
 private static final java.util.Set<String> TEST_DATABASES=java.util.Set.of("airline_exam_bot_phase5_test","airline_exam_bot_phase5_v20_fresh_test","airline_exam_bot_phase5_v20_final_test","airline_exam_bot_phase5_v21_test");
 @DynamicPropertySource
 static void isolatedLocalPostgres(DynamicPropertyRegistry registry) {
  String host=System.getenv("PHASE4_PG_HOST"), port=System.getenv("PHASE4_PG_PORT"), database=System.getenv("PHASE4_PG_DATABASE");
  String username=System.getenv("PHASE4_PG_TEST_USER"), password=System.getenv("PHASE4_PG_TEST_PASSWORD");
  if(!"127.0.0.1".equals(host)||!TEST_DATABASES.contains(database)||!"5432".equals(port)||username==null||password==null)
   throw new IllegalStateException("Payment PostgreSQL integration tests require the dedicated local Phase 5 test database only.");
  String url="jdbc:postgresql://127.0.0.1:5432/"+database;
  registry.add("spring.datasource.url",()->url); registry.add("spring.datasource.username",()->username);
  registry.add("spring.datasource.password",()->password); registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  registry.add("spring.flyway.url",()->url); registry.add("spring.flyway.user",()->username);
  registry.add("spring.flyway.password",()->password); registry.add("spring.flyway.driver-class-name",()->"org.postgresql.Driver");
 }
 @Autowired CatalogService catalog;@Autowired RegistrationService registration;@Autowired SettingsService settings;
 @Autowired BotUserRepository users;@Autowired AccessEntitlementRepository grants;
 @Autowired PaymentService payments;@Autowired PaymentMethodService methods;@Autowired PaymentReviewService review;@Autowired PaymentQueries queries;@Autowired JdbcTemplate jdbc;
 @Test void realPostgresSnapshotsReferencesRejectionApprovalAndProvenance() {
  assertThat(jdbc.queryForObject("SELECT version()",String.class)).contains("PostgreSQL");
  String suffix=UUID.randomUUID().toString();long sender=8000000000000L+Math.floorMod(suffix.hashCode(),1000000000);
  long exam=catalog.save(false,null,new CatalogForm("pg-"+suffix,"Fictional payment verification","",true,0,null),"verification");
  registration.start(sender);registration.language(sender,"en");registration.exam(sender,exam);
  registration.contact(sender,sender,"09"+String.format("%08d",Math.floorMod(suffix.hashCode(),100000000)));
  var f=SettingsForm.from(settings.current());
  settings.update(new SettingsForm(f.freePracticeLimit(),f.freeMockLimit(),f.questionsPerMock(),new BigDecimal("50"),"ETB",true,true,f.supportInfo(),f.mockDurationMinutes()),"verification");
  long method=methods.save(null,new PaymentMethodService.Form("BANK_TRANSFER","DEVELOPMENT TEST","Fictional","NOT-A-REAL-ACCOUNT","Do not pay",true,0,null),"verification");
  long first=payments.start(sender,exam,"first").request().id();payments.select(sender,first,method);
  payments.reference(sender,first,"DEVTEST-"+suffix);
  payments.receipt(sender,first,new ReceiptMetadata("fake_file","fake_unique","PHOTO",null,"image/jpeg",100));
  review.reject(first,"verification","Development test rejection");
  long second=payments.start(sender,exam,"second").request().id();payments.select(sender,second,method);
  assertThatThrownBy(()->payments.reference(sender,second,"devtest-"+suffix)).hasMessage("payment.duplicateReference");
  payments.reference(sender,second,"DEVTEST2-"+suffix);
  payments.receipt(sender,second,new ReceiptMetadata("fake_file2","fake_unique2","DOCUMENT","test.pdf","application/pdf",100));
  review.approve(second,"verification");review.approve(second,"verification");
  assertThat(grants.findByUserId(users.findByTelegramUserId(sender).orElseThrow().getId()).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,second)).isEqualTo(1);
  assertThat(queries.get(first).amount()).isEqualByComparingTo("50");
 }
 @Test void textProofPersistsWithoutReceiptAndReviewsOnlyItsTargetExam() {
  assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("22");
  String suffix=UUID.randomUUID().toString();long sender=8000000000000L+Math.floorMod(suffix.hashCode(),1000000000);
  String codeSuffix=suffix.replace("-","").substring(0,12);
  long examA=catalog.save(false,null,new CatalogForm("proof-a-"+codeSuffix,"Synthetic Exam A","",true,0,null),"verification");
  long examB=catalog.save(false,null,new CatalogForm("proof-b-"+codeSuffix,"Synthetic Exam B","",true,1,null),"verification");
  registration.start(sender);registration.language(sender,"en");registration.exam(sender,examA);
  registration.contact(sender,sender,"09"+String.format("%08d",Math.floorMod(suffix.hashCode(),100000000)));
  registration.exam(sender,examB);registration.exam(sender,examA);
  var settingsForm=SettingsForm.from(settings.current());
  settings.update(new SettingsForm(settingsForm.freePracticeLimit(),settingsForm.freeMockLimit(),settingsForm.questionsPerMock(),new BigDecimal("50"),"ETB",true,true,settingsForm.supportInfo(),settingsForm.mockDurationMinutes()),"verification");
  long method=methods.save(null,new PaymentMethodService.Form("BANK_TRANSFER","Synthetic transfer","TEST ONLY","SYNTHETIC-ONLY","Do not pay",true,0,null),"verification");
  long request=payments.start(sender,examB,"text-proof-"+suffix).request().id();payments.select(sender,request,method);
  assertThatThrownBy(()->payments.submitProof(sender,request," \n\t ")).hasMessage("payment.proofInvalid");
  String proof="Dear Customer,\nA debit of ETB 50.00 occurred.\nhttps://provider.example/receipt?id="+suffix;
  payments.submitProof(sender,request,"  "+proof+"  ");
  assertThat(queries.get(request).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
  assertThat(queries.get(request).paymentProofText()).isEqualTo(proof);
  assertThat(queries.get(request).reference()).isNull();assertThat(queries.get(request).receipt()).isNull();
  assertThat(jdbc.queryForMap("SELECT receipt_file_id,receipt_unique_id,payment_proof_text,target_exam_type_id FROM payment_requests WHERE id=?",request))
   .containsEntry("receipt_file_id",null).containsEntry("receipt_unique_id",null).containsEntry("payment_proof_text",proof).containsEntry("target_exam_type_id",examB);
  assertThat(users.findByTelegramUserId(sender).orElseThrow().getSelectedExamTypeId()).isEqualTo(examA);
  payments.submitProof(sender,request,proof);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE request_id=? AND kind='ADMIN_PENDING'",Integer.class,request)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_id=? AND action='PAYMENT_SUBMITTED_FOR_REVIEW'",Integer.class,request)).isEqualTo(1);
  review.approve(request,"verification");review.approve(request,"verification");
  long userId=users.findByTelegramUserId(sender).orElseThrow().getId();
  assertThat(grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow().getAccessLevel()).isEqualTo("FREE");
  assertThat(users.findByTelegramUserId(sender).orElseThrow().getSelectedExamTypeId()).isEqualTo(examA);

  long rejected=payments.start(sender,examA,"reject-proof-"+suffix).request().id();payments.select(sender,rejected,method);
  payments.submitProof(sender,rejected,"TXN-REJECT-"+suffix);review.reject(rejected,"verification","Synthetic rejection");
  assertThat(queries.get(rejected).status()).isEqualTo(PaymentStatus.REJECTED);
  assertThat(grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow().getAccessLevel()).isEqualTo("FREE");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,rejected)).isZero();
 }
}
