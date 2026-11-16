package de.tum.cit.aet.hephaestus.integration.scm.gitlab.manifest;

import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitLab integration manifest.
 *
 * <p>Feedback-delivery and inline-feedback capabilities are gated on
 * {@code hephaestus.integration.gitlab.enabled}, the same flag the GraphQL provider and channel beans require —
 * the bootstrap demands matching SPI beans for every declared capability. GitLab is opt-in (default off), but
 * the manifest bean stays registered either way so a workspace can be told that connecting GitLab would wake a
 * dormant practice.
 *
 * <p>No {@code RATE_LIMITED}: no per-kind {@code RateLimitTracker}.
 */
@Component
public class GitLabManifest implements IntegrationManifest {

    private final boolean gitlabStackEnabled;

    public GitLabManifest(@Value("${hephaestus.integration.gitlab.enabled:false}") boolean gitlabStackEnabled) {
        this.gitlabStackEnabled = gitlabStackEnabled;
    }

    @Override
    public boolean enabled() {
        return gitlabStackEnabled;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public String displayName() {
        return "GitLab";
    }

    @Override
    public Set<Capability> declaredCapabilities() {
        EnumSet<Capability> capabilities = EnumSet.of(Capability.WEBHOOK_INGEST);
        if (gitlabStackEnabled) {
            capabilities.add(Capability.FEEDBACK_DELIVERY);
            capabilities.add(Capability.INLINE_FEEDBACK);
        }
        return Set.copyOf(capabilities);
    }

    // Delivery declarations must share bean enablement; unavailable declared capabilities fail at boot.
    @Override
    public ReviewContribution reviewContribution() {
        return new ReviewContribution(
                Set.of(
                        ScmItemEvents.PULL_REQUEST,
                        ScmItemEvents.PULL_REQUEST_REVIEW,
                        ScmItemEvents.ISSUE,
                        ScmItemEvents.REPOSITORY,
                        ScmItemEvents.FILE),
                Map.of(
                        ScmItemEvents.PULL_REQUEST,
                        Set.of(
                                ScmItemEvents.PULL_REQUEST_OPENED,
                                ScmItemEvents.PULL_REQUEST_READY,
                                ScmItemEvents.PULL_REQUEST_SYNCHRONIZED,
                                ScmItemEvents.PULL_REQUEST_EDITED,
                                ScmItemEvents.PULL_REQUEST_LINKED_ISSUE_UPDATED,
                                ScmItemEvents.PULL_REQUEST_MERGED,
                                ScmItemEvents.PULL_REQUEST_CLOSED),
                        ScmItemEvents.ISSUE,
                        Set.of(ScmItemEvents.ISSUE_OPENED, ScmItemEvents.ISSUE_UPDATED, ScmItemEvents.ISSUE_CLOSED),
                        ScmItemEvents.PULL_REQUEST_REVIEW,
                        Set.of(ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED)),
                gitlabStackEnabled
                        ? Map.of(
                                ScmItemEvents.PULL_REQUEST,
                                Set.of(CopyCapability.SUMMARY, CopyCapability.LINE_NOTE),
                                ScmItemEvents.ISSUE,
                                Set.of(CopyCapability.SUMMARY),
                                ScmItemEvents.PULL_REQUEST_REVIEW,
                                Set.of(CopyCapability.SUMMARY, CopyCapability.LINE_NOTE))
                        : Map.of());
    }
}
