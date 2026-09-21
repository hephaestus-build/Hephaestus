package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.dto.GitHubCheckSuiteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Handles GitHub {@code check_suite} webhook events. */
@Component
public class GitHubCheckSuiteMessageHandler extends AbstractIntegrationMessageHandler<GitHubCheckSuiteEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubCheckSuiteMessageHandler.class);

    private final ProcessingContextFactory contextFactory;
    private final GitHubHeadCheckProcessor headCheckProcessor;
    private final GitHubPullRequestSyncService syncService;
    private final PullRequestRepository pullRequestRepository;
    private final TransactionTemplate transactionTemplate;

    GitHubCheckSuiteMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubHeadCheckProcessor headCheckProcessor,
            GitHubPullRequestSyncService syncService,
            PullRequestRepository pullRequestRepository,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.CHECK_SUITE.getValue(),
                GitHubCheckSuiteEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.headCheckProcessor = headCheckProcessor;
        this.syncService = syncService;
        this.pullRequestRepository = pullRequestRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The suite's verdict is recorded in the short transaction; a completed suite then has every
     * pull request on that head read again outside it, so GitHub's own rollup over all suites
     * replaces the single suite's word within seconds.
     */
    @Override
    protected void dispatchEvent(GitHubCheckSuiteEventDTO event) {
        Observed observed = transactionTemplate.execute(status -> handleEventAndReturnObserved(event));
        if (observed == null || event.actionType() != GitHubEventAction.CheckSuite.COMPLETED) {
            return;
        }
        for (int number : observed.pullRequestNumbers()) {
            syncService.refreshPullRequest(observed.context().scopeId(), observed.repository(), number);
        }
    }

    @Override
    protected void handleEvent(GitHubCheckSuiteEventDTO event) {
        handleEventAndReturnObserved(event);
    }

    private record Observed(ProcessingContext context, Repository repository, List<Integer> pullRequestNumbers) {}

    private @Nullable Observed handleEventAndReturnObserved(GitHubCheckSuiteEventDTO event) {
        var suite = event.checkSuite();
        if (suite == null || suite.headSha() == null) {
            log.warn("Received check_suite event with missing data: action={}", event.action());
            return null;
        }
        CheckState state = GitHubHeadCheckProcessor.fromCheckSuite(suite.status(), suite.conclusion());
        if (state == null) {
            log.debug(
                    "Skipped check_suite event: reason=saysNothingAboutTheHead, action={}, conclusion={}",
                    event.action(),
                    suite.conclusion());
            return null;
        }
        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        Repository repository = context == null ? null : context.repository();
        if (context == null || repository == null) {
            return null;
        }
        headCheckProcessor.observe(suite.headSha(), state, context);
        List<Integer> numbers =
                pullRequestRepository.findAllByRepository_IdAndHeadRefOid(repository.getId(), suite.headSha()).stream()
                        .map(PullRequest::getNumber)
                        .toList();
        return new Observed(context, repository, numbers);
    }
}
