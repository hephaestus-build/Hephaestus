package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Consumer-side parser for GitLab NATS subjects.
 *
 * <p>Input shape: {@code gitlab.?connection.<connectionId>.<event>} produced by
 * {@link GitLabSubjectKeyDeriver#deriveConnectionSubject}. The connection id is passthrough: route admission resolves
 * it. The event token becomes the {@link EventTypeKey#eventType()} verbatim.
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
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "GitLab subject must have exactly 4 components (gitlab.?connection.<id>.<event>), got: "
                            + fullSubject);
        }
        if (!"gitlab".equals(parts[0]) || !GitLabSubjectKeyDeriver.CONNECTION_TOKEN.equals(parts[1])) {
            throw new IllegalArgumentException("subject must start with 'gitlab.?connection.', got: " + fullSubject);
        }
        String event = parts[3];
        if (event.isBlank()) {
            throw new IllegalArgumentException("GitLab subject event component is blank: " + fullSubject);
        }
        return new EventTypeKey(IntegrationKind.GITLAB, event);
    }
}
