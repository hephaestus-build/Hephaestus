package de.tum.cit.aet.hephaestus.notification.email;

import de.tum.cit.aet.hephaestus.notification.metrics.NotificationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** One counter per (kind, outcome); both tags are bounded enums, never an address. */
@Component
public class EmailDeliveryMetrics {

    private final MeterRegistry registry;

    public EmailDeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(EmailKind kind, EmailDeliveryResult.Outcome outcome) {
        Counter.builder(NotificationMetrics.EMAIL_DELIVERY)
                .description("Email delivery attempts by kind and terminal outcome.")
                .tag("kind", kind.tag())
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }
}
