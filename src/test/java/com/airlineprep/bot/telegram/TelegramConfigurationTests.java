package com.airlineprep.bot.telegram;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class TelegramConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TelegramConfiguration.class);

    @Test
    void disabledNeedsNoTokenOrHttpClient() {
        contextRunner.withPropertyValues("telegram.bot.enabled=false", "telegram.bot.token=")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(TelegramBotClient.class)
                            .doesNotHaveBean(TelegramLongPollingService.class);
                    assertThat(context.getBean(TelegramBotProperties.class).isTokenConfigured()).isTrue();
                });
    }

    @Test
    void defaultIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TelegramLongPollingService.class);
            assertThat(context.getBean(TelegramBotProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void enabledRequiresTokenWithoutEchoingRejectedValue(CapturedOutput output) {
        // Register only properties: invalid configuration fails before transport creation.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("telegram.bot.enabled=true", "telegram.bot.token=invalid-secret-marker")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("TELEGRAM_BOT_TOKEN")
                            .hasStackTraceContaining("tokenConfigured");
                    assertThat(context.getStartupFailure().toString()).doesNotContain("invalid-secret-marker");
                });
        assertThat(output.getAll()).doesNotContain("invalid-secret-marker");
    }

    @Test
    void propertiesNeverRenderToken() {
        assertThat(new TelegramBotProperties(true, "sensitive-marker").toString())
                .contains("<redacted>").doesNotContain("sensitive-marker");
        assertThat(new TelegramBotProperties(true, "").isTokenConfigured()).isFalse();
        assertThat(new TelegramBotProperties(true, null).isTokenConfigured()).isFalse();
    }

    @Test
    void webhookModeNeverCreatesPollingWorker() {
        contextRunner.withPropertyValues("telegram.bot.enabled=true", "telegram.bot.mode=WEBHOOK",
                        "telegram.bot.token=123:fictional", "telegram.bot.webhook-secret=fictional-webhook-test")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(com.airlineprep.bot.settings.SettingsService.class, () -> mock(com.airlineprep.bot.settings.SettingsService.class))
                .withBean(com.airlineprep.bot.payment.PaymentService.class, () -> mock(com.airlineprep.bot.payment.PaymentService.class))
                .withBean(com.airlineprep.bot.practice.StudentInsights.class, () -> mock(com.airlineprep.bot.practice.StudentInsights.class))
                .withBean(com.airlineprep.bot.practice.PracticeService.class, () -> mock(com.airlineprep.bot.practice.PracticeService.class))
                .withBean(com.airlineprep.bot.mock.MockAttemptService.class, () -> mock(com.airlineprep.bot.mock.MockAttemptService.class))
                .withBean(com.airlineprep.bot.practice.StudentProgressService.class, () -> mock(com.airlineprep.bot.practice.StudentProgressService.class))
                .withBean(com.airlineprep.bot.user.RegistrationService.class, () -> mock(com.airlineprep.bot.user.RegistrationService.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TelegramWebhookController.class)
                        .hasSingleBean(TelegramUpdateHandler.class).doesNotHaveBean(TelegramLongPollingService.class));
    }

    @Test
    void disabledWebhookModeHasNoInboundOrOutboundTransport() {
        contextRunner.withPropertyValues("telegram.bot.enabled=false", "telegram.bot.mode=WEBHOOK")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TelegramWebhookController.class)
                        .doesNotHaveBean(TelegramLongPollingService.class).doesNotHaveBean(TelegramBotClient.class));
    }

    @Test
    void webhookSecretValidationNeverEchoesRejectedValue(CapturedOutput output) {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("telegram.bot.enabled=true", "telegram.bot.token=123:fictional", "telegram.bot.mode=WEBHOOK",
                        "telegram.bot.webhook-secret=invalid private marker")
                .run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain("invalid private marker");
        assertThat(new TelegramBotProperties(true,"123:fictional",TelegramBotProperties.Mode.WEBHOOK,"private-marker").toString())
                .doesNotContain("private-marker");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledContextPollsThroughMockTransportAndStopsOnClose() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        HttpResponse<String> identity = mock(HttpResponse.class);
        when(identity.statusCode()).thenReturn(200);
        when(identity.body()).thenReturn(
                "{\"ok\":true,\"result\":{\"is_bot\":true,\"username\":\"AirlineTestBot\"}}");
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        when(transport.send(any(HttpRequest.class), org.mockito.ArgumentMatchers
                .<HttpResponse.BodyHandler<String>>any())).thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    if (request.uri().getPath().endsWith("/getMe")) {
                        return identity;
                    }
                    polling.countDown();
                    try {
                        new CountDownLatch(1).await();
                        return identity;
                    } catch (InterruptedException exception) {
                        stopped.countDown();
                        throw exception;
                    }
                });
        contextRunner.withPropertyValues("telegram.bot.enabled=true",
                        "telegram.bot.token=123456:unit-test-placeholder")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(com.airlineprep.bot.settings.SettingsService.class,
                        () -> mock(com.airlineprep.bot.settings.SettingsService.class))
                .withBean(com.airlineprep.bot.payment.PaymentService.class,
                        () -> mock(com.airlineprep.bot.payment.PaymentService.class))
                .withBean(com.airlineprep.bot.practice.StudentInsights.class, () -> mock(com.airlineprep.bot.practice.StudentInsights.class))
                .withBean(com.airlineprep.bot.practice.PracticeService.class,
                        () -> mock(com.airlineprep.bot.practice.PracticeService.class))
                .withBean(com.airlineprep.bot.mock.MockAttemptService.class,
                        () -> mock(com.airlineprep.bot.mock.MockAttemptService.class))
                .withBean(com.airlineprep.bot.practice.StudentProgressService.class,
                        () -> mock(com.airlineprep.bot.practice.StudentProgressService.class))
                .withBean(com.airlineprep.bot.user.RegistrationService.class,
                        () -> mock(com.airlineprep.bot.user.RegistrationService.class))
                .withBean("mockHttpTransport", HttpClient.class, () -> transport,
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TelegramLongPollingService.class);
                    assertThat(polling.await(3, TimeUnit.SECONDS)).isTrue();
                });
        assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(TelegramBotProperties.class)
    static class PropertiesOnly {
    }
}
