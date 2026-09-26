package com.airlineprep.bot.telegram;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TelegramBotProperties.class,TelegramAdminProperties.class})
public class TelegramConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
    @org.springframework.context.annotation.Import(TelegramWebhookController.class)
    static class EnabledBot {

        @Bean(destroyMethod = "shutdownNow")
        HttpClient telegramHttpClient() {
            // JDK wire/debug logging can reveal token-bearing URLs before our error boundary.
            if (System.getProperty("jdk.httpclient.HttpClient.log") != null
                    || System.getProperty("jdk.internal.httpclient.debug") != null) {
                throw new IllegalStateException("Disable JDK HTTP client diagnostic logging before enabling Telegram");
            }
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Bean
        TelegramBotClient telegramBotClient(TelegramBotProperties properties,
                HttpClient telegramHttpClient, ObjectMapper mapper) {
            return new TelegramBotClient(properties, telegramHttpClient, mapper);
        }

        @Bean
        TelegramUpdateHandler telegramUpdateHandler(TelegramBotClient client, MessageSource messages,
                com.airlineprep.bot.user.RegistrationService registration,
                com.airlineprep.bot.practice.PracticeService practice,com.airlineprep.bot.mock.MockAttemptService mocks,
                com.airlineprep.bot.practice.StudentProgressService progress,
                com.airlineprep.bot.payment.PaymentService payments,com.airlineprep.bot.settings.SettingsService settings,
                com.airlineprep.bot.practice.StudentInsights insights) {
            var students=new StudentFlow(practice,mocks,progress,new StudentPresenter(client,messages),settings).withInsights(insights);
            return new TelegramUpdateHandler(client, registration, new RegistrationPresenter(client, messages),students,
                new PaymentFlow(payments,new StudentPresenter(client,messages)));
        }

        @Bean
        @ConditionalOnProperty(prefix = "telegram.bot", name = "mode", havingValue = "POLLING", matchIfMissing = true)
        TelegramLongPollingService telegramLongPollingService(TelegramBotClient client,
                TelegramUpdateHandler handler) {
            return new TelegramLongPollingService(client, handler);
        }

    }
}
