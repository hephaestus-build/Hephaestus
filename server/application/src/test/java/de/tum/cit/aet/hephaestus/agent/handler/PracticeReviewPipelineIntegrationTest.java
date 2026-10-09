package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryListener;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppCompositionListener;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettings;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState.Available;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitHubAppConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.OutlineConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor.DiffAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Placement;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentProjector;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@TestPropertySource(
        properties = {
            "hephaestus.integration.slack.enabled=true",
            "hephaestus.integration.slack.signing-secret=test-signing-secret-not-real",
            "hephaestus.integration.outline.enabled=true",
            "hephaestus.integration.outline.allowed-origins=https://wiki.example.com"
        })
class PracticeReviewPipelineIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** The pinned range of the reviewed change; nothing here quotes it, so no checkout is staged. */
    private static final String BASE_SHA = "a".repeat(40);

    private static final String HEAD_SHA = "b".repeat(40);

    /** A valid review that says nothing on the work: the composer's choice, not a missing review. */
    private static final String SILENT_REVIEW = "{}";

    /** The summary a review of {@code validAgentOutput()} writes about both of its observations. */
    private static final String BOTH_OBSERVATIONS_SUMMARY =
            "The description says what changed. The new method reads its input before it checks it for null.";

    @Autowired
    private JobTypeHandlerRegistry handlerRegistry;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private JobEvidenceFiles evidenceFiles;

    @Autowired
    private FabricLayout evidenceLayout;

    private final List<UUID> preparedJobIds = new ArrayList<>();
    private final Map<String, byte[]> capturedFiles = new LinkedHashMap<>();
    private final List<PreparedJobInputs> preparedEvidence = new ArrayList<>();

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository workspaceAgentBindingRepository;

    @Autowired
    private LlmModelResolver llmModelResolver;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private WorkspaceMembershipService workspaceMembershipService;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackDeliveryService feedbackDeliveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private PullRequestCommentPoster commentPoster;

    @Autowired
    private DiffNotePoster diffNotePoster;

    @Autowired
    private PracticeFeedbackCommentFormatter commentFormatter;

    @Autowired
    private AccountPreferencesQuery accountPreferencesQuery;

    private JobTypeHandler handler;
    private Workspace workspace;
    private AgentJob agentJob;
    private Long prId;
    private Repository repository;
    private User developer;

    @AfterEach
    void resetHandlerDoubles() {
        reset(commentPoster, diffNotePoster, accountPreferencesQuery);
    }

    @AfterEach
    void releasePreparedEvidence() throws Exception {
        preparedEvidence.forEach(PreparedJobInputs::close);
        preparedEvidence.clear();
        for (var jobId : preparedJobIds) {
            FileUtils.deleteDirectory(evidenceLayout
                    .jobsRoot()
                    .resolve(workspace.getId().toString())
                    .resolve(jobId.toString())
                    .toFile());
        }
        preparedJobIds.clear();
    }

    @BeforeEach
    void setUp() {
        reset(commentPoster, diffNotePoster, accountPreferencesQuery);
        databaseTestUtils.cleanDatabase();
        releaseSilentMode();
        AgentHandlerTestDoubles.resolveSummaryWrites(commentPoster);
        when(commentPoster.findExisting(any())).thenReturn(ExistingDeliveryLookup.absent());

        workspace = WorkspaceTestFixtures.activeWorkspace("pipeline-test");
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);

        var scm = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "1732",
                new GitHubAppConfig(1732L, workspace.getAccountLogin(), null, Set.of()));
        ReflectionTestUtils.setField(scm, "state", IntegrationState.ACTIVE);
        connections.save(scm);

        Practice description = createPractice("pr-description-quality", "PR Description Quality");
        Practice errors = createPractice("error-handling", "Error Handling");

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        developer = userRepository.save(TestUserFactory.createUser(500L, "pipeline-author", provider));
        workspaceMembershipService.createMembership(
                workspace, developer.getId(), WorkspaceMembership.WorkspaceRole.MEMBER);

        Repository repo = new Repository();
        repo.setNativeId(4001L);
        repo.setProvider(provider);
        repo.setName("pipeline-repo");
        repo.setNameWithOwner("org/pipeline-repo");
        repo.setHtmlUrl("https://github.com/org/pipeline-repo");
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);
        repository = repo;
        repositoryToMonitorRepository.save(WorkspaceTestFixtures.repositoryMonitor(workspace, repo.getNameWithOwner()));

        Instant now = Instant.now();
        Long providerId = Objects.requireNonNull(provider.getId());
        pullRequestRepository.upsertCore(
                8001L,
                providerId,
                50,
                "Pipeline Test PR",
                "Test body",
                "OPEN",
                null,
                "https://github.com/org/pipeline-repo/pull/50",
                false,
                null,
                0,
                now,
                now,
                now,
                developer.getId(),
                repo.getId(),
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
                "feature/pipeline",
                "main",
                "pipelinesha",
                "basesha",
                null,
                null // mergeCommitSha
                );
        prId = pullRequestRepository
                .findByRepositoryIdAndNumber(repo.getId(), 50)
                .orElseThrow()
                .getId();

        agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setWorkerId("test-worker");
        agentJob.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        agentJob.setStatus(AgentJobStatus.RUNNING);
        agentJob.setConfigSnapshot(AdmittedReviewJobFixtures.snapshot(
                workspace,
                llmConnectionRepository,
                llmModelRepository,
                workspaceAgentBindingRepository,
                llmModelResolver,
                OBJECT_MAPPER));

        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.put("pull_request_id", prId);
        metadata.put("repository_id", repo.getId());
        metadata.put("repository_full_name", "org/pipeline-repo");
        metadata.put("pr_number", 50);
        metadata.put("pr_url", "https://github.com/org/pipeline-repo/pull/50");
        metadata.put("commit_sha", "pipelinesha");
        metadata.put("source_branch", "feature/pipeline");
        metadata.put("target_branch", "main");
        metadata.put("title", "Pipeline Test PR");
        metadata.put("body", "Test body");
        agentJob.setMetadata(metadata);
        agentJob.setEvidenceSnapshot(evidenceSnapshot(description, errors));
        agentJob = agentJobRepository.save(agentJob);
        preparedEvidence.add(PreparedJobInputsFixtures.prepare(
                evidenceFiles, agentJob, PreparedJobInputsFixtures.filesOnly(capturedFiles)));
        agentJob.setStatus(AgentJobStatus.COMPLETED);
        agentJob = agentJobRepository.saveAndFlush(agentJob);
        preparedJobIds.add(agentJob.getId());

        handler = handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW);
        stubInlinePackageDelivered();
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(anyLong())).thenReturn(true);
    }

    /** The provider accounts for every line note of a package without returning a handle to record. */
    private void stubInlinePackageDelivered() {
        when(diffNotePoster.deliverPackage(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(List.of(), true, false, false, false, false, List.of()));
    }

    @Autowired
    private WorkspaceContextBuilder folderBuilder;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private OutlineDocumentRepository outlineDocuments;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ObservationAdmissionService admissionService;

    @MockitoSpyBean
    private OutlineDocumentProjector documentProjection;

    @Test
    void jobFolderVerifiesCrossSourceQuotesAndIncludesFreshlyCommittedPullRequest() throws Exception {
        setJobOutput(validAgentOutput());
        var provider = Objects.requireNonNull(repository.getProvider());
        var other = new Repository();
        other.setProvider(provider);
        other.setNativeId(4002L);
        other.setName("other-repo");
        other.setNameWithOwner("org/other-repo");
        other.setHtmlUrl("https://github.com/org/other-repo");
        other.setDefaultBranch("main");
        other = repositoryRepository.save(other);
        repositoryToMonitorRepository.save(
                WorkspaceTestFixtures.repositoryMonitor(workspace, other.getNameWithOwner()));
        long otherId = Objects.requireNonNull(other.getId());
        var committedPullRequest = new AtomicLong();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Instant now = Instant.now();
            pullRequestRepository.upsertCore(
                    8002L,
                    Objects.requireNonNull(provider.getId()),
                    51,
                    "Just committed",
                    "Fresh pull request body",
                    "OPEN",
                    null,
                    "https://github.com/org/other-repo/pull/51",
                    false,
                    null,
                    0,
                    now,
                    now,
                    now,
                    developer.getId(),
                    otherId,
                    null,
                    null,
                    false,
                    false,
                    1,
                    1,
                    0,
                    1,
                    null,
                    null,
                    null,
                    "feature/fresh",
                    "main",
                    HEAD_SHA,
                    BASE_SHA,
                    null,
                    null);
            long freshId = Objects.requireNonNull(pullRequestRepository
                    .findByRepositoryIdAndNumber(otherId, 51)
                    .orElseThrow()
                    .getId());
            committedPullRequest.set(freshId);
            jdbcTemplate.update(
                    "INSERT INTO issue_comment (native_id,provider_id,issue_id,body,html_url,author_association,created_at,updated_at) VALUES (?,?,?,?,?,'MEMBER',now(),now())",
                    1732001L,
                    provider.getId(),
                    prId,
                    "Other repository comment",
                    "https://github.com/" + repository.getNameWithOwner() + "/pull/50#issuecomment-1732001");
        });
        var slack = new SlackConversationTestSupport(jdbcTemplate);
        slack.seedChannel(workspace.getId(), "C1732", "ACTIVE");
        slack.seedMessage(workspace.getId(), "C1732", "1704067200.000001", null, "Slack source quote");
        var outline = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "https://wiki.example.com",
                new OutlineConfig("https://wiki.example.com", null, null, Set.of()));
        ReflectionTestUtils.setField(outline, "state", IntegrationState.ACTIVE);
        outline = connections.save(outline);
        var document = new OutlineDocument();
        document.setWorkspaceId(workspace.getId());
        document.setConnectionId(outline.getId());
        document.setDocumentId(UUID.randomUUID().toString());
        document.setCollectionId(UUID.randomUUID().toString());
        document.setCollectionSlug("engineering");
        document.setSlug("outside-the-old-cap");
        document.setTitle("Design");
        document.setBodyMarkdown("Outline paragraph quote");
        document.setLastMaterializedAt(Instant.now());
        outlineDocuments.save(document);
        for (int n = 0; n < 20; n++) {
            var extra = new OutlineDocument();
            extra.setWorkspaceId(workspace.getId());
            extra.setConnectionId(outline.getId());
            extra.setDocumentId(UUID.randomUUID().toString());
            extra.setCollectionId(document.getCollectionId());
            extra.setCollectionSlug("engineering");
            extra.setSlug("page-" + n);
            extra.setBodyMarkdown("Permitted page " + n);
            extra.setLastMaterializedAt(Instant.now());
            outlineDocuments.save(extra);
        }
        var practice = createPractice("folder-context", "Workspace context");
        practice.setSignals(List.of(ScmSignals.PULL_REQUEST_OPENED));
        practice.setEvidenceRequirements(
                List.of("scm.pull-request.core", "outline.documents", "workspace.project-inventory").stream()
                        .map(kind -> new PracticeEvidenceRequirement(new SourceKind(kind), EvidenceStance.REQUIRED))
                        .toList());
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 2)));
        practice = practiceRepository.saveAndFlush(practice);
        var next = new AgentJob();
        next.setWorkspace(workspace);
        next.setWorkerId("test-worker");
        next.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        next.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        next.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        next.setStatus(AgentJobStatus.QUEUED);
        next.setConfigSnapshot(agentJob.getConfigSnapshot());
        var nextMetadata =
                (ObjectNode) Objects.requireNonNull(agentJob.getMetadata()).deepCopy();
        nextMetadata.remove(ObservationAdmissionService.DIGEST_METADATA_KEY);
        nextMetadata.put("pull_request_id", committedPullRequest.get());
        nextMetadata
                .put("repository_id", otherId)
                .put("repository_full_name", "org/other-repo")
                .put("pr_number", 51)
                .put("pr_url", "https://github.com/org/other-repo/pull/51")
                .put("commit_sha", HEAD_SHA)
                .put("source_branch", "feature/fresh")
                .put("title", "Just committed")
                .put("body", "Fresh pull request body");
        next.setMetadata(nextMetadata);
        next = agentJobRepository.save(next);
        UUID claimId = next.getId();
        next = Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            var claimed = agentJobRepository
                    .findByIdQueuedForUpdateSkipLocked(claimId, Instant.now())
                    .orElseThrow();
            claimed.setStatus(AgentJobStatus.RUNNING);
            claimed.setWorkerId("test-worker");
            return agentJobRepository.saveAndFlush(claimed);
        }));
        doAnswer(call -> {
                    assertThat(jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class))
                            .isEqualTo("repeatable read");
                    assertThat(jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class))
                            .isEqualTo("on");
                    CompletableFuture.runAsync(() -> slack.seedMessage(
                                    workspace.getId(),
                                    "C1732",
                                    "1704067201.000001",
                                    null,
                                    "Committed during rendering"))
                            .join();
                    return call.callRealMethod();
                })
                .when(documentProjection)
                .documentsForWorkspace(workspace.getId());
        JobFolderIndex index;
        var snapshot = OBJECT_MAPPER.createObjectNode();
        PreparedJobInputs prepared;
        evidenceFiles.beginPersonCapture(next);
        try (var raw = folderBuilder.prepare(
                new ContextRequest.PracticeReviewRequest(next), EvidencePlan.compile(List.of(practice)))) {
            index = Objects.requireNonNull(raw.manifest());
            assertThat(folderBuilder
                            .prepareAutomatedReviewReadiness(
                                    index, List.of(practice), next.getCreatedAt(), raw.files(), null)
                            .readyPractices())
                    .containsExactly(practice);

            for (String workspaceSource : List.of("outline.documents", "workspace.project-inventory")) {
                assertThat(index.sources())
                        .filteredOn(source -> source.kind().value().equals(workspaceSource))
                        .singleElement()
                        .satisfies(source -> assertThat(source.state()).isInstanceOf(Available.class));
            }

            assertThat(index.artifacts().stream()
                            .filter(a -> a.artifact().path().startsWith("context/docs/")))
                    .hasSize(21);

            snapshot.set("manifest", OBJECT_MAPPER.valueToTree(index));
            snapshot.putArray("practices")
                    .addObject()
                    .put("slug", practice.getSlug())
                    .put(
                            "revisionId",
                            Objects.requireNonNull(practice.getCurrentRevision().getId()));
            next.setEvidenceSnapshot(snapshot);
            next = agentJobRepository.save(next);
            prepared = evidenceFiles.prepare(
                    next,
                    new PreparedEvidence(raw.files(), raw.filesOnDisk(), raw.cleanups(), null, raw.directories()),
                    null);
        } finally {
            evidenceFiles.abortPersonCapture(next);
        }
        preparedEvidence.add(prepared);
        preparedJobIds.add(next.getId());
        assertThat(Files.readString(prepared.filesOnDisk().get("context/scm/reviewed/pulls/51/record.json")))
                .contains("Just committed", "synced_at");
        var comment = OBJECT_MAPPER.readTree(Files.readAllLines(
                        prepared.filesOnDisk().get("context/scm/" + repository.getId() + "/pulls/50/comments.jsonl"))
                .getFirst());
        assertThat(comment.has("synced_at")).isTrue();
        assertThat(comment.path("synced_at").isNull()).isTrue();
        assertThat(Files.readString(prepared.filesOnDisk().get("INDEX.md")))
                .contains("context/chat/", "context/docs/", "context/people/");
        assertThat(Files.readString(prepared.filesOnDisk().get("context/chat/C1732/2024-01.jsonl")))
                .doesNotContain("Committed during rendering");
        var output = OBJECT_MAPPER.createObjectNode();
        var observation = output.putArray("observations").addObject();
        observation
                .put("practiceSlug", practice.getSlug())
                .put("summary", "Cross-source context")
                .put("outcome", "MET")
                .put("evidenceRationale", "Four independently captured sources support the review.")
                .putNull("severity");
        var citations = observation.putObject("evidence").putArray("citations");
        Map<String, String> quotes = Map.of(
                "context/scm/" + repository.getId() + "/pulls/50/comments.jsonl",
                "Other repository comment",
                "context/chat/C1732/2024-01.jsonl",
                "Slack source quote",
                "context/docs/engineering/outside-the-old-cap.md",
                "Outline paragraph quote",
                "context/people/" + developer.getId() + "/observations.jsonl",
                "Good PR description");
        for (var quote : quotes.entrySet()) {
            var lines = Files.readAllLines(prepared.filesOnDisk().get(quote.getKey()));
            int line = IntStream.range(0, lines.size())
                            .filter(n -> lines.get(n).contains(quote.getValue()))
                            .findFirst()
                            .orElseThrow()
                    + 1;
            var artifact = index.artifacts().stream()
                    .filter(a -> a.artifact().path().equals(quote.getKey()))
                    .findFirst()
                    .orElseThrow();
            citations
                    .addObject()
                    .put("sourceKind", artifact.kind().value())
                    .put("path", quote.getKey())
                    .put("artifactPath", quote.getKey())
                    .put("startLine", line)
                    .put("endLine", line)
                    .put("quote", quote.getValue());
        }
        assertThat(agentJobRepository.discardRetiredArtifactInventory(
                        next.getId(), workspace.getId(), 0, "test-worker"))
                .isZero();
        admissionService.admit(
                new ObservationAdmissionService.AdmissionIdentity(next.getId(), workspace.getId(), 0, "test-worker"),
                output.path("observations"));
        assertThat(prepared.filesOnDisk().get("INDEX.md")).doesNotExist();
        var admitted = observationRepository.findByAgentJobId(next.getId(), workspace.getId());
        assertThat(admitted).hasSize(1);
        assertThat(admitted.getFirst().getEvidence().path("citations")).hasSize(4);
        var receipt = agentJobRepository.findById(next.getId()).orElseThrow().getEvidenceSnapshot();
        assertThat(receipt.path("manifest").path("artifacts")).isEmpty();
        for (var source : receipt.path("manifest").path("sources"))
            assertThat(source.path("artifacts")).isEmpty();
        assertThat(receipt.path("manifest").path("contractVersion").asString()).isEqualTo("1.3.0");
        assertThat(receipt.path("manifest").path("refusals")).isEmpty();
        var ended = new AgentJob();
        ended.setWorkspace(workspace);
        ended.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        ended.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        ended.setConfigSnapshot(next.getConfigSnapshot());
        ended.setEvidenceSnapshot(snapshot.deepCopy());
        ended.setWorkerId("ended-worker");
        ended.setStatus(AgentJobStatus.FAILED);
        ended = agentJobRepository.saveAndFlush(ended);
        assertThat(agentJobRepository.discardRetiredArtifactInventory(
                        ended.getId(), workspace.getId(), 1, "ended-worker"))
                .isZero();
        assertThat(agentJobRepository.discardRetiredArtifactInventory(
                        ended.getId(), workspace.getId(), 0, "another-worker"))
                .isZero();
        assertThat(agentJobRepository
                        .findById(ended.getId())
                        .orElseThrow()
                        .getEvidenceSnapshot()
                        .path("manifest")
                        .path("artifacts"))
                .isNotEmpty();
        assertThat(agentJobRepository.discardRetiredArtifactInventory(
                        ended.getId(), workspace.getId(), 0, "ended-worker"))
                .isOne();
        assertThat(agentJobRepository
                        .findById(ended.getId())
                        .orElseThrow()
                        .getEvidenceSnapshot()
                        .path("manifest")
                        .path("artifacts"))
                .isEmpty();
    }

    @Test
    void shouldWithholdRequiredCoreReadinessWhenAdmittedWordsAreUnknownDespiteKnownMirrorWords() throws Exception {
        var practice = createPractice("retained-core", "Retain the admitted description");
        practice.setEvidenceRequirements(
                List.of(new PracticeEvidenceRequirement(PullRequestContentSource.CORE, EvidenceStance.REQUIRED)));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);
        practice = practiceRepository.saveAndFlush(practice);
        var unknown = new AgentJob();
        unknown.setWorkspace(workspace);
        unknown.setWorkerId("test-worker");
        unknown.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        unknown.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        unknown.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        unknown.setStatus(AgentJobStatus.RUNNING);
        unknown.setConfigSnapshot(agentJob.getConfigSnapshot());
        var metadata =
                (ObjectNode) Objects.requireNonNull(agentJob.getMetadata()).deepCopy();
        metadata.remove(List.of("title", "body", ObservationAdmissionService.DIGEST_METADATA_KEY));
        unknown.setMetadata(metadata);
        unknown = agentJobRepository.saveAndFlush(unknown);

        evidenceFiles.beginPersonCapture(unknown);
        try (var raw = folderBuilder.prepare(
                new ContextRequest.PracticeReviewRequest(unknown), EvidencePlan.compile(List.of(practice)))) {
            var index = Objects.requireNonNull(raw.manifest());
            assertThat(folderBuilder
                            .prepareAutomatedReviewReadiness(
                                    index, List.of(practice), unknown.getCreatedAt(), raw.files(), null)
                            .readyPractices())
                    .isEmpty();
            var core = index.sources().stream()
                    .filter(source -> source.kind().equals(PullRequestContentSource.CORE))
                    .findFirst()
                    .orElseThrow();
            assertThat(core.state()).isInstanceOfSatisfying(Available.class, available -> {
                assertThat(available.completeness()).isEqualTo(SourceCompleteness.PARTIAL);
                assertThat(available.limitations())
                        .containsExactlyInAnyOrder("RETAINED_TITLE_UNKNOWN", "RETAINED_BODY_UNKNOWN");
            });
            var captured = OBJECT_MAPPER.readTree(raw.files().get("context/metadata.json"));
            assertThat(captured.has("title")).isFalse();
            assertThat(captured.has("body")).isFalse();
            assertThat(raw.files()).doesNotContainKey(PullRequestContentSource.DESCRIPTION_FILE);
        } finally {
            evidenceFiles.abortPersonCapture(unknown);
        }
    }

    private Practice createPractice(String slug, String name) {
        Practice p = new Practice();
        p.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        p.setWorkspace(workspace);
        p.setAutonomy(PracticeAutonomy.AUTOMATIC);
        p.setSlug(slug);
        p.setName(name);
        p.setCriteria("Test " + slug);
        PracticeTestEvidence.configure(p, ScmSignals.PULL_REQUEST_OPENED);
        p = practiceRepository.saveAndFlush(p);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(p, 1));
        p.setCurrentRevision(revision);
        return practiceRepository.saveAndFlush(p);
    }

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private InAppCompositionListener inAppLane;

    @Autowired
    private ConversationalDeliveryListener conversationLane;

    @Test
    void shouldPrepareBothPrivateChannelsWhenTheIssueCommentIsSuppressedAsClosed() {
        Practice practice = createPractice("record-issue-outcome", "Record the issue outcome");
        PracticeTestEvidence.configure(practice, ArtifactKinds.ISSUE);
        practice = practiceRepository.saveAndFlush(practice);
        AgentJob current = agentJob;
        UUID currentObservation = UUID.randomUUID();
        for (int number = 71; number <= 73; number++) {
            Issue issue = new Issue();
            issue.setProvider(repository.getProvider());
            issue.setNativeId(9000L + number);
            issue.setNumber(number);
            issue.setTitle("Export " + number);
            issue.setBody("Record the exported format.");
            issue.setRepository(repository);
            issue.setAuthor(developer);
            issue.setState(Issue.State.CLOSED);
            issue.setCreatedAt(Instant.now().minusSeconds(3600));
            issue.setUpdatedAt(Instant.now());
            issue.setHtmlUrl("https://github.com/org/pipeline-repo/issues/" + number);
            issue = issueRepository.saveAndFlush(issue);
            AgentJob run = new AgentJob();
            run.setWorkspace(workspace);
            run.setJobType(AgentJobType.ISSUE_REVIEW);
            run.setArtifactKind(ArtifactKinds.ISSUE);
            run.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            run.setIntegrationKind(IntegrationKind.GITHUB);
            run.setStatus(AgentJobStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
            run.setConfigSnapshot(agentJob.getConfigSnapshot());
            run.setEvidenceSnapshot(EvidenceSnapshotFixtures.snapshot(OBJECT_MAPPER, ArtifactKinds.ISSUE.value()));
            run.setMetadata(OBJECT_MAPPER
                    .createObjectNode()
                    .put("issue_id", issue.getId())
                    .put("issue_number", number)
                    .put("state", "closed")
                    .put(ObservationAdmissionService.DIGEST_METADATA_KEY, "private-lifecycle-digest")
                    .put("repository_id", repository.getId())
                    .put("repository_full_name", repository.getNameWithOwner()));
            run = agentJobRepository.saveAndFlush(run);
            UUID observationId = UUID.randomUUID();
            observationRepository.insertIfAbsent(
                    observationId,
                    "occ-" + observationId,
                    run.getId(),
                    workspace.getId(),
                    practice.getId(),
                    practice.getCurrentRevision().getId(),
                    ArtifactKinds.ISSUE.value(),
                    issue.getId(),
                    developer.getId(),
                    "The issue has no recorded outcome",
                    "NOT_MET",
                    "MINOR",
                    AdmittedObservationFixtures.evidence(run.getId(), "scm.issue.core")
                            .toString(),
                    "The closed issue does not record the exported format.",
                    null,
                    Instant.now(),
                    "LIVE");
            current = run;
            currentObservation = observationId;
        }
        current.setOutput(OBJECT_MAPPER.readTree("""
                {"feedback":{"admissionDigest":"private-lifecycle-digest","observations":[{"id":"%s","practiceSlug":"%s","outcome":"NOT_MET"}],
                "units":[
                  {"channel":"IN_APP","action":"NEW","practiceSlug":"%s","basedOn":["%s"],
                   "title":"Record the outcome","body":"Three closed issues omit the exported format.",
                   "nextStep":"Name the exported format when closing the next issue."},
                  {"channel":"IN_CHAT","action":"NEW","practiceSlug":"%s","basedOn":["%s"],
                   "title":"Record the outcome","notes":{
                    "situation":"Closed issues omit the exported format.",
                    "capability":"Check that the outcome is named.",
                    "evidenceSummary":"Three closed issues omit the format.",
                    "inConversationSignal":"When planning the next export."}}],
                "contractVersion":2,"review":{"summary":{"body":"Record the exported format.","basedOn":["%s"]},
                "inline":[],"withheld":[]}}}
                """.formatted(
                        currentObservation,
                        practice.getSlug(),
                        practice.getSlug(),
                        currentObservation,
                        practice.getSlug(),
                        currentObservation,
                        currentObservation)));
        current = agentJobRepository.saveAndFlush(current);
        UUID jobId = current.getId();
        handlerRegistry.getHandler(AgentJobType.ISSUE_REVIEW).deliver(current);
        await().untilAsserted(() -> {
            AgentJob routed = agentJobRepository.findById(jobId).orElseThrow();
            assertThat(routed.getInAppPreparedAt()).isNotNull();
            assertThat(routed.getInChatPreparedAt()).isNotNull();
            assertThat(feedbackRepository.findAll().stream()
                            .filter(row -> jobId.equals(row.getAgentJobId()))
                            .toList())
                    .extracting(Feedback::getChannel, Feedback::getDeliveryState)
                    .containsExactlyInAnyOrder(
                            tuple(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.SUPPRESSED),
                            tuple(FeedbackChannel.IN_APP, FeedbackDeliveryState.PREPARED),
                            tuple(FeedbackChannel.IN_CHAT, FeedbackDeliveryState.PREPARED));
        });
        assertThat(feedbackRepository.findAll().stream()
                        .filter(row ->
                                jobId.equals(row.getAgentJobId()) && row.getChannel() == FeedbackChannel.IN_CONTEXT)
                        .toList())
                .singleElement()
                .satisfies(row ->
                        assertThat(row.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.ARTIFACT_CLOSED));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM feedback_placement p
                JOIN feedback f ON f.id = p.feedback_id WHERE f.agent_job_id = ?
                """, Integer.class, jobId)).isZero();
        assertThat(conversationLane.prepare(jobId, workspace.getId())).isZero();
        inAppLane.prepare(jobId, workspace.getId());
        assertThat(feedbackRepository.findAll().stream()
                        .filter(row -> jobId.equals(row.getAgentJobId()))
                        .toList())
                .hasSize(3);
    }

    private void setJobOutput(String rawOutput) {
        agentJob = admitAndSetOutput(agentJob, rawOutput);
    }

    /** The admission fence's own two steps, minus the ownership transaction it wraps them in. */
    private void admit(AgentJob job, JsonNode observations) {
        var pullRequests = (PullRequestReviewHandler) handler;
        pullRequests.prepareObservations(job, observations).record(job);
    }

    /** Admits the observations and leaves a composition that has written nothing yet; {@code compose} writes it. */
    private AgentJob admitAndSetOutput(AgentJob job, String rawOutput) {
        JsonNode observations = OBJECT_MAPPER.readTree(withEvidence(rawOutput)).path("observations");
        admit(job, observations);
        String digest = "test-admission-digest";
        JsonNode jobMetadata = job.getMetadata();
        assertThat(jobMetadata).isNotNull();
        ObjectNode metadata = (ObjectNode) jobMetadata.deepCopy();
        metadata.put(ObservationAdmissionService.DIGEST_METADATA_KEY, digest);
        job.setMetadata(metadata);
        ObjectNode output = OBJECT_MAPPER.createObjectNode();
        output.putObject("feedback").put("admissionDigest", digest).putArray("units");
        job.setOutput(output);
        var rows = observationRepository.findByAgentJobId(job.getId(), workspace.getId());
        String review = rows.stream().anyMatch(o -> o.getOutcome() == Outcome.NOT_MET)
                ? summaryOn(
                        BOTH_OBSERVATIONS_SUMMARY,
                        rows.stream()
                                .filter(o -> o.getOutcome().isDecided())
                                .map(o -> o.getPractice().getSlug())
                                .toArray(String[]::new))
                : SILENT_REVIEW;
        compose(job, review);
        return agentJobRepository.save(job);
    }

    private void releaseSilentMode() {
        var current = instanceSettingsService.get();
        instanceSettingsService.updateSilentMode(false, null, null, version(current));
    }

    private void engageSilentMode() {
        var current = instanceSettingsService.get();
        instanceSettingsService.updateSilentMode(true, "pipeline safety test", null, version(current));
    }

    private static EntityTagPrecondition version(InstanceSettings settings) {
        return EntityTagPrecondition.parse("\"" + settings.getVersion() + "\"");
    }

    private AgentJob newJobWithOutput(String rawOutput) {
        AgentJob next = new AgentJob();
        next.setWorkspace(workspace);
        next.setWorkerId("test-worker");
        next.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        next.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        next.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        next.setStatus(AgentJobStatus.RUNNING);
        next.setConfigSnapshot(agentJob.getConfigSnapshot());
        JsonNode metadata = agentJob.getMetadata();
        assertThat(metadata).isNotNull();
        next.setMetadata(metadata.deepCopy());
        next.setEvidenceSnapshot(agentJob.getEvidenceSnapshot().deepCopy());
        next = agentJobRepository.save(next);
        preparedEvidence.add(PreparedJobInputsFixtures.prepare(
                evidenceFiles, next, PreparedJobInputsFixtures.filesOnly(capturedFiles)));
        next.setStatus(AgentJobStatus.COMPLETED);
        next = agentJobRepository.saveAndFlush(next);
        preparedJobIds.add(next.getId());
        return admitAndSetOutput(next, rawOutput);
    }

    private ObjectNode evidenceSnapshot(Practice... practices) {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(OBJECT_MAPPER);
        addArtifact(
                snapshot,
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.core", null),
                "context/metadata.json",
                "{\"body\":\"Test body\"}");
        addArtifact(
                snapshot,
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.diff", BASE_SHA + ":" + HEAD_SHA),
                PullRequestContentSource.CHANGE_FILE,
                "{\"base_sha\":\"" + BASE_SHA + "\",\"head_sha\":\"" + HEAD_SHA + "\"}");
        for (Practice practice : practices) {
            EvidenceSnapshotFixtures.admittedPractice(
                    snapshot,
                    practice.getSlug(),
                    Objects.requireNonNull(practice.getCurrentRevision().getId()));
        }
        return snapshot;
    }

    private void addArtifact(ObjectNode snapshot, ObjectNode source, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        capturedFiles.put(path, bytes);
        EvidenceSnapshotFixtures.artifact(snapshot, source, path, ProvenanceDigest.sha256Hex(bytes))
                .put("mediaType", "application/json")
                .put("bytes", bytes.length);
    }

    private String withEvidence(String rawOutput) {
        try {
            var root = OBJECT_MAPPER.readTree(rawOutput);
            for (var observation : root.path("observations")) {
                if (!observation.has("evidence")) {
                    var citation = ((ObjectNode) observation)
                            .putObject("evidence")
                            .putArray("citations")
                            .addObject();
                    citation.put("sourceKind", "scm.pull-request.core");
                    citation.put("artifactPath", "context/metadata.json");
                    citation.put("path", "body");
                    citation.put("startLine", 1);
                    citation.put("endLine", 1);
                    citation.put("quote", "Test body");
                }
            }
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (RuntimeException ignored) {
            return rawOutput;
        }
    }

    private String validAgentOutput() {
        String observations = """
            {
              "observations": [
                {
                  "practiceSlug": "pr-description-quality",
                  "summary": "Good PR description",
                  "outcome": "MET",
                  "severity": null,
                  "evidenceRationale": "The description names what changed."
                },
                {
                  "practiceSlug": "error-handling",
                  "summary": "Missing null check",
                  "outcome": "NOT_MET",
                  "severity": "MAJOR",
                  "evidenceRationale": "The method does not check for null input."
                }
              ]""";
        return observations + "\n}";
    }

    /**
     * The composer's output as the runner writes it: the job's admitted observations echoed under their persisted
     * ids, the review written whole for the work, and the private units. A {@code {practice-slug}} placeholder in
     * the review or a unit names the observation of that practice.
     */
    private void compose(AgentJob job, String review, String... units) {
        ObjectNode output = (ObjectNode) Objects.requireNonNull(job.getOutput());
        ObjectNode feedback = (ObjectNode) output.get("feedback");
        feedback.put("contractVersion", ComposedReview.CONTRACT_VERSION);
        var staged = feedback.putArray("observations");
        String written = "[" + String.join(",", units) + "]";
        String said = review;
        for (Observation observation : observationRepository.findByAgentJobId(job.getId(), workspace.getId())) {
            String slug = observation.getPractice().getSlug();
            String id = observation.getId().toString();
            staged.addObject()
                    .put("id", id)
                    .put("practiceSlug", slug)
                    .put("outcome", observation.getOutcome().name())
                    .put("anchorable", false)
                    .putArray("citations");
            written = written.replace("{" + slug + "}", id);
            said = said.replace("{" + slug + "}", id);
        }
        feedback.set("units", OBJECT_MAPPER.readTree(written));
        feedback.set("review", OBJECT_MAPPER.readTree(said));
        agentJobRepository.save(job);
    }

    /** A review whose one text is a summary resting on the observations of these practices. */
    private static String summaryOn(String body, String... practiceSlugs) {
        ObjectNode review = OBJECT_MAPPER.createObjectNode();
        ObjectNode summary = review.putObject("summary").put("body", body);
        var basedOn = summary.putArray("basedOn");
        for (String slug : practiceSlugs) {
            basedOn.add("{" + slug + "}");
        }
        return OBJECT_MAPPER.writeValueAsString(review);
    }

    @Nested
    class PartialDelivery {

        private static final String FOUR_OBSERVATIONS = """
                {"observations": [
                  {"practiceSlug": "pr-description-quality", "summary": "The description never says why",
                   "outcome": "NOT_MET", "severity": "MINOR",
                   "evidenceRationale": "The body lists what changed only."},
                  {"practiceSlug": "error-handling", "summary": "Missing null check",
                   "outcome": "NOT_MET", "severity": "MAJOR",
                   "evidenceRationale": "The method does not check for null input."},
                  {"practiceSlug": "parser-input-validation", "summary": "Second unchecked input",
                   "outcome": "NOT_MET", "severity": "MINOR",
                   "evidenceRationale": "The parser does not check its input either."},
                  {"practiceSlug": "issue-linking", "summary": "The description names the issue",
                   "outcome": "MET", "severity": null,
                   "evidenceRationale": "The body closes the issue."}
                ]}""";

        @Test
        void aLineNoteThatNeverLandsIsRecordedFailedAndNotBehindTheDeliveredComment() {
            Practice parser = createPractice("parser-input-validation", "Parser input validation");
            Practice linking = createPractice("issue-linking", "Issue linking");
            ObjectNode snapshot = (ObjectNode) Objects.requireNonNull(agentJob.getEvidenceSnapshot());
            EvidenceSnapshotFixtures.admittedPractice(
                    snapshot,
                    parser.getSlug(),
                    Objects.requireNonNull(
                            Objects.requireNonNull(parser.getCurrentRevision()).getId()));
            EvidenceSnapshotFixtures.admittedPractice(
                    snapshot,
                    linking.getSlug(),
                    Objects.requireNonNull(
                            Objects.requireNonNull(linking.getCurrentRevision()).getId()));
            agentJob.setEvidenceSnapshot(snapshot);
            agentJob = agentJobRepository.saveAndFlush(agentJob);
            setJobOutput(FOUR_OBSERVATIONS);
            List<Observation> rows = observationRepository.findByAgentJobId(agentJob.getId(), workspace.getId());
            Observation summarised = row(rows, "The description never says why");
            Observation landing = row(rows, "Missing null check");
            Observation failing = row(rows, "Second unchecked input");
            Observation supporting = row(rows, "The description names the issue");
            var inlineA = onLine(landing, 3);
            var inlineB = onLine(failing, 9);
            String failingNote = "The parser reads this input before it checks it; check it first, as on line 3.";
            var review = new ComposedReview(
                    new ComposedReview.Summary(
                            "The description lists what changed but never says why.",
                            List.of(summarised.getId().toString())),
                    List.of(
                            new ComposedReview.InlineNote(
                                    "Null reaches the handler here. Guard the input before the lookup.",
                                    List.of(
                                            landing.getId().toString(),
                                            supporting.getId().toString()),
                                    new ComposedReview.ResolvedAnchor(
                                            landing.getId().toString(), 0, "src/App.java", "NEW", 3, null)),
                            new ComposedReview.InlineNote(
                                    failingNote,
                                    List.of(failing.getId().toString()),
                                    new ComposedReview.ResolvedAnchor(
                                            failing.getId().toString(), 0, "src/App.java", "NEW", 9, null))),
                    List.of());
            List<ValidatedObservation> admitted =
                    List.of(validated(summarised, null), inlineA, inlineB, validated(supporting, null));
            var decided = AdmittedDelivery.decide(
                    review,
                    ArtifactKinds.PULL_REQUEST,
                    admitted,
                    PullRequestReviewHandler.subjectsOf(
                            rows, rows.stream().map(Observation::getId).collect(Collectors.toSet())),
                    List.of(),
                    admitted);
            if (!(decided instanceof AdmittedDelivery.Automatic automatic)) {
                throw new AssertionError("Every observation is automatic, so nothing waits for approval");
            }
            DeliveryContent content = Objects.requireNonNull(automatic.content());
            assertThat(content.diffNotes()).hasSize(2);
            String landingKey = "observation:" + landing.getOccurrenceKey() + ":0";
            String failingKey = "observation:" + failing.getOccurrenceKey() + ":0";
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("summary-ref"));
            when(diffNotePoster.deliverPackage(eq(agentJob), any(), any(), any(), any(), any()))
                    .thenReturn(
                            incomplete(
                                    signal(landingKey, 3, Disposition.POSTED),
                                    signal(failingKey, 9, Disposition.FAILED)),
                            incomplete(
                                    signal(landingKey, 3, Disposition.PRESERVED_EXISTING),
                                    signal(failingKey, 9, Disposition.FAILED)));

            assertThatThrownBy(() -> feedbackDeliveryService.deliverFeedback(
                            agentJob, content, automatic.contributingPracticeSlugs()))
                    .isInstanceOf(JobDeliveryException.class);
            jdbcTemplate.update(
                    "UPDATE feedback_dispatch SET attempt_count = ?, next_attempt_at = CURRENT_TIMESTAMP"
                            + " WHERE destination_key = ?",
                    PracticeFeedbackDispatchService.MAX_ATTEMPTS - 1,
                    "review:" + agentJob.getId());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                try {
                    feedbackDeliveryService.recoverAutomaticPackageIfPresent(agentJob);
                } catch (JobDeliveryException stillFailing) {
                    // A package that ends FAILED still reports itself unreconciled; its ledger is what counts here.
                }
                assertThat(stateOf(FeedbackDeliveryState.FAILED)).isNotEmpty();
            });
            try {
                feedbackDeliveryService.recoverAutomaticPackageIfPresent(agentJob);
            } catch (JobDeliveryException stillFailing) {
                // Replaying a terminal package must neither write to the provider nor record it again.
            }

            verify(commentPoster, times(1)).post(any());
            verify(diffNotePoster, times(2)).deliverPackage(eq(agentJob), any(), any(), any(), any(), any());
            assertThat(stateOf(FeedbackDeliveryState.DELIVERED))
                    .as("the summary, the note that landed and what that note cites; never the note that failed")
                    .containsExactlyInAnyOrder(summarised.getId(), landing.getId(), supporting.getId());
            assertThat(stateOf(FeedbackDeliveryState.FAILED)).containsExactly(failing.getId());
            assertThat(jdbcTemplate.queryForList(
                            "SELECT body FROM feedback WHERE agent_job_id = ? AND delivery_state = 'FAILED'",
                            String.class,
                            agentJob.getId()))
                    .as("the note that never landed is kept as the package sealed it for the pull request")
                    .containsExactly(commentFormatter.appendInlineFeedbackPrompt(failingNote, agentJob));
            assertThat(jdbcTemplate.queryForList("""
                            SELECT fp.posted_comment_ref FROM feedback_placement fp
                            JOIN feedback f ON f.id = fp.feedback_id
                            WHERE f.agent_job_id = ? AND f.delivery_state = 'DELIVERED'
                            """, String.class, agentJob.getId()))
                    .containsExactlyInAnyOrder("summary-ref", "note-" + landingKey);
        }

        private static Observation row(List<Observation> rows, String summary) {
            return rows.stream()
                    .filter(o -> summary.equals(o.getSummary()))
                    .findFirst()
                    .orElseThrow();
        }

        /** The observation as a delivery handler stamps it; {@code evidence} replaces what admission stored. */
        private ValidatedObservation validated(Observation row, @Nullable JsonNode evidence) {
            return new ValidatedObservation(
                    row.getPractice().getSlug(),
                    row.getSummary(),
                    row.getOutcome(),
                    row.getSeverity(),
                    evidence == null ? row.getEvidence() : evidence,
                    row.getEvidenceRationale(),
                    new ObservationKeys(row.getOccurrenceKey(), row.getRecurrenceKey(), row.getId()));
        }

        /** The observation cited on a verified line of the change, so the composer places a line note there. */
        private ValidatedObservation onLine(Observation row, int line) {
            ObjectNode evidence = OBJECT_MAPPER.createObjectNode();
            ObjectNode citation = evidence.putArray("citations").addObject();
            citation.put("sourceKind", "scm.pull-request.diff");
            citation.put("artifactPath", "context/diff.patch");
            citation.put("path", "src/App.java");
            citation.put("side", "NEW");
            citation.put("startLine", line);
            citation.put("quote", "return input;");
            CitationVerification.record(
                    citation, agentJob, "a".repeat(64), CitationVerification.quoteDigest("return input;"));
            return validated(row, evidence);
        }

        /** A line comment that landed under {@code disposition}, or for {@code FAILED} one provably never requested. */
        private static DeliveredSignal signal(String key, int line, Disposition disposition) {
            DiffAnchor anchor = new DiffAnchor("src/App.java", line, null);
            return disposition == Disposition.FAILED
                    ? DeliveredSignal.notSent(key, anchor)
                    : new DeliveredSignal(
                            key, anchor, disposition, "note-" + key, "discussion-" + key, null, null, Placement.LINE);
        }

        private static DiffNotePoster.DiffNoteResult incomplete(DeliveredSignal... signals) {
            return new DiffNotePoster.DiffNoteResult(List.of(signals), false, false, false, false, false, List.of());
        }

        /** The observations bound to this job's feedback recorded in {@code state}. */
        private List<@Nullable UUID> stateOf(FeedbackDeliveryState state) {
            return jdbcTemplate.queryForList("""
                    SELECT fo.observation_id FROM feedback_observation fo
                    JOIN feedback f ON f.id = fo.feedback_id WHERE f.agent_job_id = ? AND f.delivery_state = ?
                    """, UUID.class, agentJob.getId(), state.name());
        }
    }

    @Nested
    class HappyPath {

        @Test
        void shouldPersistWithoutExternalWritesOrReplayWhenSilentModeIsEngaged() {
            setJobOutput(validAgentOutput());
            engageSilentMode();

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(2);
            assertThat(feedbackRepository.findAll())
                    .singleElement()
                    .extracting(Feedback::getDeliveryState, Feedback::getSuppressionReason)
                    .containsExactly(FeedbackDeliveryState.SUPPRESSED, FeedbackSuppressionReason.INSTANCE_SILENCED);
            verify(commentPoster, never()).post(any());
            verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());

            releaseSilentMode();
            assertThat(feedbackRepository.findAll())
                    .noneMatch(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.PREPARED);

            AgentJob newEvent = newJobWithOutput(validAgentOutput());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-after-release"));
            handler.deliver(newEvent);

            verify(commentPoster).post(argThat(write -> write.job().equals(newEvent)));
            assertThat(observationRepository.findAll()).hasSize(4);
            assertThat(feedbackRepository.findAll())
                    .extracting(Feedback::getDeliveryState)
                    .containsExactlyInAnyOrder(FeedbackDeliveryState.SUPPRESSED, FeedbackDeliveryState.DELIVERED);
        }

        @Test
        void fullPipelineFromParseToDelivery() {
            setJobOutput(validAgentOutput());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-123"));

            handler.deliver(agentJob);

            List<Observation> observations = observationRepository.findAll();
            assertThat(observations).hasSize(2);
            assertThat(observations)
                    .extracting(Observation::getOutcome)
                    .containsExactlyInAnyOrder(Outcome.MET, Outcome.NOT_MET);

            verify(commentPoster).post(argThat(write -> write.job().equals(agentJob)));
            // A package with no line notes writes nothing inline, so it never touches what earlier packages placed.
            verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());

            // AgentJobExecutor persists deliveryStatus, not handler.deliver(), so it stays null here.
            assertThat(agentJob.getDeliveryCommentId()).isEqualTo("comment-123");
            assertThat(agentJob.getDeliveryStatus()).isNull();
        }

        @Test
        void allPositiveObservationsStayQuietWhenTheReviewDidNotReachEveryPractice() {
            String output = """
                {
                  "observations": [
                    {
                      "practiceSlug": "pr-description-quality",
                      "summary": "Good description",
                      "outcome": "MET",
                      "severity": null,
                      "evidenceRationale": "The description explains the change."
                    }
                  ]
                }""";
            agentJob = admitAndSetOutput(agentJob, output);

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(1);
            verify(commentPoster, never()).post(any());
        }

        @Test
        void aPartialReviewStillReportsTheProblemItFound() {
            agentJob = admitAndSetOutput(agentJob, validAgentOutput());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-partial"));

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(2);
            verify(commentPoster).post(argThat(write -> write.job().equals(agentJob)));
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void unknownSlugRejectsDeliveryAtomically() {
            String output = """
                {
                  "observations": [
                    {
                      "practiceSlug": "pr-description-quality",
                      "summary": "Good description",
                      "outcome": "MET",
                      "severity": null,
                      "evidenceRationale": "The description explains the change."
                    },
                    {
                      "practiceSlug": "nonexistent-practice",
                      "summary": "Unknown practice",
                      "outcome": "MET",
                      "severity": null,
                      "evidenceRationale": "The submitted practice does not exist."
                    },
                    {
                      "practiceSlug": "error-handling",
                      "summary": "Good handling",
                      "outcome": "NOT_MET",
                      "severity": "MINOR",
                      "evidenceRationale": "The implementation omits the required check."
                    }
                  ]
                }""";
            JsonNode submitted = OBJECT_MAPPER.readTree(withEvidence(output)).path("observations");

            assertThatThrownBy(() -> admit(agentJob, submitted))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("practice not admitted to the job");

            assertThat(observationRepository.findAll()).isEmpty();
            verify(commentPoster, never()).post(any());
            verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());
        }

        @Test
        void closedPrSkipsDelivery() {
            var pr = pullRequestRepository.findById(prId).orElseThrow();
            var provider = Objects.requireNonNull(pr.getProvider());
            var author = Objects.requireNonNull(pr.getAuthor());
            var createdAt = Objects.requireNonNull(pr.getCreatedAt());
            pullRequestRepository.upsertCore(
                    8001L,
                    Objects.requireNonNull(provider.getId()),
                    50,
                    "Pipeline Test PR",
                    "Test body",
                    "CLOSED",
                    null,
                    "https://github.com/org/pipeline-repo/pull/50",
                    false,
                    null,
                    0,
                    createdAt,
                    Instant.now(),
                    createdAt,
                    author.getId(),
                    pr.requireRepository().getId(),
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
                    "feature/pipeline",
                    "main",
                    "pipelinesha",
                    "basesha",
                    null,
                    null // mergeCommitSha
                    );

            setJobOutput(validAgentOutput());

            handler.deliver(agentJob);

            // Observations are still persisted: deliver() persists first, then posts.
            assertThat(observationRepository.findAll()).hasSize(2);

            verify(commentPoster, never()).post(any());
            verify(diffNotePoster, never()).deliverPackage(any(), any(), any(), any(), any(), any());

            assertThat(agentJob.getDeliveryCommentId()).isNull();
            assertThat(agentJob.getDeliveryStatus()).isNull();
        }
    }

    @Nested
    class ObservationIdempotency {

        @Test
        @DisplayName("re-delivering same job creates no duplicate observations")
        void redeliveryNoDuplicates() {
            setJobOutput(validAgentOutput());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-789"));

            handler.deliver(agentJob);
            assertThat(observationRepository.findAll()).hasSize(2);

            handler.deliver(agentJob);
            assertThat(observationRepository.findAll()).hasSize(2);
        }
    }
}
