package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Per-kind subject and delivery-key derivation on the receive side.
 *
 * <p>Subjects stay vendor-prefixed ({@code github.*}, {@code gitlab.*}, etc.). Adding
 * a new kind = registering a new {@link SubjectKeyDeriver} bean keyed on
 * {@link #kind()}.
 */
public interface SubjectKeyDeriver {
    IntegrationKind kind();

    /** Builds the provider event subject from payload + headers. */
    String deriveSubject(JsonNode payload, Map<String, String> headers);

    /**
     * Builds the delivery key from the raw body + headers. The inbox stores one row per workspace and delivery key, so a
     * provider's retry or redelivery adds none.
     *
     * <p>Per-vendor strategy:
     * <ul>
     *   <li>GitHub: {@code "github-" + X-GitHub-Delivery}, which a redelivery keeps
     *   <li>GitLab: a connection's key from {@code GitLabSubjectKeyDeriver#deriveConnectionDedupKey}
     *   <li>Slack: {@code "slack-" + event_id}
     * </ul>
     */
    String deriveDedupKey(byte[] body, Map<String, String> headers);
}
