package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class AgentJobSubmissionIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AgentJobService agentJobService;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository scmConnections;

    private Workspace workspace;
    private WorkspaceAgentBinding agentBinding;
    private Long prId;
    private Repository repo;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaceRepository,
                scmConnections,
                WorkspaceTestFixtures.installationWorkspace(42L, "submit-test").withSlug("submit-test"),
                42L);
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);

        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("submit-test");
        practice.setName("Submit test");
        practice.setCriteria("Review the pull request");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practiceRepository.save(practice);

        LlmConnection connection = llmConnectionRepository.save(LlmCatalogTestFixtures.connection("submit-test"));
        LlmModel model =
                llmModelRepository.save(LlmCatalogTestFixtures.model(connection, "submit-model", "gpt-submit-test"));

        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setInstanceModel(model);
        binding.setTimeoutSeconds(300);
        agentBinding = agentBindingRepository.save(binding);

        // Seed git entities needed for the submission request
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        User author = TestUserFactory.createUser(300L, "pr-author", provider);
        author = userRepository.save(author);

        repo = new Repository();
        repo.setNativeId(2001L);
        repo.setProvider(provider);
        repo.setName("submit-repo");
        repo.setNameWithOwner("org/submit-repo");
        repo.setHtmlUrl("https://github.com/org/submit-repo");
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);

        Instant now = Instant.now();
        Long providerId = provider.getId();
        org.junit.jupiter.api.Assertions.assertNotNull(providerId);
        pullRequestRepository.upsertCore(
                6001L,
                providerId,
                10,
                "Submit Test PR",
                "Body",
                "OPEN",
                null,
                "https://github.com/org/submit-repo/pull/10",
                false,
                null,
                0,
                now,
                now,
                now,
                author.getId(),
                repo.getId(),
                null,
                null,
                false,
                false,
                1,
                5,
                3,
                2,
                null,
                null,
                null,
                "feature/submit",
                "main",
                "sha1abc",
                "sha1base",
                null,
                null // mergeCommitSha
                );
        prId = pullRequestRepository
                .findByRepositoryIdAndNumber(repo.getId(), 10)
                .orElseThrow()
                .getId();
    }

    private PullRequestReviewSubmissionRequest createRequest(String commitSha) {
        String defaultBranch = repo.getDefaultBranch();
        org.junit.jupiter.api.Assertions.assertNotNull(defaultBranch);
        RepositoryRef repoRef = new RepositoryRef(repo.getId(), repo.getNameWithOwner(), defaultBranch);
        ScmEventPayload.PullRequestData prData = new ScmEventPayload.PullRequestData(
                prId,
                10,
                "Submit Test PR",
                "Body",
                PullRequest.State.OPEN,
                false,
                false,
                5,
                3,
                2,
                repo.getHtmlUrl() + "/pull/10",
                repoRef,
                null,
                null,
                null,
                null,
                null,
                null);
        return new PullRequestReviewSubmissionRequest(
                prData, "feature/submit", commitSha, defaultBranch, "a".repeat(40));
    }

    @Nested
    class HappyPath {

        @Test
        void submitsJobWithCorrectState() {
            var request = createRequest("abc123");

            Optional<AgentJob> result =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);

            assertThat(result).isPresent();
            AgentJob job = result.get();
            assertThat(job.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(job.getJobType()).isEqualTo(AgentJobType.PULL_REQUEST_REVIEW);
            assertThat(job.getIdempotencyKey()).isEqualTo("pr_review:org/submit-repo:10:manual:abc123:detection");
            assertThat(job.getPurpose()).isEqualTo(AgentPurpose.PRACTICE_REVIEW);
            assertThat(job.getConfigSnapshot()).isNotNull();
            var metadata = job.getMetadata();
            org.junit.jupiter.api.Assertions.assertNotNull(metadata);
            assertThat(metadata.get("pull_request_id").asLong()).isEqualTo(prId);
            assertThat(metadata.get("pr_number").asInt()).isEqualTo(10);
            assertThat(metadata.get("commit_sha").asString()).isEqualTo("abc123");

            AgentJob fromDb = agentJobRepository.findById(job.getId()).orElseThrow();
            assertThat(fromDb.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
        }
    }

    @Nested
    class Idempotency {

        @Test
        void deduplicatesSameCommitSha() {
            var request = createRequest("dedup123");

            Optional<AgentJob> first =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);
            Optional<AgentJob> second =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(second.get().getId()).isEqualTo(first.get().getId());

            assertThat(agentJobRepository.findAll()).hasSize(1);
        }

        @Test
        void allowsDifferentCommitSha() {
            var request1 = createRequest("sha_v1");
            var request2 = createRequest("sha_v2");

            Optional<AgentJob> first =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request1, null);
            Optional<AgentJob> second =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request2, null);

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(second.get().getId()).isNotEqualTo(first.get().getId());

            assertThat(agentJobRepository.findAll()).hasSize(2);
        }

        @Test
        void deduplicatesAgainstPreExistingJob() {
            // Pre-insert a QUEUED job with the same idempotency key via raw repository,
            // simulating a job created by a concurrent request before this one.
            AgentJob existing = new AgentJob();
            existing.setWorkspace(workspace);
            existing.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            existing.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            existing.setIdempotencyKey(
                    // per-phase key: must carry the "manual" phase to dedup against a manual submission
                    "pr_review:" + repo.getNameWithOwner() + ":10:manual:race123:detection");
            existing.setMetadata(OBJECT_MAPPER.createObjectNode());
            existing.setConfigSnapshot(OBJECT_MAPPER.createObjectNode());
            agentJobRepository.saveAndFlush(existing);

            var request = createRequest("race123");
            Optional<AgentJob> result =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(existing.getId());
        }
    }

    @Nested
    class NoBinding {

        @Test
        void returnsEmptyWhenNoBinding() {
            agentBindingRepository.deleteAll();

            var request = createRequest("noconfig123");
            Optional<AgentJob> result =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);

            assertThat(result).isEmpty();
        }

        @Test
        void returnsEmptyWhenDisabled() {
            agentBinding.setEnabled(false);
            agentBindingRepository.save(agentBinding);

            var request = createRequest("disabled123");
            Optional<AgentJob> result =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class ConfigSnapshot {

        @Test
        void capturesSnapshotAtSubmitTime() {
            var request = createRequest("snapshot123");

            Optional<AgentJob> result =
                    agentJobService.submit(workspace.getId(), AgentJobType.PULL_REQUEST_REVIEW, request, null);

            assertThat(result).isPresent();
            var snapshot = result.get().getConfigSnapshot();
            assertThat(snapshot.get("timeoutSeconds").asInt()).isEqualTo(300);

            agentBinding.setTimeoutSeconds(900);
            agentBindingRepository.save(agentBinding);

            AgentJob fromDb = agentJobRepository.findById(result.get().getId()).orElseThrow();
            assertThat(fromDb.getConfigSnapshot().get("timeoutSeconds").asInt()).isEqualTo(300);
        }
    }
}
