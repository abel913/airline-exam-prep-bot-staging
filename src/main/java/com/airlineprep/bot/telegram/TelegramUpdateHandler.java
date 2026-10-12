package com.airlineprep.bot.telegram;

import com.airlineprep.bot.user.RegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

public class TelegramUpdateHandler {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);
    private final TelegramBotClient client;
    private final RegistrationService registration;
    private final RegistrationPresenter presenter;
    private final StudentFlow students;
    private final PaymentFlow payments;

    public TelegramUpdateHandler(TelegramBotClient client, RegistrationService registration,
                                 RegistrationPresenter presenter,StudentFlow students) {
        this(client,registration,presenter,students,null);
    }
    public TelegramUpdateHandler(TelegramBotClient client, RegistrationService registration,
                                 RegistrationPresenter presenter,StudentFlow students,PaymentFlow payments) {
        this.payments=payments;
        this.students=students;
        this.client = client; this.registration = registration; this.presenter = presenter;
    }

    public void handle(JsonNode update, String botUsername) throws InterruptedException {
        handle(update, botUsername, false);
    }

    public void handleWebhook(JsonNode update, String botUsername) throws InterruptedException {
        handle(update, botUsername, true);
    }

    private void handle(JsonNode update, String botUsername, boolean retryStorageFailure) throws InterruptedException {
        if (update == null || !update.isObject()) return;
        JsonNode callback = update.path("callback_query");
        JsonNode message = callback.isObject() ? callback.path("message") : update.path("message");
        JsonNode sender = callback.isObject() ? callback.path("from") : message.path("from");
        String callbackData = callback.isObject() ? callback.path("data").asText("") : "";
        boolean mockCallback = callbackData.startsWith("m:");
        if (callback.isObject() && callback.path("id").isTextual()) {
            long started = System.nanoTime();
            try {
                client.answerCallbackQuery(callback.path("id").textValue());
                if (mockCallback) log.info("Telegram mock callback acknowledged (action={}, elapsedMs={})",
                        mockAction(callbackData), elapsedMillis(started));
            }
            catch (TelegramBotClient.ApiException exception) {
                if (mockCallback) log.warn("Telegram mock callback acknowledgement failed (action={}, code={}, elapsedMs={})",
                        mockAction(callbackData), exception.code(), elapsedMillis(started));
                else log.warn("Telegram callback acknowledgement failed (code {}, elapsedMs={})",
                        exception.code(), elapsedMillis(started));
            } catch (InterruptedException exception) {
                if (mockCallback) log.warn("Telegram mock callback acknowledgement interrupted (action={}, elapsedMs={})",
                        mockAction(callbackData), elapsedMillis(started));
                throw exception;
            }
        }
        Long senderId = positiveId(sender.path("id"));
        Long chatId = positiveId(message.path("chat").path("id"));
        if (senderId == null || chatId == null || !senderId.equals(chatId)
                || !"private".equals(message.path("chat").path("type").asText())
                || sender.path("is_bot").asBoolean(false)) return;
        try {
            if(students!=null && students.maintenance(senderId)) return;
            if (callback.isObject()) {
                String data = callback.path("data").asText("");
                if (data.equals("lang:en") || data.equals("lang:am"))
                    show(chatId, registration.language(senderId, data.substring(5)));
                else if (data.matches("exam:[1-9][0-9]{0,17}"))
                    show(chatId, registration.exam(senderId, Long.parseLong(data.substring(5))));
                else if (data.equals("s:exams")) presenter.show(chatId, registration.switchExams(senderId));
                else if(data.startsWith("s:")||data.startsWith("p:")||data.startsWith("m:")) {
                    if (data.startsWith("m:")) {
                        long started = System.nanoTime();
                        try {
                            students.callback(senderId,data);
                            log.info("Telegram mock callback completed (action={}, elapsedMs={})",
                                    mockAction(data), elapsedMillis(started));
                        } catch (RuntimeException exception) {
                            if (exception instanceof TelegramBotClient.ApiException telegramFailure)
                                log.warn("Telegram mock callback failed (action={}, failure=telegram_api, code={}, elapsedMs={})",
                                        mockAction(data), telegramFailure.code(), elapsedMillis(started));
                            else if (exception instanceof DataAccessException || exception instanceof TransactionException)
                                log.warn("Telegram mock callback failed (action={}, failure=database, errorType={}, elapsedMs={})",
                                        mockAction(data), exception.getClass().getSimpleName(), elapsedMillis(started));
                            else log.warn("Telegram mock callback failed (action={}, failure=application, errorType={}, elapsedMs={})",
                                    mockAction(data), exception.getClass().getSimpleName(), elapsedMillis(started));
                            throw exception;
                        }
                    } else students.callback(senderId,data);
                }
                else if(data.startsWith("pay:")&&payments!=null) payments.callback(senderId,data);
                return;
            }
            if (message.path("contact").isObject()) {
                if (message.has("forward_origin") || message.has("forward_date") || message.has("forward_from")
                        || message.has("forward_from_chat") || message.path("is_automatic_forward").asBoolean()) {
                    show(chatId, registration.contact(senderId, null, null));
                    return;
                }
                var contact = message.path("contact");
                var result=registration.contact(senderId,
                    positiveId(contact.path("user_id")), contact.path("phone_number").asText(null));
                if(result.status()==com.airlineprep.bot.user.RegistrationStatus.COMPLETED && result.errorKey()==null) presenter.removeContactKeyboard(chatId,result.language());
                show(chatId,result);
                log.debug("Telegram contact update handled");
                return;
            }
            String text = message.path("text").asText("");
            String command = text.split("\\s+", 2)[0];
            if (command.equals("/start") || (botUsername != null && command.startsWith("/start@")
                    && command.substring(7).equalsIgnoreCase(botUsername))) {
                show(chatId, registration.start(senderId));
                log.debug("Telegram registration step sent");
            } else if(!command.startsWith("/")) {
                var phonePrompt = registration.manualPhoneInput(senderId);
                if (phonePrompt.isPresent() && message.path("text").isTextual() && !text.isBlank()) {
                    show(chatId, registration.manualPhone(senderId, text));
                    return;
                }
                if (message.path("text").isTextual() && !text.isBlank()) {
                    if (phonePrompt.isPresent()) {
                        presenter.show(chatId, phonePrompt.get());
                        return;
                    }
                }
                if(payments!=null) payments.message(senderId,message);
            }
        } catch (DataAccessException | TransactionException exception) {
            if (retryStorageFailure) throw exception;
            // Database exception details may contain private bind values.
            log.warn("Registration storage unavailable; user may retry /start");
            presenter.unavailable(chatId);
        }
    }
    private void show(long chatId,com.airlineprep.bot.user.RegistrationView view) throws InterruptedException {
        if(view.status()==com.airlineprep.bot.user.RegistrationStatus.COMPLETED) students.menu(chatId);
        else presenter.show(chatId,view);
    }
    private Long positiveId(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0
                ? node.longValue() : null;
    }
    private String mockAction(String data) {
        if (data.startsWith("m:s:")) return "prepare";
        if (data.startsWith("m:o:") || data.startsWith("m:r:")) return "open_or_review";
        if (data.startsWith("m:a:")) return "answer";
        if (data.startsWith("m:f:")) return "finish";
        if (data.equals("m:intro")) return "intro";
        return "other";
    }
    private long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
