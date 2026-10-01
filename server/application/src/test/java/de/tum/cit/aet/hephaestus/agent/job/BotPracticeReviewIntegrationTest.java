package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewDetectionGate;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class BotPracticeReviewIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private PracticeReviewDetectionGate gate;

    @Autowired
    private SignalRecorder recorder;

    @Autowired
    private WorkspaceResolver resolver;

    @Autowired
    private AgentJobService jobs;

    @Autowired
    private IntegrationManifestRegistry manifests;

    @Autowired
    private TransactionTemplate transactions;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRefuseBotReviewerWhoIsAWorkspaceMemberOnLiveAndRetryPaths(boolean gitLab) {
        var provider = gitLab ? ensureGitLabProvider() : ensureGitHubProvider();
        User owner = persistUser("owner");
        var workspace = createWorkspace("bot-review", "Bot review", "owner", AccountType.ORG, owner);
        workspace.getFeatures().setPracticesEnabled(true);
        workspaces.saveAndFlush(workspace);
        User bot = persistUser("review-bot");
        bot.setProvider(provider);
        bot.setType(User.Type.BOT);
        bot = userRepository.saveAndFlush(bot);
        ensureWorkspaceMembership(workspace, bot, WorkspaceRole.MEMBER);
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), bot.getId()))
                .isPresent();

        Repository repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(4001L);
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl((gitLab ? "https://gitlab.com/" : "https://github.com/") + "owner/repo");
        repository.setDefaultBranch("main");
        repository = repositories.saveAndFlush(repository);
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner("owner/repo");
        monitors.saveAndFlush(monitor);
        PullRequest pr = new PullRequest();
        pr.setProvider(provider);
        pr.setNativeId(7101L);
        pr.setRepository(repository);
        pr.setAuthor(owner);
        pr.setNumber(1);
        pr.setTitle("Human work");
        pr.setState(Issue.State.OPEN);
        pr.setHtmlUrl(repository.getHtmlUrl() + "/pull/1");
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        pr.setHeadRefName("feature");
        pr.setHeadRefOid("a".repeat(40));
        pr.setBaseRefName("main");
        pr.setBaseRefOid("b".repeat(40));
        pr = pullRequests.saveAndFlush(pr);
        PullRequestReview review = new PullRequestReview();
        review.setProvider(provider);
        review.setNativeId(8101L);
        review.setPullRequest(pr);
        review.setAuthor(bot);
        review.setBody("LGTM");
        review.setState(PullRequestReview.State.APPROVED);
        review.setHtmlUrl(pr.getHtmlUrl() + "#review");
        review.setSubmittedAt(Instant.now());
        review.setCreatedAt(Instant.now());
        review.setUpdatedAt(Instant.now());
        review = reviews.saveAndFlush(review);
        var payload = ScmEventPayload.ReviewData.from(review).orElseThrow();
        assertThat(payload.humanAuthor()).isFalse();
        var listener = new AgentJobEventListener(jobs, pullRequests, gate, resolver, recorder, manifests);
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                null,
                DataSource.WEBHOOK,
                "review",
                UUID.randomUUID().toString(),
                null);
        transactions.executeWithoutResult(
                status -> listener.onReviewSubmitted(new ScmDomainEvent.ReviewSubmitted(payload, context)));
        var row = signals.findForArtifact(workspace.getId(), ScmSignals.PULL_REQUEST.value(), pr.getId()).stream()
                .filter(signal -> signal.getSignalName().equals(ScmSignals.PULL_REQUEST_REVIEWED.value()))
                .findFirst()
                .orElseThrow();
        assertThat(row.getStateReason()).isEqualTo(SignalStateReason.BOT_REVIEWER);
        assertThat(row.getJobId()).isNull();
        var retry = new PullRequestSignalResubmitter(jobs, pullRequests, gate, recorder, reviews, manifests);
        transactions.executeWithoutResult(status -> retry.resubmit(row));
        var after = signals.findById(row.getId()).orElseThrow();
        assertThat(after.getStateReason()).isEqualTo(SignalStateReason.BOT_REVIEWER);
        assertThat(after.getJobId()).isNull();

        pr.setAuthor(bot);
        pr = pullRequests.saveAndFlush(pr);
        long botPrId = pr.getId();
        var authorDecision = transactions.execute(status -> gate.evaluateAdministrative(
                pullRequests.findByIdWithAllForGate(botPrId).orElseThrow(), ScmSignals.PULL_REQUEST_OPENED));
        assertThat(authorDecision)
                .isInstanceOfSatisfying(
                        GateDecision.Skip.class,
                        skip -> assertThat(skip.resolvedSignalReason()).isEqualTo(SignalStateReason.BOT_AUTHOR));
    }
}
