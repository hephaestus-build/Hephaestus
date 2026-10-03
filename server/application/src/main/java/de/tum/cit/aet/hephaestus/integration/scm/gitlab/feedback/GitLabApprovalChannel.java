package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApprovalChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * GitLab adapter for {@link ApprovalChannel}: approves a merge request through the REST
 * {@code POST /projects/:id/merge_requests/:iid/approve} endpoint.
 *
 * <p>REST, not GraphQL, because GitLab's schema defines no approval mutation.
 *
 * <p>Gated on {@code hephaestus.integration.gitlab.enabled=true} to track {@link GitLabGraphQlClientProvider}.
 */
@Component
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabApprovalChannel implements ApprovalChannel {

    private static final Logger log = LoggerFactory.getLogger(GitLabApprovalChannel.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitLabTokenService tokenService;
    private final GitLabSummaryChannel feedbackChannel;
    private final WebClient webClient;
    private final OutboundEgressGuard egressGuard;

    public GitLabApprovalChannel(
            GitLabGraphQlClientProvider gitLabProvider,
            GitLabTokenService tokenService,
            GitLabSummaryChannel feedbackChannel,
            WebClient.Builder webClientBuilder,
            OutboundEgressGuard egressGuard) {
        this.gitLabProvider = gitLabProvider;
        this.tokenService = tokenService;
        this.feedbackChannel = feedbackChannel;
        this.webClient = webClientBuilder.build();
        this.egressGuard = egressGuard;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public void approve(SummaryChannel.FeedbackTarget target, @Nullable String message) {
        long scopeId = target.ref().workspaceId();
        if (gitLabProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("GitLab rate limit critical — skipping approval for scope " + scopeId);
        }

        MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(target.subjectExternalId());
        try {
            egressGuard.requireDeliveryAllowed("gitlab.approve");
            webClient
                    .post()
                    .uri(approvalUri(tokenService.resolveServerUrl(scopeId), mr))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken(scopeId))
                    .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId)
                    .retrieve()
                    .toBodilessEntity()
                    .block(REQUEST_TIMEOUT);
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (WebClientResponseException e) {
            // GitLab answers 401, not 403, when the token's user may not approve this MR — usually its author.
            throw new FeedbackDeliveryException(
                    "GitLab approve failed for " + mr.projectPath()
                            + "!"
                            + mr.iid()
                            + ": HTTP "
                            + e.getStatusCode()
                            + (e.getStatusCode().value() == 401
                                    ? " (the token's user may not approve this merge request)"
                                    : ""),
                    e);
        } catch (RuntimeException e) {
            throw new FeedbackDeliveryException("GitLab approve transport error: " + e.getMessage(), e);
        }
        log.info("Approved GitLab MR: workspaceId={}, mr={}!{}", scopeId, mr.projectPath(), mr.iid());

        // GitLab's approve endpoint accepts no body — post the message as a separate note.
        if (message != null && !message.isBlank()) {
            feedbackChannel.postSummary(target, new SummaryChannel.FeedbackContent(message, ""));
        }
    }

    /**
     * A ready-made {@link URI} so no template expansion re-encodes the {@code %2F} separators GitLab requires
     * in a project's full path — a double-encoded {@code %252F} resolves to no project at all. Namespace and
     * project paths are restricted to unreserved characters, so escaping the separators is the whole encoding.
     */
    private static URI approvalUri(String serverUrl, MrCoordinates mr) {
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return URI.create(base + "/api/v4/projects/"
                + mr.projectPath().replace("/", "%2F")
                + "/merge_requests/"
                + mr.iid()
                + "/approve");
    }
}
