package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.data.domain.PageRequest;

/**
 * The gate on the whole feature: two consecutive sweeps over an artifact nobody touched submit exactly
 * one review.
 *
 * <p>A recurring sweep re-offers the same corpus every night, so nothing about it is safe unless
 * "already measured at this state" is decided by the database rather than by anything the sweep
 * remembers. {@code ReviewBackfillSignals} derives the signal from the artifact's <em>current</em> state,
 * so an unchanged artifact produces the identical {@code SignalKey} and {@code uq_artifact_signal} refuses
 * the second insert. Deliberately end to end against a real database: the constraint is the guard, and a
 * mocked recorder would assert only that this test knows what it wants the recorder to say.
 */
@DisplayName("Review sweep spend guard")
class ReviewSweepSpendGuardIntegrationTest extends BaseIntegrationTest {

    private static final long ACCOUNT_ID = 4242L;

    @Autowired
    private ReviewSweepCampaignOpener opener;

    @Autowired
    private ReviewSweepScheduleRepository scheduleRepository;

    private ReviewBackfillDriver driver;

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    @Autowired
    private ReviewBackfillRunRepository runRepository;

    @Autowired
    private ReviewBackfillScopeRepository scopeRepository;

    @Autowired
    private LlmBudgetService llmBudgetService;

    @Autowired
    private ReviewBackfillProperties backfillProperties;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private ArtifactSignalRepository signalRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceMembershipService workspaceMembershipService;

    @Autowired
    private de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository scmConnections;

