package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pipeline;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pipeline.dto.GitLabPipelineEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab Pipeline Hook events: the pipeline's status becomes the head-check state of the
 * merge request it names, or of every merge request whose head is the pipeline's commit. A pipeline
 * is GitLab's whole verdict on a commit, so it replaces the state outright, as the sync's head
 * pipeline does.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPipelineMessageHandler extends AbstractIntegrationMessageHandler<GitLabPipelineEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabPipelineMessageHandler.class);

    private final GitLabWebhookContextResolver contextResolver;
    private final PullRequestRepository pullRequestRepository;

    GitLabPipelineMessageHandler(
            GitLabWebhookContextResolver contextResolver,
            PullRequestRepository pullRequestRepository,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.PIPELINE.getValue(),
                GitLabPipelineEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextResolver = contextResolver;
        this.pullRequestRepository = pullRequestRepository;
    }

    @Override
    protected void handleEvent(GitLabPipelineEventDTO event) {
        var pipeline = event.objectAttributes();
        if (pipeline == null || pipeline.sha() == null || pipeline.status() == null) {
            log.warn("Received pipeline event with missing object_attributes");
            return;
        }
        if (event.project() == null || event.project().pathWithNamespace() == null) {
            log.warn("Received pipeline event with missing project data");
            return;
        }
        String projectPath = event.project().pathWithNamespace();
        ProcessingContext context = contextResolver.resolve(projectPath, pipeline.status(), "pipeline");
        if (context == null) {
            return;
        }
        long repositoryId = Objects.requireNonNull(context.repository()).getId();
        CheckState state = GitLabMergeRequestProcessor.mapPipelineStatus(pipeline.status());

        List<PullRequest> targets = event.mergeRequest() == null
                ? pullRequestRepository.findAllByRepository_IdAndHeadRefOid(repositoryId, pipeline.sha())
                : pullRequestRepository
                        .findByRepositoryIdAndNumber(
                                repositoryId, event.mergeRequest().iid())
                        .map(List::of)
                        .orElse(List.of());
        for (PullRequest pr : targets) {
            if (pr.observeHeadChecks(pipeline.sha(), state, true)) {
                pullRequestRepository.save(pr);
            }
        }
        log.debug(
                "Processed pipeline event: projectPath={}, sha={}, status={}, mergeRequests={}",
                sanitizeForLog(projectPath),
                pipeline.sha(),
                pipeline.status(),
                targets.size());
    }
}
