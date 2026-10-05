package com.airlineprep.bot.payment;
import java.util.*;
import com.airlineprep.bot.telegram.*;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest @Transactional
class PaymentTelegramTests extends PaymentFixture {
 @Autowired MessageSource messages;@Autowired PracticeService practice;@Autowired MockAttemptService mocks;@Autowired StudentProgressService progress;
 TelegramBotClient client;TelegramUpdateHandler handler;ObjectMapper mapper=new ObjectMapper();
 List<String> texts,buttons;
 @BeforeEach void setup() throws Exception {
  setupPayment();client=mock(TelegramBotClient.class);texts=new ArrayList<>();buttons=new ArrayList<>();
  doAnswer(i->{texts.add(i.getArgument(1));Map<?,?> markup=i.getArgument(2);
   if(markup.get("inline_keyboard") instanceof List<?> rows) for(Object row:rows) for(Object button:(List<?>)row) buttons.add((String)((Map<?,?>)button).get("callback_data"));return null;
  }).when(client).sendMessage(anyLong(),anyString(),anyMap());
  var ui=new StudentPresenter(client,messages);
  handler=new TelegramUpdateHandler(client,registration,new RegistrationPresenter(client,messages),new StudentFlow(practice,mocks,progress,ui),new PaymentFlow(payments,ui,registration));
 }
 void click(String callback) throws Exception {
  texts.clear();buttons.clear();
  handler.handle(mapper.valueToTree(Map.of("callback_query",Map.of("id","cb","data",callback,"from",Map.of("id",sender),
   "message",Map.of("chat",Map.of("id",sender,"type","private"))))),"TestBot");
 }
 void message(Map<String,?> fields) throws Exception {
  var m=new HashMap<String,Object>();m.putAll(fields);m.put("from",Map.of("id",sender));m.put("chat",Map.of("id",sender,"type","private"));
  texts.clear();buttons.clear();handler.handle(mapper.valueToTree(Map.of("message",m)),"TestBot");
 }
 String button(String prefix) {return buttons.stream().filter(x->x.startsWith(prefix)).findFirst().orElseThrow();}
 @Test void fullUpdateFlowResumeTextProofPendingAndLifetimeMenu() throws Exception {
  message(Map.of("text","/start"));assertThat(buttons).contains("pay:open","pay:status");
  click("pay:open");assertThat(String.join("",texts)).contains("Which exam");
  click(button("pay:exam:"));assertThat(String.join("",texts)).contains("50.00","Test support");
  click(button("pay:method:"));
  long id=payments.status(sender).request().id();
  message(Map.of("text","/help"));assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.AWAITING_REFERENCE);
  message(Map.of("text","/start"));assertThat(buttons).contains("p:menu");click("pay:status");
  String guidance=messages.getMessage("payment.proofPrompt",null,Locale.ENGLISH);
  assertThat(String.join("",texts)).contains(guidance);
  message(Map.of("text","DEVTEST-TG"));assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
  assertThat(queries.get(id).paymentProofText()).isEqualTo("DEVTEST-TG");assertThat(queries.get(id).receipt()).isNull();
  assertThat(String.join("",texts)).contains("Pending manual review");
  message(Map.of("photo",List.of(Map.of("file_id","photo","file_unique_id","unique","width",100,"height",100,"file_size",100))));
  assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
  review.approve(id,"test-admin");message(Map.of("text","/start"));assertThat(String.join("",texts)).contains("Unlimited");
  click("pay:open");assertThat(String.join("",texts)).contains("Which exam");
  click(button("pay:exam:"));assertThat(String.join("",texts)).contains("Lifetime access is active");
 }
 @Test void photoDocumentAndBlankTextWhileWaitingReturnTextGuidanceWithoutAdvancing() throws Exception {
  long id=selected();String guidance=messages.getMessage("payment.proofPrompt",null,Locale.ENGLISH);
  for(Map<String,?> fields:List.<Map<String,?>>of(Map.of("photo",List.of(Map.of("file_id","photo","file_unique_id","unique","file_size",100))),
    Map.of("document",Map.of("file_id","doc","file_unique_id","doc-unique","file_name","proof.pdf","mime_type","application/pdf","file_size",100)),
    Map.of("text"," \n \t "))){
   message(fields);assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.AWAITING_REFERENCE);
   assertThat(queries.get(id).paymentProofText()).isNull();assertThat(queries.get(id).receipt()).isNull();
   assertThat(String.join("",texts)).contains(guidance);
  }
 }
 @Test void fullTransactionTextKeepsLinesAndUrlAndPromptIsExact() throws Exception {
  long id=selected();String prompt=messages.getMessage("payment.proofPrompt",null,Locale.ENGLISH);
  assertThat(prompt).isEqualTo("After payment, send either the full transaction confirmation message you received from the bank/payment provider, including the official receipt link, OR send only the transaction/reference number.");
  String proof="Dear Customer,\nA debit transaction occurred.\nhttps://provider.example/receipt?id=synthetic";
  message(Map.of("text",proof));assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);
  assertThat(queries.get(id).paymentProofText()).isEqualTo(proof);assertThat(queries.get(id).receipt()).isNull();
 }
 @Test void forwardedAndUnsupportedAttachmentsDoNotSubmit() throws Exception {
  long id=selected();payments.reference(sender,id,"DEVTEST-FORWARDED");
  message(Map.of("forward_origin",Map.of("type","user"),"photo",List.of(Map.of("file_id","photo","file_unique_id","unique","file_size",100))));
  assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);
  message(Map.of("document",Map.of("file_id","bad","file_unique_id","bad","file_name","test.exe","mime_type","application/octet-stream","file_size",10)));
  assertThat(queries.get(id).status()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);
 }
 @Test void pngDocumentAndAmharicStatusWork() throws Exception {
  users.findByTelegramUserId(sender).orElseThrow().setPreferredLanguage("am");entityManager.flush();
  long id=selected();payments.reference(sender,id,"DEVTEST-AMHARIC");
  message(Map.of("document",Map.of("file_id","png","file_unique_id","png_unique","file_name","test.png","mime_type","image/png","file_size",100)));
  assertThat(queries.get(id).receipt().mime()).isEqualTo("image/png");
  assertThat(String.join("",texts)).contains("በመጠባበቅ");
 }
}
