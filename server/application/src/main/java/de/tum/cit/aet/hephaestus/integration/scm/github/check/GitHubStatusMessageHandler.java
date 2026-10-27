package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRunsRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.dto.GitHubStatusEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Handles GitHub {@code status} (commit status) webhook events. */
@Component
public class GitHubStatusMessageHandler extends AbstractIntegrationMessageHandler<GitHubStatusEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubStatusMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubHeadCheckProcessor headCheckProcessor;
    private final PullRequestRepository pullRequestRepository;
    private final GitHubCommitCheckRuns checkRuns;
    private final HeadCheckRunsRecorder checkRunsRecorder;
    private final TransactionTemplate transactionTemplate;

    GitHubStatusMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubHeadCheckProcessor headCheckProcessor,
            PullRequestRepository pullRequestRepository,
            GitHubCommitCheckRuns checkRuns,
            HeadCheckRunsRecorder checkRunsRecorder,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.STATUS.getValue(),
                GitHubStatusEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.headCheckProcessor = headCheckProcessor;
        this.pullRequestRepository = pullRequestRepository;
        this.checkRuns = checkRuns;
        this.checkRunsRecorder = checkRunsRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The status is recorded in the short transaction. A status that ended has the commit's single runs read outside
     * it, when a pull request has that commit as its head: a commit status has no suite that completes.
     */
    @Override
    protected void dispatchEvent(GitHubStatusEventDTO event) {
        ProcessingContext context = transactionTemplate.execute(status -> handleEventAndReturnContext(event));
        String sha = event.sha();
        if (context == null || sha == null || headCheckState(event) == CheckState.PENDING) {
            return;
        }
        Repository repository = Objects.requireNonNull(context.repository());
        Long scopeId = context.scopeId();
        if (scopeId == null
                || pullRequestRepository
                        .findAllByRepository_IdAndHeadRefOid(repository.getId(), sha)
                        .isEmpty()) {
            return;
        }
        checkRuns
                .read(scopeId, repository.getNameWithOwner(), sha)
                .ifPresent(runs -> checkRunsRecorder.observe(repository.getId(), runs, context));
    }

    @Override
    protected void handleEvent(GitHubStatusEventDTO event) {
        handleEventAndReturnContext(event);
    }

    private @Nullable ProcessingContext handleEventAndReturnContext(GitHubStatusEventDTO event) {
        CheckState state = headCheckState(event);
        if (event.sha() == null || state == null) {
            log.warn("Received status event with missing data: state={}", event.state());
            return null;
        }
        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null || context.repository() == null) {
            return null;
        }
        headCheckProcessor.observe(event.sha(), state, context);
        return context;
    }

    private static @Nullable CheckState headCheckState(GitHubStatusEventDTO event) {
        return GitHubHeadCheckProcessor.fromStatus(event.state());
    }
}
