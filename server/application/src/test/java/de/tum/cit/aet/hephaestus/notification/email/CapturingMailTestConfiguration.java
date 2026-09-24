package de.tum.cit.aet.hephaestus.notification.email;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Stands in for the relay in the integration tier: {@code spring.mail.host} is blank under the test
 * profile, so no production sender exists and this one is the only {@link JavaMailSender}.
 */
@TestConfiguration
public class CapturingMailTestConfiguration {

    @Bean
    CapturingJavaMailSender capturingJavaMailSender() {
        return new CapturingJavaMailSender();
    }
}
