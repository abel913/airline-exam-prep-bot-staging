package com.airlineprep.bot.telegram;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.airlineprep.bot.user.RegistrationView;
import org.springframework.context.MessageSource;

public class RegistrationPresenter {
    private final TelegramBotClient client;
    private final MessageSource messages;
    public RegistrationPresenter(TelegramBotClient client, MessageSource messages) {
        this.client = client; this.messages = messages;
    }
    public void show(long chatId, RegistrationView view) throws InterruptedException {
        Locale locale = Locale.forLanguageTag(view.language());
        if (view.errorKey() != null && view.status() != com.airlineprep.bot.user.RegistrationStatus.PHONE_REQUIRED)
            client.sendMessage(chatId, message(view.errorKey(), locale));
        switch (view.status()) {
            case LANGUAGE_REQUIRED -> client.sendMessage(chatId, message("registration.language", locale),
                Map.of("inline_keyboard", List.of(List.of(
                    Map.of("text", "English", "callback_data", "lang:en"),
                    Map.of("text", "አማርኛ", "callback_data", "lang:am")))));
            case EXAM_TYPE_REQUIRED -> {
                if (view.exams().isEmpty()) {
                    client.sendMessage(chatId, message("registration.noExams", locale), Map.of("remove_keyboard", true));
                } else {
                    var rows = view.exams().stream().map(e -> List.of(Map.of(
                        "text", "am".equals(view.language()) && !e.nameAm().isBlank() ? e.nameAm() : e.name(),
                        "callback_data", "exam:" + e.id()))).toList();
                    client.sendMessage(chatId, message("registration.exam", locale), Map.of("inline_keyboard", rows));
                }
            }
            case EXAM_SWITCH_REQUIRED -> {
                var rows = view.exams().stream().map(e -> List.of(Map.of(
                    "text", (e.current() ? "✅ " : "") + ("am".equals(view.language()) && !e.nameAm().isBlank() ? e.nameAm() : e.name()),
                    "callback_data", "exam:" + e.id()))).toList();
                client.sendMessage(chatId, message("registration.switchExam", locale), Map.of("inline_keyboard", rows));
            }
            case PHONE_REQUIRED -> client.sendMessage(chatId,
                message(view.errorKey() == null ? "registration.phone" : view.errorKey(), locale),
                Map.of("keyboard", List.of(List.of(Map.of("text", message("registration.share", locale),
                    "request_contact", true))), "resize_keyboard", true, "one_time_keyboard", false,
                    "is_persistent", true));
            case COMPLETED -> client.sendMessage(chatId, messages.getMessage("registration.complete",
                new Object[]{view.practiceLimit(), view.mockLimit(), view.questionsPerMock()}, locale),
                Map.of("remove_keyboard", true));
        }
    }
    public void unavailable(long chatId) throws InterruptedException {
        client.sendMessage(chatId, message("registration.retry", Locale.ENGLISH));
    }
    public void removeContactKeyboard(long chatId,String language) throws InterruptedException {
        client.sendMessage(chatId,message("registration.ready",Locale.forLanguageTag(language)),Map.of("remove_keyboard",true));
    }
    private String message(String key, Locale locale) { return messages.getMessage(key, null, locale); }
}
