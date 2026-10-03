package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Consumer-side parser for GitLab NATS subjects.
 *
 * <p>Input shape: {@code gitlab.<namespace~with~tildes>.<project>.<event>} produced by
 * {@link GitLabSubjectKeyDeriver}. Namespace and project are passthrough (we don't
 * dispatch on them here — handlers do their own scope filtering). The event token
 * becomes the {@link EventTypeKey#eventType()} verbatim.
 *
 * <p>Unlike GitHub, GitLab has no domain-tier prefix on the eventType — its event-type
 * space is flat.
 */
@Component
public class GitLabSubjectParser implements SubjectParser {

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public EventTypeKey parse(@Nullable String fullSubject) {
        if (fullSubject == null || fullSubject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        String[] parts = fullSubject.split("\\.", -1);
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "GitLab subject must have at least 4 components (gitlab.<ns>.<proj>.<event>), got: " + fullSubject);
        }
        if (!"gitlab".equals(parts[0])) {
            throw new IllegalArgumentException("subject must start with 'gitlab.', got: " + fullSubject);
        }
        // The deriver replaces '.' in the event with '~', so a derived subject has exactly 4 components; an
        // unsanitized one keeps its trailing components in the event token.
        String event;
        if (parts.length == 4) {
            event = parts[3];
        } else {
            event = String.join(".", Arrays.copyOfRange(parts, 3, parts.length));
        }
        if (event.isBlank()) {
            throw new IllegalArgumentException("GitLab subject event component is blank: " + fullSubject);
        }
        return new EventTypeKey(IntegrationKind.GITLAB, event);
    }
}
