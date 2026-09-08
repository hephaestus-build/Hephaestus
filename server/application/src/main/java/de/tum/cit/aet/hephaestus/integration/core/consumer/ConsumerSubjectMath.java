package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Pure subject helpers for NATS consumers. Repository integrations use scoped wildcard filters such as
 * {@code github.owner.repo.>}; Slack Events API callbacks use per-workspace filters
 * {@code slack.<team>.>} (Slack interactivity/button postbacks stay on the separate signed HTTP
 * endpoint); Outline change notifications use per-subscription filters {@code outline.<subscriptionId>.>}.
 *
 * <p>{@link #kindFromSubjectPrefix(String)} is the single source of truth for NATS-subject routing. It
 * lists the kinds that publish to JetStream (GitHub, GitLab, Slack, Outline) and never calls
 * {@link IntegrationKind#valueOf(String)} on subject-derived input.
 *
 * @see de.tum.cit.aet.hephaestus.integration.core.webhook.IntegrationKindRouting the HTTP-path router
 */
public final class ConsumerSubjectMath {

    /** Subject-prefix allow-list — the kinds that publish to JetStream. */
    private static final Map<String, IntegrationKind> PREFIX_TO_KIND = Map.of(
            "github",
            IntegrationKind.GITHUB,
            "gitlab",
            IntegrationKind.GITLAB,
            "slack",
            IntegrationKind.SLACK,
            "outline",
            IntegrationKind.OUTLINE);

    private ConsumerSubjectMath() {}

    // Subject prefix construction (publisher-mirror)

    /**
     * Builds the NATS subject prefix for a repository identifier.
     * <ul>
     *   <li><b>GitHub:</b> {@code owner/repo} → {@code github.owner.repo} (exactly 2 parts)</li>
     *   <li><b>GitLab:</b> {@code group/sub/project} → {@code gitlab.group~sub.project}
     *       (namespace parts joined with {@code ~})</li>
     * </ul>
     * Dots in any path segment are sanitized to {@code ~} so the NATS token grammar holds.
     */
    public static String buildSubjectPrefix(String streamName, String nameWithOwner) {
        if (streamName == null || streamName.trim().isEmpty()) {
            throw new IllegalArgumentException("Stream name cannot be null or empty.");
        }
        if (nameWithOwner == null || nameWithOwner.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository identifier cannot be null or empty.");
        }

        String sanitized = nameWithOwner.trim().replace(".", "~");
        String[] parts = sanitized.split("/", -1);
        if (Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid repository format: '%s'. Empty path segments are not allowed.", nameWithOwner));
        }

        if ("gitlab".equals(streamName)) {
            if (parts.length < 2) {
                throw new IllegalArgumentException(String.format(
                        "Invalid GitLab repository format: '%s'. Expected 'namespace/project'.", nameWithOwner));
            }
            String namespace = String.join("~", Arrays.copyOfRange(parts, 0, parts.length - 1));
            String project = parts[parts.length - 1];
            return streamName + "." + namespace + "." + project;
        }

        if (parts.length != 2) {
            throw new IllegalArgumentException(String.format(
                    "Invalid repository format: '%s'. Expected format 'owner/repository'.", nameWithOwner));
        }
        return streamName + "." + parts[0] + "." + parts[1];
    }

    // Wildcard subject filters

    /**
     * Wildcard subject filter that matches every event for a single repository.
     *
     * @param streamName    the NATS stream name
     * @param nameWithOwner repository identifier in {@code owner/repo} (or nested for GitLab)
     * @return a subject like {@code github.owner.repo.>}
     */
    public static String repositoryFilter(String streamName, String nameWithOwner) {
        return buildSubjectPrefix(streamName, nameWithOwner) + ".>";
    }

    /**
     * Wildcard subject filter that matches every org-level event. Org events use the literal
     * {@code ?} placeholder where a repository would otherwise sit.
     *
     * @param streamName the NATS stream name
     * @param owner      the organization login
     * @return a subject like {@code github.owner.?.>}
     */
    public static String organizationFilter(String streamName, String owner) {
        return buildSubjectPrefix(streamName, owner + "/?") + ".>";
    }

    /**
     * Wildcard subject filter that matches every installation-level event for the given
     * {@link IntegrationKind}. Only GitHub publishes installation events today; the kind
     * stays a parameter so the SPI signature remains vendor-neutral.
     *
     * @return the subject e.g. {@code github.?.?.>}
     * @throws UnsupportedOperationException if {@code kind} has no installation semantics
     */
    public static String installationAwareSubjectFilter(@org.jspecify.annotations.Nullable IntegrationKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        return switch (kind) {
            case GITHUB -> buildSubjectPrefix("github", "?/?") + ".>";
            case GITLAB, SLACK, OUTLINE, KEYCLOAK_DIRECTORY, GITHUB_ACCESS ->
                throw new UnsupportedOperationException("Installation-aware subject filter not yet supported for kind="
                        + kind + " (only GITHUB publishes installation events today)");
        };
    }

    /**
     * Resolves the NATS stream name for an {@link IntegrationKind}; a null kind returns
     * {@link Optional#empty()} so callers can short-circuit on the path.
     */
    public static Optional<String> streamNameFor(@Nullable IntegrationKind kind) {
        if (kind == null) {
            return Optional.empty();
        }
        return switch (kind) {
            case GITHUB -> Optional.of("github");
            case GITLAB -> Optional.of("gitlab");
            case OUTLINE -> Optional.of("outline");
            case SLACK -> Optional.of("slack");
            case KEYCLOAK_DIRECTORY, GITHUB_ACCESS -> Optional.empty();
        };
    }

    /**
     * Wildcard subject filter matching every event for a single subscription-scoped stream. Format:
     * {@code <stream>.<subscriptionId>.>}. Used by the documentation family (Outline today), where a
     * workspace subscribes to exactly one change-notification subscription whose id is a dot-free UUID.
     */
    public static String subscriptionFilter(String streamName, String subscriptionId) {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("Stream name cannot be null or empty.");
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new IllegalArgumentException("Subscription id cannot be null or empty.");
        }
        return streamName + "." + subscriptionId.trim() + ".>";
    }

    /**
     * Wildcard subject filter that matches every event for one messaging-team tenant:
     * {@code <stream>.<team>.>}. Dots in the team id are replaced with {@code ~}, matching the
     * publish-side sanitisation of the messaging subject derivers so the filter and the published
     * subject always agree.
     *
     * @param streamName the NATS stream name (e.g. {@code slack})
     * @param teamId     the vendor team id from the workspace's connection config
     * @return a subject like {@code slack.T0ABC123.>}
     */
    public static String teamFilter(String streamName, String teamId) {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("Stream name cannot be null or empty.");
        }
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("Team id cannot be null or empty.");
        }
        return streamName + "." + teamId.trim().replace('.', '~') + ".>";
    }

    // Subject → kind

    /**
     * Explicit allow-list mapping of subject prefix → {@link IntegrationKind}. Returns
     * {@link Optional#empty()} for null, blank, dot-less, or unknown prefixes.
     */
    public static Optional<IntegrationKind> kindFromSubjectPrefix(@Nullable String fullSubject) {
        if (fullSubject == null || fullSubject.isBlank()) {
            return Optional.empty();
        }
        int firstDot = fullSubject.indexOf('.');
        if (firstDot <= 0) {
            return Optional.empty();
        }
        String prefix = fullSubject.substring(0, firstDot).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(PREFIX_TO_KIND.get(prefix));
    }

    /**
     * The {@code <base>-} every durable this deployment creates starts with. Both name builders below
     * are derived from it, so it is also what separates this deployment's durables from another's on a
     * shared broker.
     */
    public static String durablePrefix(String baseConsumerName) {
        if (baseConsumerName == null || baseConsumerName.isBlank()) {
            throw new IllegalArgumentException("Base consumer name cannot be null or blank.");
        }
        return baseConsumerName + "-";
    }

    /**
     * Builds the durable consumer name for a scope. Format: {@code <base>-scope-<scopeId>}.
     */
    public static String scopeConsumerName(@org.jspecify.annotations.Nullable String baseConsumerName, long scopeId) {
        if (baseConsumerName == null) {
            throw new IllegalArgumentException("baseConsumerName must not be null");
        }
        return durablePrefix(baseConsumerName) + "scope-" + scopeId;
    }

    /**
     * Builds the durable consumer name for the installation-wide consumer. Format:
     * {@code <base>-installation}.
     */
    public static String installationConsumerName(String baseConsumerName) {
        return durablePrefix(baseConsumerName) + "installation";
    }
}
