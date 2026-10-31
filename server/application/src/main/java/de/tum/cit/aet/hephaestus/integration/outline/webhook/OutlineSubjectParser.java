package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumer-side parser for Outline NATS subjects.
 *
 * <p>Input shape: {@code outline.<subscriptionId>.<event>} produced by
 * {@link OutlineSubjectKeyDeriver}. The subscription id is passthrough (handlers resolve the
 * workspace from the body). One handler routes every Outline event off the body's event name, so the
 * parser collapses the flat event space onto the single logical key
 * {@code EventTypeKey(OUTLINE, }{@value OutlineWebhookMessageHandler#EVENT_TYPE}{@code )} — analogous
 * to GitHub's parser folding domain tiers — after asserting the subject is well-formed. The specific
 * event still rides the subject/dedup key for observability.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineSubjectParser implements SubjectParser {

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public EventTypeKey parse(String fullSubject) {
        if (fullSubject == null || fullSubject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        String[] parts = fullSubject.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Outline subject must have exactly 3 components (outline.<sub>.<event>), got: " + fullSubject);
        }
        if (!"outline".equals(parts[0])) {
            throw new IllegalArgumentException("subject must start with 'outline.', got: " + fullSubject);
        }
        // The deriver replaces '.' in the event with '~'.
        String event = parts[2];
        if (event.isBlank()) {
            throw new IllegalArgumentException("Outline subject event component is blank: " + fullSubject);
        }
        return new EventTypeKey(IntegrationKind.OUTLINE, OutlineWebhookMessageHandler.EVENT_TYPE);
    }
}
