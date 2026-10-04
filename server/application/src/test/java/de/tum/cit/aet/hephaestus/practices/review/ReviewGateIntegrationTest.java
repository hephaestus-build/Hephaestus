package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class ReviewGateIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ReviewGate gate;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private WorkspaceLlmConnectionRepository workspaceLlmConnectionRepository;

    @Autowired
    private WorkspaceLlmModelRepository workspaceLlmModelRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipService workspaceMembershipService;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LabelRepository labelRepository;

    private Workspace workspace;
    private WorkspaceLlmModel workspaceModel;
    private Repository repo;
    private IdentityProvider provider;
    private User assignee;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        // Workspace with accountLogin = "org" so WorkspaceResolver heuristic matches "org/repo"
        workspace = WorkspaceTestFixtures.activeWorkspace("gate-test");
        workspace.setAccountLogin("org");
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);

        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setWorkspace(workspace);
        connection.setSlug("gate-connection");
        connection.setDisplayName("Gate connection");
        connection.setBaseUrl("https://api.openai.com");
        connection.setApiProtocol("openai-completions");
        connection.setEnabled(true);
        connection = workspaceLlmConnectionRepository.save(connection);

        workspaceModel = new WorkspaceLlmModel();
        workspaceModel.setWorkspace(workspace);
        workspaceModel.setConnection(connection);
        workspaceModel.setSlug("gate-model");
        workspaceModel.setDisplayName("Gate model");
        workspaceModel.setUpstreamModelId("gpt-5");
        workspaceModel.setEnabled(true);
        workspaceModel = workspaceLlmModelRepository.save(workspaceModel);

        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setWorkspaceModel(workspaceModel);
        binding.setTimeoutSeconds(300);
        agentBindingRepository.save(binding);

        provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        assignee = TestUserFactory.createUser(400L, "assignee-user", provider);
        assignee = userRepository.save(assignee);
        workspaceMembershipService.createMembership(
                workspace, assignee.getId(), WorkspaceMembership.WorkspaceRole.MEMBER);

        repo = new Repository();
        repo.setNativeId(3001L);
        repo.setProvider(provider);
        repo.setName("gate-repo");
        repo.setNameWithOwner("org/gate-repo");
        repo.setHtmlUrl("https://github.com/org/gate-repo");
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);
    }

    private Practice createPractice(String slug, String name, List<SignalName> signals, boolean active) {
        Practice p = new Practice();
        p.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        p.setWorkspace(workspace);
        p.setSlug(slug);
        p.setName(name);
        p.setCriteria("Test " + slug);
        p.setJudgment(PracticeJudgment.holistic());
        PracticeTestEvidence.configure(p, signals.toArray(SignalName[]::new));
        p.setAutonomy(active ? PracticeAutonomy.AUTOMATIC : PracticeAutonomy.OFF);
        return practiceRepository.save(p);
    }

    private PullRequest createPullRequest(boolean isDraft, Set<Label> labels, Set<User> assignees) {
        Instant now = Instant.now();
        Long providerId = provider.getId();
        assertNotNull(providerId);
        pullRequestRepository.upsertCore(
                7001L,
                providerId,
                100,
                "Gate Test PR",
                "Body",
                "OPEN",
                null,
                "https://github.com/org/gate-repo/pull/100",
                false, // isLocked
                null, // closedAt
                0, // commentsCount
                now, // lastSyncAt
                now, // createdAt
                now, // updatedAt
                assignee.getId(),
                repo.getId(),
                null, // milestoneId
                null, // mergedAt
                isDraft, // isDraft
                false, // isMerged
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/gate",
                "main",
                "gatesha",
                "basesha",
                null,
                null // mergeCommitSha
                );
        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(repo.getId(), 100)
                .orElseThrow();

        // Attach relationships that the gate reads (these are on the Issue superclass)
        pr.setLabels(labels != null ? labels : Set.of());
        pr.setAssignees(assignees != null ? assignees : Set.of());
        pr.setRepository(repo);
        return pr;
    }

    @Nested
    class PracticeMatching {

        @Test
        void matchesByTriggerEvent() {
            createPractice("pr-quality", "PR Quality", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            var detect = (GateDecision.Detect) decision;
            assertThat(detect.matchedPractices()).hasSize(1);
            assertThat(detect.matchedPractices().get(0).getSlug()).isEqualTo("pr-quality");
        }

        @Test
        void excludesInactive() {
            createPractice("active-one", "Active", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            createPractice("inactive-one", "Inactive", List.of(ScmSignals.PULL_REQUEST_OPENED), false);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            var detect = (GateDecision.Detect) decision;
            assertThat(detect.matchedPractices()).hasSize(1);
            assertThat(detect.matchedPractices().get(0).getSlug()).isEqualTo("active-one");
        }

        @Test
        void excludesMismatchedEvents() {
            createPractice("review-only", "Review Only", List.of(ScmSignals.PULL_REQUEST_REVIEWED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
        }

        @Test
        void matchesMultiple() {
            createPractice("practice-a", "Practice A", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            createPractice(
                    "practice-b",
                    "Practice B",
                    List.of(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_REVIEWED),
                    true);
            createPractice("practice-c", "Practice C", List.of(ScmSignals.PULL_REQUEST_REVIEWED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            var detect = (GateDecision.Detect) decision;
            assertThat(detect.matchedPractices()).hasSize(2);
            assertThat(detect.matchedPractices())
                    .extracting(Practice::getSlug)
                    .containsExactlyInAnyOrder("practice-a", "practice-b");
        }
    }

    @Nested
    class GateSkips {

        @Test
        void skipsNoBinding() {
            agentBindingRepository.deleteAll();
            createPractice("no-config", "No Config", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).contains("no runnable practice-review agent");
        }

        @Test
        void skipsUnavailableModelWithoutPoisoningTheReadTransaction() {
            workspaceModel.setEnabled(false);
            workspaceLlmModelRepository.saveAndFlush(workspaceModel);
            createPractice("unavailable-model", "Unavailable model", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).contains("no runnable practice-review agent");
        }

        @Test
        void skipsNoMatchingPractices() {
            createPractice("wrong-event", "Wrong Event", List.of(ScmSignals.PULL_REQUEST_REVIEWED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).contains("practices");
        }

        @Test
        void skipsDraftWhenThePracticeExcludesIt() {
            createPractice("draft-skip", "Draft Skip", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            PullRequest pr = createPullRequest(true, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
        }
    }

    @Nested
    class WorkspaceResolution {

        @Test
        void resolvesFromRepoOwner() {
            createPractice("resolved", "Resolved", List.of(ScmSignals.PULL_REQUEST_OPENED), true);
            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            var detect = (GateDecision.Detect) decision;
            assertThat(detect.workspace().getId()).isEqualTo(workspace.getId());
        }

        @Test
        void skipsUnresolvableWorkspace() {
            createPractice("orphan", "Orphan", List.of(ScmSignals.PULL_REQUEST_OPENED), true);

            Repository unknownRepo = new Repository();
            unknownRepo.setNativeId(3099L);
            unknownRepo.setProvider(provider);
            unknownRepo.setName("unknown-repo");
            unknownRepo.setNameWithOwner("unknown-org/unknown-repo");
            unknownRepo.setHtmlUrl("https://github.com/unknown-org/unknown-repo");
            unknownRepo.setDefaultBranch("main");
            unknownRepo = repositoryRepository.save(unknownRepo);

            PullRequest pr = createPullRequest(false, Set.of(), Set.of(assignee));
            pr.setRepository(unknownRepo);

            GateDecision decision = gate.evaluate(pr, ScmSignals.PULL_REQUEST_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).contains("workspace");
        }
    }
}
