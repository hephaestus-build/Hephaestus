package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

    @Test
    void shouldBuildTheSenderFromBootsMailProperties() {
        runner.withPropertyValues(
                        "spring.mail.host=smtp.example.org",
                        "spring.mail.port=587",
                        "spring.mail.username=relay-user",
                        "spring.mail.password=relay-secret",
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
