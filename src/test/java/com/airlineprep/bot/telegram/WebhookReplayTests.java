package com.airlineprep.bot.telegram;

import java.util.Map;
import com.airlineprep.bot.payment.PaymentFixture;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.MockAttemptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties="payment.notifications.automatic=false")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookReplayTests extends PaymentFixture {
    @Autowired PracticeService practice;
    @Autowired MockAttemptService mocks;
    @Autowired StudentProgressService progress;
    @Autowired MessageSource messages;
    final ObjectMapper mapper=new ObjectMapper();
    final TelegramBotClient client=mock(TelegramBotClient.class);
    TelegramWebhookController controller;

    void initialize() {
        setupPayment();content(2);
        var presenter=new StudentPresenter(client,messages);
        var handler=new TelegramUpdateHandler(client,registration,new RegistrationPresenter(client,messages),
                new StudentFlow(practice,mocks,progress,presenter,settings),new PaymentFlow(payments,presenter,registration));
        controller=new TelegramWebhookController(new TelegramBotProperties(true,"123:fictional",TelegramBotProperties.Mode.WEBHOOK,"fictional-test"),client,handler,mapper);
    }
    void post(Map<String,Object> update,int status) throws Exception {
        var request=new MockHttpServletRequest("POST",TelegramWebhookController.PATH);
        request.addHeader(TelegramWebhookController.SECRET_HEADER,"fictional-test");
        request.setContent(mapper.writeValueAsBytes(update));
        assertThat(controller.receive(request).getStatusCode().value()).isEqualTo(status);
    }
    Map<String,Object> callback(String data) {
        return Map.of("update_id",5,"callback_query",Map.of("id","cb","data",data,"from",Map.of("id",sender),
                "message",Map.of("chat",Map.of("id",sender,"type","private"))));
    }
    @Test void repeatedWebhookCannotDuplicateUsagePaymentOrFinancialDecision() throws Exception {
        initialize();
        long delivery=practice.next(sender,null,null,false).delivery().id();
        var answer=callback("p:a:"+delivery+":0");post(answer,200);post(answer,200);
        assertThat(grant().getPracticeUsed()).isEqualTo(1);
        long attempt=mocks.prepare(sender,"webhook-mock").attempt().id();mocks.open(sender,attempt,0,false);
        var mockAnswer=callback("m:a:"+attempt+":0:0:0");post(mockAnswer,200);post(mockAnswer,200);
        assertThat(grant().getMocksUsed()).isEqualTo(1);
        post(callback("pay:open"),200);
        var start=callback("pay:exam:"+exam);post(start,200);post(start,200);
        long payment=payments.status(sender).request().id();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_requests WHERE user_id=?",Integer.class,grant().getUserId())).isEqualTo(1);
        payments.select(sender,payment,method);payments.reference(sender,payment,"FICTIONAL-WEBHOOK-"+sender);
        var receipt=Map.<String,Object>of("update_id",9,"message",Map.of("from",Map.of("id",sender),
                "chat",Map.of("id",sender,"type","private"),"document",Map.of("file_id","fictional","file_unique_id","fictional-unique", "file_name","test.png","mime_type","image/png","file_size",8)));
        post(receipt,200);post(receipt,200);review.approve(payment,"test-admin");post(receipt,200);review.approve(payment,"test-admin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifetime_access_grants WHERE payment_request_id=?",Integer.class,payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit_events WHERE entity_id=? AND action='PAYMENT_APPROVED'",Integer.class,payment)).isEqualTo(1);
    }
    @Test void lostResponseRetriesPersistedAnswerWithoutDoubleConsumption() throws Exception {
        initialize();long id=practice.next(sender,null,null,false).delivery().id();var answer=callback("p:a:"+id+":0");
        doThrow(mock(TelegramBotClient.ApiException.class)).when(client).sendMessage(anyLong(),anyString(),anyMap());
        post(answer,503);assertThat(grant().getPracticeUsed()).isEqualTo(1);
        doNothing().when(client).sendMessage(anyLong(),anyString(),anyMap());post(answer,200);
        assertThat(grant().getPracticeUsed()).isEqualTo(1);
    }
    @Test void repeatedOnboardingCreatesOneIdentityAndEntitlement() throws Exception {
        initialize();sender+=900000;
        var start=Map.<String,Object>of("update_id",1,"message",Map.of("from",Map.of("id",sender),"chat",Map.of("id",sender,"type","private"),"text","/start"));
        post(start,200);post(start,200);post(callback("lang:en"),200);post(callback("exam:"+exam),200);
        var contact=Map.<String,Object>of("update_id",2,"message",Map.of("from",Map.of("id",sender),"chat",Map.of("id",sender,"type","private"),
                "contact",Map.of("user_id",sender,"phone_number","09"+String.format("%08d",sender))));
        post(contact,200);post(contact,200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE telegram_user_id=?",Integer.class,sender)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?",Integer.class,grant().getUserId())).isEqualTo(1);
    }
}
