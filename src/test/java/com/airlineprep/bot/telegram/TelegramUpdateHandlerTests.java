package com.airlineprep.bot.telegram;

import java.util.List;
import java.util.Map;
import com.airlineprep.bot.user.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TelegramUpdateHandlerTests {
    @ParameterizedTest @ValueSource(strings={"forward_origin","forward_date","forward_from","forward_from_chat","is_automatic_forward"})
    void forwardedContactsCannotCompleteRegistration(String field) throws Exception {
        var update=(com.fasterxml.jackson.databind.node.ObjectNode)message("");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)update.path("message");body.put(field,true);
        body.set("contact",mapper.valueToTree(Map.of("user_id",12,"phone_number","0912345678")));
        when(registration.contact(12,null,null)).thenReturn(view(RegistrationStatus.PHONE_REQUIRED));
        handler.handle(update,"AirlineTestBot");verify(registration).contact(12,null,null);
        verify(registration,never()).contact(eq(12L),eq(12L),anyString());
    }
    @Test void retryDelayHonorsRateLimitButCapsUntrustedExtremeValues() {
        assertThat(TelegramLongPollingService.retryDelay(2,30)).isEqualTo(30);
        assertThat(TelegramLongPollingService.retryDelay(60,0)).isEqualTo(60);
        assertThat(TelegramLongPollingService.retryDelay(2,Long.MAX_VALUE)).isEqualTo(3600);
    }
    final ObjectMapper mapper = new ObjectMapper();
    final TelegramBotClient client = mock(TelegramBotClient.class);
    final RegistrationService registration = mock(RegistrationService.class);
    final RegistrationPresenter presenter = new RegistrationPresenter(client, messages());
    final TelegramUpdateHandler handler = new TelegramUpdateHandler(client, registration, presenter,mock(StudentFlow.class));
    static ResourceBundleMessageSource messages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages"); source.setDefaultEncoding("UTF-8"); return source;
    }
    RegistrationView view(RegistrationStatus state) {
        return new RegistrationView(state, "en", List.of(), null, 100, 2, 50);
    }
    JsonNode message(String text) {
        return mapper.valueToTree(Map.of("message", Map.of("chat", Map.of("id", 12, "type", "private"),
            "from", Map.of("id", 12), "text", text)));
    }
    JsonNode callback(String data) {
        return mapper.valueToTree(Map.of("callback_query", Map.of("id", "query", "data", data,
            "from", Map.of("id", 12), "message", Map.of("chat", Map.of("id", 12, "type", "private")))));
    }
    @ParameterizedTest @ValueSource(strings = {"/start", "/start@AirlineTestBot", "/start@airlinetestbot", "/start referral"})
    void startPreservesWelcomeAndBeginsRegistration(String text) throws Exception {
        when(registration.start(12)).thenReturn(view(RegistrationStatus.LANGUAGE_REQUIRED));
        handler.handle(message(text), "AirlineTestBot");
        verify(client).sendMessage(eq(12L), contains("Welcome to Airline Exam Prep!"),
            argThat(m -> m.containsKey("inline_keyboard")));
    }
    @ParameterizedTest @ValueSource(strings = {"null","[]","42","{}","{\"message\":null}","{\"callback_query\":{}}",
        "{\"message\":{\"photo\":[]}}","{\"message\":{\"text\":123,\"chat\":{\"id\":12}}}",
        "{\"message\":{\"text\":\"/start\"}}","{\"message\":{\"text\":\"/start\",\"chat\":{\"id\":\"12\"}}}"})
    void malformedUpdatesAreIgnored(String json) throws Exception {
        handler.handle(mapper.readTree(json), "AirlineTestBot");
        verifyNoInteractions(registration);
    }
    @ParameterizedTest @ValueSource(strings = {"hello", "", " ", "/help", "/starting", "/start@OtherBot", "text /start", "0912345678"})
    void unsupportedTextRemainsSafe(String text) throws Exception {
        handler.handle(message(text), "AirlineTestBot");
        if (!text.isBlank() && !text.startsWith("/")) verify(registration).manualPhoneInput(12);
        verify(registration, never()).contact(anyLong(), any(), any());
        verifyNoInteractions(client);
    }
    @Test void nullUpdateIsIgnored() throws Exception { handler.handle(null, "AirlineTestBot"); verifyNoInteractions(client); }
    @Test void callbacksAreAcknowledgedAndLanguagePersisted() throws Exception {
        when(registration.language(12, "am")).thenReturn(view(RegistrationStatus.EXAM_TYPE_REQUIRED));
        handler.handle(callback("lang:am"), "AirlineTestBot");
        verify(client).answerCallbackQuery("query"); verify(registration).language(12, "am");
    }
    @ParameterizedTest @ValueSource(strings = {"lang:xx", "exam:-1", "exam:999999999999999999999999", "broken"})
    void invalidCallbacksAreAcknowledgedAndIgnored(String data) throws Exception {
        handler.handle(callback(data), "AirlineTestBot");
        verify(client).answerCallbackQuery("query"); verifyNoInteractions(registration);
    }
    @Test void examCallbackRequestsOwnContact() throws Exception {
        when(registration.exam(12, 7)).thenReturn(view(RegistrationStatus.PHONE_REQUIRED));
        handler.handle(callback("exam:7"), "AirlineTestBot");
        verify(client).answerCallbackQuery("query");
        verify(client).sendMessage(eq(12L), eq(PHONE_PROMPT), eq(contactKeyboard()));
        verifyNoMoreInteractions(client);
    }
    static final String PHONE_PROMPT = "📱 PHONE NUMBER VERIFICATION\n\n"
        + "To continue registration, share the phone number\nconnected to YOUR Telegram account.\n\n"
        + "👇 TAP THE BUTTON BELOW 👇\n\nDo not type your phone number manually.";
    static Map<String,Object> contactKeyboard() {
        return Map.of("keyboard", List.of(List.of(Map.of("text", "👉 📲 SHARE MY PHONE NUMBER 👈",
            "request_contact", true))), "resize_keyboard", true, "one_time_keyboard", false, "is_persistent", true);
    }
    RegistrationView phoneError(String key) {
        return new RegistrationView(RegistrationStatus.PHONE_REQUIRED,"en",List.of(),key,null,null,null);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void manualNumberGetsOneWarningAndPersistentContactButton(boolean webhook) throws Exception {
        when(registration.manualPhoneInput(12)).thenReturn(java.util.Optional.of(phoneError("registration.manualPhone")));
        var payments = mock(PaymentFlow.class);
        var routed = new TelegramUpdateHandler(client, registration, presenter, mock(StudentFlow.class), payments);
        if (webhook) routed.handleWebhook(message("+251912345678"), "AirlineTestBot");
        else routed.handle(message("+251912345678"), "AirlineTestBot");
        verify(client).sendMessage(12L,"⚠️ Please don't type your phone number.\n\n"
            + "For security, use the button below so Telegram can verify that the number belongs to you.\n\n"
            + "👇 TAP THE BUTTON BELOW 👇",contactKeyboard());
        verifyNoMoreInteractions(client);
        verify(registration,never()).contact(anyLong(),any(),any());
        verifyNoInteractions(payments);
    }
    JsonNode contact(long owner) {
        var update=(com.fasterxml.jackson.databind.node.ObjectNode)message("");
        ((com.fasterxml.jackson.databind.node.ObjectNode)update.path("message")).set("contact",
            mapper.valueToTree(Map.of("user_id",owner,"phone_number","0912345678")));
        return update;
    }
    @Test void wrongContactGetsOneWarningAndKeepsKeyboard() throws Exception {
        when(registration.contact(12,99L,"0912345678")).thenReturn(phoneError("registration.ownContact"));
        handler.handle(contact(99),"AirlineTestBot");
        verify(client).sendMessage(12L,"⚠️ This is not your Telegram-linked phone number.\n\n"
            + "Please use the button below to share your own number.\n\n👇 TAP THE BUTTON BELOW 👇",contactKeyboard());
        verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings={"registration.invalidPhone","registration.duplicatePhone","registration.unavailable"})
    void otherContactErrorsAlsoKeepOneKeyboard(String key) throws Exception {
        presenter.show(12,phoneError(key));
        verify(client).sendMessage(eq(12L),anyString(),eq(contactKeyboard()));
        verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownContactRemovesKeyboardBeforeOpeningExistingMenu(boolean webhook) throws Exception {
        var students=mock(StudentFlow.class);
        var routed=new TelegramUpdateHandler(client,registration,presenter,students);
        when(registration.contact(12,12L,"0912345678")).thenReturn(view(RegistrationStatus.COMPLETED));
        if(webhook) routed.handleWebhook(contact(12),"AirlineTestBot");
        else routed.handle(contact(12),"AirlineTestBot");
        var order=inOrder(client,students);
        order.verify(client).sendMessage(12L,"✅ Phone number verified successfully.",Map.of("remove_keyboard",true));
        order.verify(students).menu(12);
        verifyNoMoreInteractions(client);
    }
    @Test void registeredStartOpensMenuWithoutPhonePrompt() throws Exception {
        var students=mock(StudentFlow.class);
        when(registration.start(12)).thenReturn(view(RegistrationStatus.COMPLETED));
        new TelegramUpdateHandler(client,registration,presenter,students).handle(message("/start"),"AirlineTestBot");
        verify(students).menu(12);verifyNoInteractions(client);
    }
    @Test void registeredTextStillReachesPayments() throws Exception {
        var payments=mock(PaymentFlow.class);
        var update=message("0912345678");
        new TelegramUpdateHandler(client,registration,presenter,mock(StudentFlow.class),payments)
            .handle(update,"AirlineTestBot");
        verify(payments).message(12,update.path("message"));verifyNoInteractions(client);
    }
    @Test void contactPassesSenderAndOwnerSeparately() throws Exception {
        when(registration.contact(12, 99L, "0912345678")).thenReturn(view(RegistrationStatus.PHONE_REQUIRED));
        handler.handle(mapper.valueToTree(Map.of("message", Map.of("chat", Map.of("id",12,"type","private"),
            "from",Map.of("id",12),"contact",Map.of("user_id",99,"phone_number","0912345678")))), "AirlineTestBot");
        verify(registration).contact(12, 99L, "0912345678");
    }
    @ParameterizedTest @ValueSource(strings = {"group","supergroup","channel"})
    void registrationNeverRunsInGroups(String type) throws Exception {
        var update = (com.fasterxml.jackson.databind.node.ObjectNode) message("/start");
        ((com.fasterxml.jackson.databind.node.ObjectNode) update.path("message").path("chat")).put("type",type);
        handler.handle(update,"AirlineTestBot"); verifyNoInteractions(registration,client);
    }
    @Test void unavailableDatabaseGetsSafeRetryMessage() throws Exception {
        when(registration.start(12)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private details"));
        handler.handle(message("/start"),"AirlineTestBot");
        verify(client).sendMessage(eq(12L),contains("retry"));
    }
    @Test void noExamsGetsSafeMessage() throws Exception {
        presenter.show(12,view(RegistrationStatus.EXAM_TYPE_REQUIRED));
        verify(client).sendMessage(eq(12L),contains("temporarily unavailable"),anyMap());
    }
    @Test void amharicPromptsAndSuccessUseSelectedLanguage() throws Exception {
        presenter.show(12,new RegistrationView(RegistrationStatus.PHONE_REQUIRED,"am",List.of(),null,null,null,null));
        verify(client).sendMessage(eq(12L),contains("የኢትዮጵያ"),anyMap());
        presenter.show(12,new RegistrationView(RegistrationStatus.COMPLETED,"am",List.of(),null,100,2,50));
        verify(client).sendMessage(eq(12L),contains("ተጠናቋል"),eq(Map.of("remove_keyboard",true)));
    }
}
