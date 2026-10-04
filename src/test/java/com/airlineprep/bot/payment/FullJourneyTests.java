package com.airlineprep.bot.payment;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.airlineprep.bot.telegram.*;
import com.airlineprep.bot.admin.CatalogForm;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.settings.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest(properties={"telegram.admin.id=999000123","payment.notifications.automatic=false"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FullJourneyTests extends PaymentFixture {
 static final AtomicLong SENDERS=new AtomicLong(800000);
 @MockitoBean TelegramBotClient client;
 @MockitoBean java.time.Clock clock;
 @Autowired MessageSource messages;@Autowired PracticeService practice;@Autowired MockAttemptService mocks;@Autowired StudentProgressService progress;
 @Autowired PaymentNotificationDispatcher notifications;@Autowired MockMvc mvc;
 TelegramUpdateHandler handler;final ObjectMapper json=new ObjectMapper();final List<String> texts=new ArrayList<>(),buttons=new ArrayList<>();
 @BeforeEach void setup() throws Exception {
  when(clock.instant()).thenReturn(java.time.Instant.parse("2026-01-01T00:00:00Z"));
  setupPayment();content(3);var f=SettingsForm.from(settings.current());
  settings.update(new SettingsForm(1,1,2,f.lifetimePrice(),f.currency(),true,true,"Fictional support",null),"test-admin");
  sender=SENDERS.incrementAndGet();
  doAnswer(call->{texts.add(call.getArgument(1));Map<?,?> markup=call.getArgument(2);if(markup.get("inline_keyboard") instanceof List<?> rows)for(Object row:rows)for(Object b:(List<?>)row)buttons.add((String)((Map<?,?>)b).get("callback_data"));return null;}).when(client).sendMessage(anyLong(),anyString(),anyMap());
  var ui=new StudentPresenter(client,messages);handler=new TelegramUpdateHandler(client,registration,new RegistrationPresenter(client,messages),new StudentFlow(practice,mocks,progress,ui,settings),new PaymentFlow(payments,ui,registration));
 }
 void message(Map<String,Object> fields) throws Exception {var body=new HashMap<>(fields);body.put("from",Map.of("id",sender));body.put("chat",Map.of("id",sender,"type","private"));texts.clear();buttons.clear();handler.handle(json.valueToTree(Map.of("message",body)),"TestBot");}
 void click(String data) throws Exception {texts.clear();buttons.clear();handler.handle(json.valueToTree(Map.of("callback_query",Map.of("id","cb","data",data,"from",Map.of("id",sender),"message",Map.of("chat",Map.of("id",sender,"type","private"))))),"TestBot");}
 String button(String prefix){return buttons.stream().filter(b->b.startsWith(prefix)).findFirst().orElseThrow();}
 String phone(){return "09"+String.format("%08d",sender);}
 void onboard(String language,String phone) throws Exception {message(Map.of("text","/start"));click("lang:"+language);click("exam:"+exam);message(Map.of("contact",Map.of("user_id",sender,"phone_number",phone)));}
 long submitPayment(String reference) throws Exception {
  click("pay:open");click(button("pay:exam:"));click(button("pay:method:"));message(Map.of("text",reference));
  var receipt=Map.<String,Object>of("photo",List.of(Map.of("file_id","test_file","file_unique_id","test_unique_"+sender,"file_size",100,"width",10,"height",10)));
  message(receipt);long id=payments.status(sender).request().id();message(receipt);assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);return id;
 }
 @Test void fullStudentJourneyThroughWebApprovalAndUnlimitedAccess() throws Exception {
  onboard("en",phone());assertThat(buttons).contains("p:menu","m:intro","pay:open");assertThat(grant().getPracticeLimit()).isEqualTo(1);
  click("s:help");assertThat(String.join("",texts)).contains("Fictional support","not immediate");
  click("p:c:0");String answer=button("p:a:");click(answer);click(answer);assertThat(grant().getPracticeUsed()).isEqualTo(1);
  click("s:progress");assertThat(progress.get(sender).answered()).isEqualTo(1);
  click("m:intro");click(button("m:s:"));click(button("m:o:"));click(button("m:a:"));long attempt=mocks.introduction(sender).active().id();click("m:f:"+attempt);
  assertThat(progress.get(sender).completed()).isEqualTo(1);assertThat(grant().getMocksUsed()).isEqualTo(1);
  long payment=submitPayment("DEVTEST-JOURNEY-"+sender);assertThat(queries.get(payment).amount()).isEqualByComparingTo("50");
  notifications.retryDue();
  mvc.perform(post("/admin/payments/"+payment+"/approve").with(user("test-admin").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
  notifications.retryDue();verify(client).sendMessage(eq(sender),contains("Lifetime access is now active"),anyMap());
  message(Map.of("text","/start"));assertThat(String.join("",texts)).contains("Unlimited");
  var delivery=practice.next(sender,null,null,false);practice.answer(sender,delivery.delivery().id(),0);
  var unlimited=mocks.prepare(sender,"after-upgrade");mocks.open(sender,unlimited.attempt().id(),0,false);mocks.answer(sender,unlimited.attempt().id(),0,0,0);
  assertThat(grant().getPracticeUsed()).isEqualTo(1);assertThat(grant().getMocksUsed()).isEqualTo(1);
 }
 @Test void amharicDuplicatePhoneDoesNotRevealOwnerAndAnotherUserIsIndependent() throws Exception {
  String shared=phone();onboard("en",shared);long first=sender;sender=SENDERS.incrementAndGet();onboard("am",shared);
  assertThat(users.findByTelegramUserId(sender).orElseThrow().getRegistrationStatus()).isEqualTo(com.airlineprep.bot.user.RegistrationStatus.PHONE_REQUIRED);
  assertThat(String.join("",texts)).doesNotContain(Long.toString(first),shared);
  message(Map.of("contact",Map.of("user_id",sender,"phone_number",phone())));assertThat(grant().getPracticeUsed()).isZero();assertThat(grant().getAccessLevel()).isEqualTo("FREE");
 }
 @Test void multiExamUsageAndLifetimePurchasesRemainExamScoped() throws Exception {
  onboard("en",phone());
  long examA=exam;
  long userId=users.findByTelegramUserId(sender).orElseThrow().getId();
  var practiceA=practice.next(sender,null,null,false);practice.answer(sender,practiceA.delivery().id(),0);
  var mockA=mocks.prepare(sender,"phase5-exam-a");mocks.open(sender,mockA.attempt().id(),0,false);
  mocks.answer(sender,mockA.attempt().id(),0,0,0);
  mocks.submit(sender,mockA.attempt().id());

  long examB=catalog.save(false,null,new CatalogForm("phase5-cabin-"+sender,"Synthetic Cabin Crew","",true,1,null),"test-admin");
  long categoryB=catalog.save(true,null,new CatalogForm("phase5-category-"+sender,"Synthetic Cabin Category","",true,1,examB),"test-admin");
  exam=examB;category=categoryB;content(3);
  registration.exam(sender,examB);
  var practiceB=practice.next(sender,null,null,false);practice.answer(sender,practiceB.delivery().id(),0);
  var mockB=mocks.prepare(sender,"phase5-exam-b");mocks.open(sender,mockB.attempt().id(),0,false);
  mocks.answer(sender,mockB.attempt().id(),0,0,0);
  var a=grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow();
  var b=grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow();
  assertThat(a.getPracticeUsed()).isEqualTo(1);assertThat(a.getMocksUsed()).isEqualTo(1);
  assertThat(b.getPracticeUsed()).isEqualTo(1);assertThat(b.getMocksUsed()).isEqualTo(1);

  registration.exam(sender,examA);long paymentA=submitPaymentForExam(examA,"DEVTEST-MULTI-A-"+sender);
  assertThat(queries.get(paymentA).examTypeId()).isEqualTo(examA);
  review.approve(paymentA,"test-admin");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examA).orElseThrow().getAccessLevel()).isEqualTo("LIFETIME");
  assertThat(grants.findByUserIdAndExamTypeId(userId,examB).orElseThrow().getAccessLevel()).isEqualTo("FREE");

  long paymentB=submitPaymentForExam(examB,"DEVTEST-MULTI-B-"+sender);
  assertThat(queries.get(paymentB).examTypeId()).isEqualTo(examB);
  review.approve(paymentB,"test-admin");
  assertThat(grants.findAllByUserIdOrderByExamTypeId(userId)).hasSize(2)
   .allMatch(entitlement->entitlement.getAccessLevel().equals("LIFETIME"));
  assertThat(users.findByTelegramUserId(sender).orElseThrow().getSelectedExamTypeId()).isEqualTo(examA);
 }

 long submitPaymentForExam(long target,String reference) throws Exception {
  click("pay:open");assertThat(buttons).contains("pay:exam:"+target);
  click("pay:exam:"+target);click(button("pay:method:"));message(Map.of("text",reference));
  message(Map.of("photo",List.of(Map.of("file_id","test_file","file_unique_id","target_unique_"+target+"_"+sender,"file_size",100,"width",10,"height",10))));
  return payments.status(sender).request().id();
 }
 @Test void rejectedPaymentNotifiesAndAllowsNewRequestWithoutReferenceReuse() throws Exception {
  onboard("en",phone());String reference="DEVTEST-REJECT-"+sender;long id=submitPayment(reference);
  mvc.perform(post("/admin/payments/"+id+"/reject").param("reason","Fictional verification rejected").with(user("test-admin").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
  notifications.retryDue();verify(client).sendMessage(eq(sender),contains("Fictional verification rejected"),anyMap());assertThat(grant().getAccessLevel()).isEqualTo("FREE");
  long next=payments.start(sender,exam,"after-rejection").request().id();payments.select(sender,next,method);
  assertThatThrownBy(()->payments.reference(sender,next,reference)).hasMessage("payment.duplicateReference");
 }
 @Test void lostPracticeFeedbackAndReceiptAcknowledgmentPreserveCommittedState() throws Exception {
  onboard("en",phone());click("p:c:0");String answer=button("p:a:");
  doThrow(mock(TelegramBotClient.ApiException.class)).when(client).sendMessage(eq(sender),anyString(),anyMap());
  assertThatThrownBy(()->click(answer)).isInstanceOf(TelegramBotClient.ApiException.class);assertThat(grant().getPracticeUsed()).isEqualTo(1);
  doNothing().when(client).sendMessage(eq(sender),anyString(),anyMap());click(answer);assertThat(grant().getPracticeUsed()).isEqualTo(1);
  long id=selected();payments.reference(sender,id,"DEVTEST-LOST-"+sender);
  doThrow(mock(TelegramBotClient.ApiException.class)).when(client).sendMessage(eq(sender),anyString(),anyMap());
  assertThatThrownBy(()->message(Map.of("document",Map.of("file_id","test","file_unique_id","unique","file_name","test.png","mime_type","image/png","file_size",8)))).isInstanceOf(TelegramBotClient.ApiException.class);
  assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
 }
}
