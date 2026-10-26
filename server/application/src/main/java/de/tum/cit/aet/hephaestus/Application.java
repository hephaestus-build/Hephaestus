package de.tum.cit.aet.hephaestus;

import de.tum.cit.aet.hephaestus.core.database.DatabaseMigration;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Hephaestus root application class.
 *
 * <p>{@link org.springframework.scheduling.annotation.EnableScheduling @EnableScheduling} is
 * intentionally NOT declared here — it lives in
 * {@link de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig} which is gated by
 * {@link de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole#SERVER_PROPERTY}, so the worker and
 * webhook containers fire no {@code @Scheduled} method. See ADR 0008.
 *
 * <p>With the {@value DatabaseMigration#PROFILE} profile active, the process migrates the database and exits
 * instead ({@link DatabaseMigration}).
 *
 * <p>{@link de.tum.cit.aet.hephaestus.notification.email.EmailTransportConfiguration} gates the
 * excluded mail auto-configuration on a configured SMTP host and the server runtime role.
 */
@SpringBootApplication(exclude = MailSenderAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableResilientMethods
public class Application {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        if (DatabaseMigration.requested(args)) {
            System.exit(DatabaseMigration.run(args));
        }
        SpringApplication application = new SpringApplication(Application.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
    }
}