    private Workspace workspace;
    private long pullRequestId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        ReviewBackfillSubmitter submitter = beanFactory.createBean(ReviewBackfillSubmitter.class);
        driver = new ReviewBackfillDriver(
                runRepository,
                scopeRepository,
                submitter,
                agentBindingRepository,
                llmBudgetService,
                backfillProperties);

        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaceRepository,
                scmConnections,
                WorkspaceTestFixtures.installationWorkspace(42L, "sweep-guard").withSlug("sweep-guard"),
                42L);
        workspace.setAccountLogin("sweeporg");
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);

        // An open, non-draft, unmerged pull request stands at "ready for review", so that is the signal
        // the sweep derives from its current state and the one a practice must be bound to.
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("sweep-guard-practice");
        practice.setName("Sweep guard practice");
        practice.setCriteria("Review the pull request");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_READY));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practiceRepository.save(practice);

        LlmConnection connection = llmConnectionRepository.save(LlmCatalogTestFixtures.connection("sweep-guard"));
        LlmModel model = llmModelRepository.save(
                LlmCatalogTestFixtures.model(connection, "sweep-guard-model", "gpt-sweep-guard"));
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setInstanceModel(model);
        binding.setTimeoutSeconds(300);
        agentBindingRepository.save(binding);

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        User author = userRepository.save(TestUserFactory.createUser(5001L, "sweep-author", provider));
        workspaceMembershipService.createMembership(
                workspace, author.getId(), WorkspaceMembership.WorkspaceRole.MEMBER);

        Repository repository = new Repository();
        repository.setNativeId(5101L);
        repository.setProvider(provider);
        repository.setName("sweep-repo");
        repository.setNameWithOwner("sweeporg/sweep-repo");
        repository.setHtmlUrl("https://github.com/sweeporg/sweep-repo");
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);

        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        repositoryToMonitorRepository.save(monitor);

        pullRequestId = persistPullRequest(provider, repository, author);
    }

    /**
     * The acceptance test for the feature: two consecutive nights whose windows overlap by design (the
     * lookback is twice the cadence), so the second sweep walks the same untouched pull request the first
     * one did — and must pay for nothing.
     */
    @Test
    void twoConsecutiveSweepsOverAnUnchangedArtifactSubmitExactlyOneReview() {
        createSchedule();

        assertThat(sweepOnce())
                .as("the first sweep pays for the review nothing had triggered")
                .isEqualTo(1);

        assertThat(sweepOnce())
                .as("the second sweep finds the occurrence already settled and buys nothing")
                .isEqualTo(1);

        // One ledger row, not two: the second sweep produced the identical key and lost the insert.
        List<ArtifactSignal> rows = signalRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSignalName()).isEqualTo(ScmSignals.PULL_REQUEST_READY.value());
        assertThat(rows.getFirst().getState()).isEqualTo(SignalState.TRIGGERED);
        // Both campaigns really did walk the artifact; the second one just walked past it.
        assertThat(runRepository.findAll())
                .hasSize(2)
                .allSatisfy(run -> assertThat(run.getEstimatedArtifacts()).isOne());
    }

    /**
     * The stamp that decides where the measurement is counted. A sweep is bounded to recent work, so it
     * measures the population events measure; recording it as BACKFILL would hide every finding from the
     * developer it is about and silence every channel but the profile.
     */
    @Test
    void aSweepsLedgerRowSaysASweepFoundItAndNotACampaign() {
        createSchedule();

        assertThat(sweepOnce()).isEqualTo(1);

        assertThat(signalRepository.findAll())
                .singleElement()
                .extracting(ArtifactSignal::getDiscoveredVia)
                .isEqualTo(DiscoveredVia.SWEEP);
        assertThat(runRepository.findAll())
                .singleElement()
                .extracting(ReviewBackfillRun::getDiscoveredVia)
                .isEqualTo(DiscoveredVia.SWEEP);
    }

    /**
     * One night: the schedule's turn opens a campaign, and the driver walks that campaign to the end.
     *
     * <p>The two units are called directly rather than through their {@code @Scheduled} entry points,
     * which carry a {@code @SchedulerLock} with a {@code lockAtLeastFor} floor — under it a second call
     * inside the same test would be skipped, and the test would pass by not running. Which schedules a
     * tick selects is a separate question, covered by {@code ReviewSweepSchedulerTest}.
     *
     * @return how many review jobs exist afterwards
     */
    private int sweepOnce() {
        ReviewSweepSchedule schedule = scheduleRepository.findAll().getFirst();
        assertThat(opener.openDueRun(schedule.getId(), Instant.now())).isEqualTo(ReviewSweepOutcome.OPENED);

        // Twice: the first pass offers the batch, the second finds an empty page and completes the run —
        // which is what frees the workspace's single campaign slot for the next night.
        driveActiveCampaigns();
        driveActiveCampaigns();
        return agentJobRepository.findAll().size();
    }

    /** Every campaign the driver would pick up on one tick, loaded the way the driver loads them. */
    private void driveActiveCampaigns() {
        for (ReviewBackfillRun run : runRepository.findByStatusIn(
                List.of(ReviewBackfillStatus.RUNNING, ReviewBackfillStatus.PAUSED), PageRequest.ofSize(5))) {
            driver.advance(run);
        }
    }

    private void createSchedule() {
        ReviewSweepSchedule schedule = new ReviewSweepSchedule();
        schedule.setWorkspace(workspace);
        schedule.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        schedule.setCadence(ReviewSweepCadence.DAILY);
        // Twice the cadence, so consecutive windows overlap.
        schedule.setLookbackDays(2);
        schedule.setEnabled(true);
        schedule.setNextRunAt(Instant.now().minus(Duration.ofMinutes(1)));
        schedule.setCreatedByAccountId(ACCOUNT_ID);
        scheduleRepository.save(schedule);
    }

    private long persistPullRequest(IdentityProvider provider, Repository repository, User author) {
        Instant now = Instant.now();
        Long providerId = java.util.Objects.requireNonNull(provider.getId());
        pullRequestRepository.upsertCore(
                5201L,
                providerId,
                12,
                "A change the webhook never announced",
                "Body",
                "OPEN",
                null,
                "https://github.com/sweeporg/sweep-repo/pull/12",
                false,
                null,
                0,
                now,
                now,
                now,
                author.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/sweep",
                "main",
                "sweepheadsha",
                "sweepbasesha",
                null,
                null);
        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), 12)
                .orElseThrow()
                .getId();
    }
}
