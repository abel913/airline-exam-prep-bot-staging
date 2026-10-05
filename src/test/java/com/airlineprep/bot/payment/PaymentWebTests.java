package com.airlineprep.bot.payment;
import com.airlineprep.bot.telegram.TelegramBotClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest @AutoConfigureMockMvc @Transactional
class PaymentWebTests extends PaymentFixture {
 @Autowired MockMvc mvc;
 @MockitoBean TelegramBotClient client;
 long payment;
 @BeforeEach void setup() {setupPayment();payment=pending();}
 @ParameterizedTest @ValueSource(strings={"/admin/payments","/admin/payments/1","/admin/payments/1/receipt","/admin/payment-methods","/admin/payment-methods/new","/admin/payment-audit"})
 void pagesRequireWebAdmin(String path) throws Exception {
  mvc.perform(get(path)).andExpect(status().is3xxRedirection());
  mvc.perform(get(path).with(user("telegram-admin").roles("USER"))).andExpect(status().isForbidden());
 }
 @ParameterizedTest @ValueSource(strings={"approve","reject","retry-notification"})
 void reviewRequiresAuthenticationAndCsrf(String action) throws Exception {
  mvc.perform(post("/admin/payments/"+payment+"/"+action)).andExpect(status().isForbidden());
  mvc.perform(post("/admin/payments/"+payment+"/"+action).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
  mvc.perform(post("/admin/payments/"+payment+"/"+action).with(csrf()).with(user("telegram-id").roles("USER"))).andExpect(status().isForbidden());
 }
 @Test void pagesAndFiltersRenderEscapedEvidence() throws Exception {
  for(String path:new String[]{"/admin/payments","/admin/payment-methods","/admin/payment-methods/new","/admin/payment-methods/"+method+"/edit","/admin/payments/"+payment,"/admin/payment-audit","/admin"})
   mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
  mvc.perform(get("/admin/payments").param("status","PENDING_REVIEW").param("method",""+method).param("page","1").with(user("admin").roles("ADMIN")))
   .andExpect(status().isOk());
  mvc.perform(get("/admin/payment-audit").param("action","PAYMENT_REQUEST_CREATED").param("actorType","USER").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
 }
 @Test void textProofIsEscapedAndMultilineInAuthenticatedAdminReview() throws Exception {
  String proof="Bank confirmation\n<script>alert('x')</script>\nhttps://provider.example/receipt?id=synthetic\nhttp://example.test/form\nhttps://provider.example/receipt?id=synthetic\njavascript:alert(1)";
  jdbc.update("UPDATE payment_requests SET payment_proof_text=?,receipt_file_id=NULL,receipt_unique_id=NULL,receipt_type=NULL,receipt_filename=NULL,receipt_mime=NULL,receipt_size=NULL WHERE id=?",proof,payment);
  String html=mvc.perform(get("/admin/payments/"+payment).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
   .andReturn().getResponse().getContentAsString();
  assertThat(html).contains("Payment proof","Bank confirmation","&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;",
   "href=\"https://provider.example/receipt?id=synthetic\"","href=\"http://example.test/form\"","target=\"_blank\"","rel=\"noopener noreferrer\"","javascript:alert(1)");
  assertThat(html).doesNotContain("<script>alert('x')</script>","href=\"javascript:");
  assertThat(html.indexOf("href=\"https://provider.example/receipt?id=synthetic\"")).isLessThan(html.indexOf("href=\"http://example.test/form\""));
  assertThat(html.split("target=\"_blank\"",-1).length-1).isEqualTo(2);
  assertThat(html).contains("class=\"payment-proof\"");
 }
 @Test void referenceOnlyPaymentProofDoesNotRenderAnEmptyLinksSection() throws Exception {
  jdbc.update("UPDATE payment_requests SET payment_proof_text='TEST-REF-001',receipt_file_id=NULL,receipt_unique_id=NULL,receipt_type=NULL,receipt_filename=NULL,receipt_mime=NULL,receipt_size=NULL WHERE id=?",payment);
  String html=mvc.perform(get("/admin/payments/"+payment).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
   .andReturn().getResponse().getContentAsString();
  assertThat(html).contains("Payment proof","TEST-REF-001").doesNotContain("Links found in payment proof");
 }
 @Test void approveIsPostOnlyIdempotentAndAudited() throws Exception {
  mvc.perform(get("/admin/payments/"+payment+"/approve").with(user("admin").roles("ADMIN"))).andExpect(status().isMethodNotAllowed());
  for(int i=0;i<2;i++) mvc.perform(post("/admin/payments/"+payment+"/approve").with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().is3xxRedirection());
  assertThat(grant().getAccessLevel()).isEqualTo("LIFETIME");
  mvc.perform(post("/admin/payments/"+payment+"/reject").param("reason","No").with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE action='PAYMENT_APPROVED' AND entity_id=?",Integer.class,payment)).isEqualTo(1);
 }
 @Test void rejectRequiresReasonAndShowsEscapedStudentVisibleReason() throws Exception {
  mvc.perform(post("/admin/payments/"+payment+"/reject").param("reason"," ").with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().isConflict());
  mvc.perform(post("/admin/payments/"+payment+"/reject").param("reason","<script>not verified</script>").with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().is3xxRedirection());
  mvc.perform(get("/admin/payments/"+payment).with(user("admin").roles("ADMIN"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
  assertThat(grant().getAccessLevel()).isEqualTo("FREE");
 }
 @Test void receiptProxyIsPrivateBoundedAndRejectsInvalidContentAndFailure() throws Exception {
  byte[] jpeg={-1,-40,-1,0,0};
  when(client.receiptBytes("test_file")).thenReturn(jpeg);
  mvc.perform(get("/admin/payments/"+payment+"/receipt").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
   .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(jpeg));
  when(client.receiptBytes("test_file")).thenReturn("<script>evil</script>".getBytes());
  mvc.perform(get("/admin/payments/"+payment+"/receipt").with(user("admin").roles("ADMIN"))).andExpect(status().isBadGateway());
  when(client.receiptBytes("test_file")).thenThrow(mock(TelegramBotClient.ApiException.class));
  mvc.perform(get("/admin/payments/"+payment+"/receipt").with(user("admin").roles("ADMIN"))).andExpect(status().isBadGateway());
  mvc.perform(get("/admin/payments/999999/receipt").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
 }
 @Test void methodValidationCsrfAndAuditAreEnforced() throws Exception {
  mvc.perform(post("/admin/payment-methods/new").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
  mvc.perform(post("/admin/payment-methods/new").with(csrf()).with(user("admin").roles("ADMIN")).param("type","BAD").param("displayOrder","-1")).andExpect(status().isOk());
  mvc.perform(post("/admin/payment-methods/new").with(csrf()).with(user("admin").roles("ADMIN"))
   .param("type","BANK_TRANSFER").param("displayName","Development bank").param("accountName","Test").param("destination","NOT-A-REAL-ACCOUNT")
   .param("instructions","Do not pay").param("active","true").param("displayOrder","1")).andExpect(status().is3xxRedirection());
  assertThat(methods.list(true,0)).hasSize(2);
 }
 @Test void auditHasNoEditOrDeleteRoute() throws Exception {
  mvc.perform(post("/admin/payment-audit/1/edit").with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
  mvc.perform(delete("/admin/payment-audit/1").with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
 }
}
