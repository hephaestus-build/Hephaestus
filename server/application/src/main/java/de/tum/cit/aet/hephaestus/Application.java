package de.tum.cit.aet.hephaestus;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Hephaestus root application class.
 *
 * <p>{@link org.springframework.scheduling.annotation.EnableScheduling @EnableScheduling} is
 * intentionally NOT declared here — it lives in
 * {@link de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig} which is gated by
 * {@link de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole#SERVER_PROPERTY}. The
 * {@code webhook-server} container (which sets {@code server.enabled=false}) must NOT fire any
 * {@code @Scheduled} methods, otherwise sync schedulers, zombie sweepers, and rate-limit eviction
 * would double-run alongside the {@code application-server} container. See ADR 0008.
 *
 * <p>{@link de.tum.cit.aet.hephaestus.notification.email.EmailTransportConfiguration} gates the
 * excluded mail auto-configuration on a configured SMTP host and the server runtime role.
 */
@SpringBootApplication(exclude = MailSenderAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableCaching
@EnableResilientMethods
public class Application {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        SpringApplication application = new SpringApplication(Application.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
    }
}
