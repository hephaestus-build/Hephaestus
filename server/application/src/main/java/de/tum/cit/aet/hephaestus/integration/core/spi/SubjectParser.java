package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Per-kind event subject → {@link EventTypeKey} on the consumer side.
 *
 * <p>GitHub's domain wrinkle (repository / organization / installation tiers) is
 * encoded into the eventType prefix: {@code "github.acme.?.repository"} parses to
 * {@code EventTypeKey(GITHUB, "organization.repository")}; {@code "github.?.?.installation"}
 * parses to {@code EventTypeKey(GITHUB, "installation.installation")}.
 */
public interface SubjectParser {
    IntegrationKind kind();

    EventTypeKey parse(String fullSubject);
}
