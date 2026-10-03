package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link SubjectParser}. Re-encodes the three GitHub event tiers
 * (repository / organization / installation) into the {@link EventTypeKey} prefix so
 * downstream handler routing stays vendor-agnostic:
 * <ul>
 *   <li>{@code github.<org>.<repo>.<event>} → {@code repository.<event>}
 *   <li>{@code github.<org>.?.<event>} → {@code organization.<event>}
 *   <li>{@code github.?.?.<event>} → {@code installation.<event>}
 * </ul>
 *
 * <p>The literal {@code ?} placeholder is reserved (matches the producer-side
 * {@link GitHubSubjectKeyDeriver}). Subjects with fewer than four dot-delimited
 * components are malformed and throw {@link IllegalArgumentException}. That exception is
 * caught by {@code IntegrationMessageDispatcher#dispatch}, which logs it at DEBUG and
 * returns no handler — the consumer then ACKs and skips the message. A malformed subject is
 * therefore a silent ACK-drop, not a dead-letter (nothing is redelivered or parked).
 */
@Component
public class GitHubSubjectParser implements SubjectParser {

    private static final String PREFIX = "github.";
    private static final String PLACEHOLDER = "?";

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public EventTypeKey parse(@Nullable String fullSubject) {
        if (fullSubject == null || fullSubject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (!fullSubject.startsWith(PREFIX)) {
            throw new IllegalArgumentException("subject must start with 'github.': " + fullSubject);
        }
        String[] parts = fullSubject.split("\\.", -1);
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "subject must have >= 4 dot-separated components, got " + parts.length + ": " + fullSubject);
        }
        // parts[0] = "github", parts[1] = org, parts[2] = repo, parts[3..] = event (may carry suffixes).
        String org = parts[1];
        String repo = parts[2];
        // Rejoin the tail in case the event segment itself contains dots; the deriver sanitizes dots to
        // ~ so this should not trigger, but preserving all tail segments costs nothing.
        StringBuilder eventBuilder = new StringBuilder(parts[3]);
        for (int i = 4; i < parts.length; i++) {
            eventBuilder.append('.').append(parts[i]);
        }
        String event = eventBuilder.toString();
        if (event.isBlank()) {
            throw new IllegalArgumentException("event segment must not be blank: " + fullSubject);
        }

        String tier;
        if (PLACEHOLDER.equals(org) && PLACEHOLDER.equals(repo)) {
            tier = "installation";
        } else if (PLACEHOLDER.equals(repo)) {
            tier = "organization";
        } else {
            tier = "repository";
        }
        return new EventTypeKey(IntegrationKind.GITHUB, tier + "." + event);
    }
}
