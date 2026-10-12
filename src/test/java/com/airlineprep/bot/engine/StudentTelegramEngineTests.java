package com.airlineprep.bot.engine;
import java.util.*;
import com.airlineprep.bot.telegram.*;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest @Transactional
class StudentTelegramEngineTests extends EngineFixture {
 @Autowired PracticeService practice;
 @Autowired MockAttemptService mocks;
 @Autowired StudentProgressService progress;
 @Autowired MessageSource messages;
 @Autowired JdbcTemplate jdbc;
 TelegramBotClient client;TelegramUpdateHandler handler;
 List<String> texts;List<String> callbacks;
 ObjectMapper mapper=new ObjectMapper();
 @BeforeEach void setup() throws Exception {
  prepareFixture(2);content(3);client=mock(TelegramBotClient.class);texts=new ArrayList<>();callbacks=new ArrayList<>();
  doAnswer(invocation->{texts.add(invocation.getArgument(1));
   Map<?,?> markup=invocation.getArgument(2);
   var rows=(List<?>)markup.get("inline_keyboard");
   if(rows!=null) for(var row:rows) for(var button:(List<?>)row) callbacks.add((String)((Map<?,?>)button).get("callback_data"));
   return null;
  }).when(client).sendMessage(anyLong(),anyString(),anyMap());
  doAnswer(i->{texts.add(i.getArgument(1));return null;}).when(client).sendMessage(anyLong(),anyString());
  handler=new TelegramUpdateHandler(client,registration,new RegistrationPresenter(client,messages),
   new StudentFlow(practice,mocks,progress,new StudentPresenter(client,messages)));
 }
 void click(String data) throws Exception {
  texts.clear();callbacks.clear();
  handler.handle(mapper.valueToTree(Map.of("callback_query",Map.of("id",UUID.randomUUID().toString(),"data",data,
   "from",Map.of("id",sender),"message",Map.of("chat",Map.of("id",sender,"type","private"))))),"TestBot");
 }
 String button(String prefix) { return callbacks.stream().filter(c->c.startsWith(prefix)).findFirst().orElseThrow(); }
 String text() { return String.join("\n",texts); }
 @Test void fullHandlerPracticeProgressMockResumeSubmitReview() throws Exception {
  handler.handle(mapper.valueToTree(Map.of("message",Map.of("text","/start","from",Map.of("id",sender),
   "chat",Map.of("id",sender,"type","private")))),"TestBot");
  assertThat(callbacks).contains("p:menu","m:intro","s:progress");
  click("p:menu");assertThat(callbacks).contains("p:c:"+category);
  click("p:c:"+category);assertThat(text()).contains("Fictional arithmetic").doesNotContain("Fictional explanation","Correct answer");
  String answer=button("p:a:");click(answer);
  assertThat(text()).contains("Fictional explanation");assertThat(grant().getPracticeUsed()).isEqualTo(1);
  click(answer);assertThat(grant().getPracticeUsed()).isEqualTo(1);
  click(button("p:n:"));assertThat(text()).doesNotContain("Fictional explanation");
  click("s:progress");assertThat(text()).contains("100");
  click("m:intro");String prepare=button("m:s:");click(prepare);
  assertThat(grant().getMocksUsed()).isZero();assertThat(text()).doesNotContain("Fictional arithmetic");
  click(button("m:o:"));String mockAnswer=button("m:a:");click(mockAnswer);
  assertThat(grant().getMocksUsed()).isEqualTo(1);assertThat(text()).doesNotContain("Fictional explanation","Correct answer");
  click(button("m:o:"));assertThat(text()).doesNotContain("Fictional explanation");
  click("s:home");click(button("m:o:"));assertThat(text()).contains("2");
  click(button("m:f:"));assertThat(text()).contains("50");
  String review=button("m:r:");click(review);assertThat(text()).contains("Fictional explanation","Correct answer");
  click("s:progress");assertThat(callbacks.stream().anyMatch(c->c.startsWith("m:f:"))).isTrue();
  assertThat(callbacks).allMatch(c->c.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=64);
 }
 @Test void malformedForgedAndUnsupportedCallbacksDoNotConsume() throws Exception {
  for(String data:List.of("p:a:999999:0","m:o:999999:0","p:c:999999999999999999999999","m:a:bad:0:0:0","s:unknown","p:a:<bad>")) click(data);
  assertThat(grant().getPracticeUsed()).isZero();assertThat(grant().getMocksUsed()).isZero();
 }
 @Test void lifetimePremiumMockCanBePreparedThroughTelegramCallback() throws Exception {
  grant().setAccessLevel("LIFETIME");grant().setMockLimit(0);
  // Keep this callback-path test's pool Premium-only; setup() normally seeds free mock questions.
  jdbc.update("UPDATE question_versions SET mock_pool=false WHERE exam_type_id=?",exam);
  question("Synthetic premium mock question",false,true,true);
  question("Synthetic premium mock question",false,true,true);

  click("m:intro");
  assertThat(text()).contains("Unlimited");
  click(button("m:s:"));
  assertThat(text()).contains("2 questions are frozen and ready");
  assertThat(grant().getMocksUsed()).isZero();
  var callbackOrder=inOrder(client);
  callbackOrder.verify(client).answerCallbackQuery(anyString());
  callbackOrder.verify(client).sendMessage(anyLong(),anyString(),anyMap());
  callbackOrder.verify(client).answerCallbackQuery(anyString());
  callbackOrder.verify(client).sendMessage(anyLong(),anyString(),anyMap());

  click(button("m:o:"));
  assertThat(text()).contains("Synthetic premium mock question");
 }
 @Test void insufficientLifetimeMockPoolReturnsAnErrorThroughTelegramCallback() throws Exception {
  grant().setAccessLevel("LIFETIME");grant().setMockLimit(0);
  var current=com.airlineprep.bot.settings.SettingsForm.from(settings.current());
  settings.update(new com.airlineprep.bot.settings.SettingsForm(current.freePracticeLimit(),current.freeMockLimit(),4,
   current.lifetimePrice(),current.currency(),current.paymentEnabled(),current.manualPaymentEnabled(),current.supportInfo(),
   current.mockDurationMinutes()),"test-admin");

  click("m:intro");
  click(button("m:s:"));
  assertThat(text()).contains("Not enough eligible mock questions are currently available");
  assertThat(grant().getMocksUsed()).isZero();
  assertThat(mocks.introduction(sender).active()).isNull();
  var callbackOrder=inOrder(client);
  callbackOrder.verify(client).answerCallbackQuery(anyString());
  callbackOrder.verify(client).sendMessage(anyLong(),anyString(),anyMap());
  callbackOrder.verify(client).answerCallbackQuery(anyString());
  callbackOrder.verify(client).sendMessage(anyLong(),anyString(),anyMap());
 }
 @Test void callbackAcknowledgementFailureDoesNotBlockHandlerAndSendFailureIsNotSwallowed() throws Exception {
  doThrow(mock(TelegramBotClient.ApiException.class)).when(client).answerCallbackQuery(anyString());

  click("m:intro");
  assertThat(text()).contains("Mock exam:");
  verify(client).sendMessage(anyLong(),anyString(),anyMap());

  doThrow(mock(TelegramBotClient.ApiException.class)).when(client).sendMessage(anyLong(),anyString(),anyMap());
  String prepare=button("m:s:");
  assertThatThrownBy(()->click(prepare)).isInstanceOf(TelegramBotClient.ApiException.class);
 }
 @Test void groupAndMismatchedSenderNeverReachEngine() throws Exception {
  for(String type:List.of("group","supergroup","channel"))
   handler.handle(mapper.valueToTree(Map.of("callback_query",Map.of("id","group","data","p:c:0","from",Map.of("id",sender),
    "message",Map.of("chat",Map.of("id",sender,"type",type))))),"TestBot");
  handler.handle(mapper.valueToTree(Map.of("callback_query",Map.of("id","forged","data","p:c:0","from",Map.of("id",sender+1),
   "message",Map.of("chat",Map.of("id",sender,"type","private"))))),"TestBot");
  assertThat(texts).isEmpty();assertThat(grant().getPracticeUsed()).isZero();
 }
 @Test void longPlainTextAndAmharicRemainWithinTelegramLimits() throws Exception {
  var v=practice.next(sender,null,null,false);
  var q=v.question();
  var longQuestion=new com.airlineprep.bot.question.StudentQuestion(q.id(),q.versionId(),q.examId(),q.categoryId(),q.categoryName(),
   "<b>"+"ሀ😀".repeat(5000),q.options(),q.explanation());
  new StudentPresenter(client,messages).practice(sender,new PracticeService.View(v.student(),v.delivery(),longQuestion));
  assertThat(texts).allMatch(t->t.length()<=3500);assertThat(text()).contains("<b>");
  var user=users.findByTelegramUserId(sender).orElseThrow();user.setPreferredLanguage("am");entityManager.flush();
  click("s:home");assertThat(text()).doesNotContain("Practice remaining");assertThat(callbacks).contains("p:menu");
 }
}
