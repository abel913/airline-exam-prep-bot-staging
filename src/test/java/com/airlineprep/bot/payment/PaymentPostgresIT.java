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
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
/** Explicit development PostgreSQL verification. Scenario writes are rolled back. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password="})
@Transactional
class PaymentPostgresIT {
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
}
