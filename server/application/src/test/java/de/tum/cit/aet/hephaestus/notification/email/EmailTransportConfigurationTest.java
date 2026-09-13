package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderValidatorAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** The one meaning of "email is on": a non-blank {@code spring.mail.host}. */
@Tag("integration")
class EmailTransportConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EmailTransportConfiguration.class);

    @Test
    void shouldRegisterNoSenderOutsideTheServerRole() {
        runner.withPropertyValues("spring.mail.host=smtp.example.org", "hephaestus.runtime.server.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JavaMailSender.class));
    }

    @Test
    void shouldTreatHostAsDataRatherThanAnExpression() {
        runner.withPropertyValues("spring.mail.host=host'with-quote")
                .run(context -> assertThat(
                                context.getBean(JavaMailSenderImpl.class).getHost())
                        .isEqualTo("host'with-quote"));
    }

    @Test
    void shouldRegisterNoSenderWhenTheHostIsBlank() {
        runner.withPropertyValues("spring.mail.host=", "spring.mail.port=587")
                .run(context -> assertThat(context).doesNotHaveBean(JavaMailSender.class));
    }

    @Test
    void shouldRegisterNoSenderWhenTheHostIsAbsent() {
        runner.run(context -> assertThat(context).doesNotHaveBean(JavaMailSender.class));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void shouldRejectAuthenticatedSmtpWithoutRequiredEncryption(boolean enabled, boolean required) {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.org",
                        "spring.mail.properties[mail.smtp.auth]=true",
                        "spring.mail.properties[mail.smtp.starttls.enable]=" + enabled,
                        "spring.mail.properties[mail.smtp.starttls.required]=" + required)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Authenticated SMTP requires implicit TLS or STARTTLS both enabled and required");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldValidateEncryptionBeforeBootTestsTheConnection(boolean encrypted) throws Exception {
        var sender = spy(new JavaMailSenderImpl());
        sender.getSession().getProperties().setProperty("mail.smtp.auth", "true");
        sender.getSession().getProperties().setProperty("mail.smtp.starttls.enable", Boolean.toString(encrypted));
        sender.getSession().getProperties().setProperty("mail.smtp.starttls.required", Boolean.toString(encrypted));
        doNothing().when(sender).testConnection();

        runner.withConfiguration(AutoConfigurations.of(MailSenderValidatorAutoConfiguration.class))
                .withBean(JavaMailSenderImpl.class, () -> sender)
                .withPropertyValues("spring.mail.host=smtp.example.org", "spring.mail.test-connection=true")
                .run(context -> {
                    if (encrypted) {
                        assertThat(context).hasNotFailed();
                        verify(sender).testConnection();
                    } else {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage(
                                        "Authenticated SMTP requires implicit TLS or STARTTLS both enabled and required");
                        verify(sender, never()).testConnection();
                    }
                });
    }

    @Test
    void shouldRejectPlaintextCredentialsEvenWhenAuthIsDisabled() {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.org",
                        "spring.mail.username=relay-user",
                        "spring.mail.password=relay-secret",
                        "spring.mail.properties[mail.smtp.auth]=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldAllowUnauthenticatedPlaintextMailpit() {
        runner.withPropertyValues(
                        "spring.mail.host=localhost",
                        "spring.mail.port=1025",
                        "spring.mail.username=",
                        "spring.mail.password=",
                        "spring.mail.properties[mail.smtp.auth]=false")
                .run(context -> assertThat(context).hasSingleBean(JavaMailSender.class));
    }

    @Test
    void shouldAllowAuthenticatedSmtpWithRequiredStartTls() {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.org",
                        "spring.mail.properties[mail.smtp.auth]=true",
                        "spring.mail.properties[mail.smtp.starttls.enable]=true",
                        "spring.mail.properties[mail.smtp.starttls.required]=true")
                .run(context -> assertThat(context).hasSingleBean(JavaMailSender.class));
    }

    @Test
    void shouldAllowImplicitTlsThroughTheSmtpsProtocol() {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.org",
                        "spring.mail.protocol=smtps",
                        "spring.mail.properties[mail.smtps.auth]=true",
                        "spring.mail.properties[mail.smtps.ssl.enable]=false")
                .run(context -> assertThat(context).hasSingleBean(JavaMailSender.class));
    }

    @Test
    void shouldBuildTheSenderFromBootsMailProperties() {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.org",
                        "spring.mail.port=587",
                        "spring.mail.username=relay-user",
                        "spring.mail.password=relay-secret",
                        "spring.mail.properties[mail.smtp.auth]=true",
                        "spring.mail.properties[mail.smtp.starttls.required]=true",
                        "spring.mail.ssl.enabled=true")
                .run(context -> {
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    assertThat(sender.getHost()).isEqualTo("smtp.example.org");
                    assertThat(sender.getPort()).isEqualTo(587);
                    assertThat(sender.getUsername()).isEqualTo("relay-user");
                    assertThat(sender.getPassword()).isEqualTo("relay-secret");
                    assertThat(sender.getProtocol()).isEqualTo("smtp");
                    assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
                    assertThat(sender.getJavaMailProperties())
                            .containsEntry("mail.smtp.starttls.required", "true")
                            .containsEntry("mail.smtp.ssl.enable", "true");
                });
    }
}
