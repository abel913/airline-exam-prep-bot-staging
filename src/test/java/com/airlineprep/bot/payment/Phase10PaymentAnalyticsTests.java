package com.airlineprep.bot.payment;
import com.airlineprep.bot.analytics.AnalyticsService;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @Transactional
class Phase10PaymentAnalyticsTests extends PaymentFixture {
 @Autowired AnalyticsService analytics;
 @Test void currencyGroupsAndRepeatedDecisionsNeverDoubleCount() {
  setupPayment();long rejected=pending();review.reject(rejected,"test-admin","Fictional rejection");
  long approved=pending();review.approve(approved,"test-admin");review.approve(approved,"test-admin");
  long firstUser=sender;prepareFixture(2);long pending=pending();
  // Fixture-only alternate currency; no financial evidence is exported.
  jdbc.update("UPDATE payment_requests SET currency='USD',amount=3 WHERE id=?",pending);
  entityManager.flush();
  var filter=new AnalyticsService.Filter("all",null,null,null,null,null,null,null,null);
  var rows=analytics.report("payments",filter,0,25);
  assertThat(rows.rows()).hasSize(3);
  assertThat(rows.rows()).anySatisfy(r->{assertThat(r.get(0)).isEqualTo("ETB");assertThat(r.get(1)).isEqualTo("APPROVED");assertThat(new BigDecimal(r.get(4))).isEqualByComparingTo("50");assertThat(r.get(2)).isEqualTo("1");});
  assertThat(rows.rows()).anySatisfy(r->{assertThat(r.get(0)).isEqualTo("USD");assertThat(r.get(1)).isEqualTo("PENDING_REVIEW");assertThat(new BigDecimal(r.get(4))).isEqualByComparingTo("0");});
  assertThat(analytics.overview(filter).get("Lifetime grants in window")).isEqualTo(1L);
  assertThat(new BigDecimal(analytics.overview(filter).get("Payment approval % (decisions in window)").toString())).isEqualByComparingTo("50");
  String rendered=rows.toString();assertThat(rendered).doesNotContain("DEVTEST-","test_file","phone_identity","recipient_id");
  assertThat(analytics.report("outbox",filter,0,25).headers()).doesNotContain("recipient_id","claim_token","request_id");
 }
}
