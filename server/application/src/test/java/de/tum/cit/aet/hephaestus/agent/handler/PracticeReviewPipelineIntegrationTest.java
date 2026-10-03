package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.PreparedJobInputsFixtures;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettings;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState.Available;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentProjector;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
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
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
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
    private ReviewHistoryContentSource historySource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private PullRequestCommentPoster commentPoster;

    @Autowired
    private DiffNotePoster diffNotePoster;

    @Autowired
    private AccountPreferencesQuery accountPreferencesQuery;

    @Autowired
    private ReactionRepository reactionRepository;

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
        when(commentPoster.findExistingSummaryComment(any())).thenReturn(ExistingDeliveryLookup.absent());
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
        agentJob.setStatus(AgentJobStatus.COMPLETED);
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
        agentJob.setMetadata(metadata);
        agentJob.setEvidenceSnapshot(evidenceSnapshot(description, errors));
        agentJob = agentJobRepository.save(agentJob);
        preparedEvidence.add(PreparedJobInputsFixtures.prepare(
                evidenceFiles, agentJob, PreparedJobInputsFixtures.filesOnly(capturedFiles)));
        preparedJobIds.add(agentJob.getId());

        handler = handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW);
        when(diffNotePoster.reconcileInlineNotes(any(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(0, 0, List.of()));
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(anyLong())).thenReturn(true);
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
                .put("commit_sha", HEAD_SHA);
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
        var raw = folderBuilder.prepare(
                new ContextRequest.PracticeReviewRequest(next), EvidencePlan.compile(List.of(practice)));
        var index = Objects.requireNonNull(raw.manifest());
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

        assertThat(index.artifacts().stream().filter(a -> a.artifact().path().startsWith("context/docs/")))
                .hasSize(21);
        var snapshot = OBJECT_MAPPER.createObjectNode();
        snapshot.set("manifest", OBJECT_MAPPER.valueToTree(index));
        snapshot.putArray("practices")
                .addObject()
                .put("slug", practice.getSlug())
                .put(
                        "revisionId",
                        Objects.requireNonNull(practice.getCurrentRevision().getId()));
        next.setEvidenceSnapshot(snapshot);
        next = agentJobRepository.save(next);
        var prepared = evidenceFiles.prepare(
                next,
                new PreparedEvidence(raw.files(), raw.filesOnDisk(), raw.cleanups(), null, raw.directories()),
                null);
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

    private void setJobOutput(String rawOutput) {
        agentJob = admitAndSetOutput(agentJob, rawOutput);
    }

    private AgentJob admitAndSetOutput(AgentJob job, String rawOutput) {
        return admitAndSetOutput(job, rawOutput, true);
    }

    /** The admission fence's own two steps, minus the ownership transaction it wraps them in. */
    private void admit(AgentJob job, JsonNode observations) {
        var pullRequests = (PullRequestReviewHandler) handler;
        pullRequests.prepareObservations(job, observations).record(job);
    }

    /**
     * @param reachedEveryPractice the coverage ledger the run left behind, which decides whether this
     *     review is allowed to say it found nothing
     */
    private AgentJob admitAndSetOutput(AgentJob job, String rawOutput, boolean reachedEveryPractice) {
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
        int eligible = Objects.requireNonNull(job.getEvidenceSnapshot())
                .path("practices")
                .size();
        output.putObject("practiceCoverage")
                .put("eligible", eligible)
                .put("evaluated", reachedEveryPractice ? eligible : eligible - 1);
        job.setOutput(output);
        return agentJobRepository.save(job);
    }

    private void releaseSilentMode() {
        var current = instanceSettingsService.get();
        instanceSettingsService.updateSilentMode(false, null, "pipeline-test", version(current));
    }

    private void engageSilentMode() {
        var current = instanceSettingsService.get();
        instanceSettingsService.updateSilentMode(true, "pipeline safety test", "pipeline-test", version(current));
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
        next.setStatus(AgentJobStatus.COMPLETED);
        next.setConfigSnapshot(agentJob.getConfigSnapshot());
        JsonNode metadata = agentJob.getMetadata();
        assertThat(metadata).isNotNull();
        next.setMetadata(metadata.deepCopy());
        next.setEvidenceSnapshot(agentJob.getEvidenceSnapshot().deepCopy());
        next = agentJobRepository.save(next);
        preparedEvidence.add(PreparedJobInputsFixtures.prepare(
                evidenceFiles, next, PreparedJobInputsFixtures.filesOnly(capturedFiles)));
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

    private List<Feedback> unitsOf(AgentJob job, FeedbackChannel channel) {
        return feedbackRepository.findAll().stream()
                .filter(feedback -> feedback.getAgentJobId().equals(job.getId()) && feedback.getChannel() == channel)
                .toList();
    }

    private List<@Nullable UUID> boundTo(Feedback feedback) {
        return jdbcTemplate.queryForList(
                "SELECT observation_id FROM feedback_observation WHERE feedback_id = ?", UUID.class, feedback.getId());
    }

    /**
     * The composer's output as the runner writes it: the job's admitted observations under their persisted
     * ids, and units whose {@code {practice-slug}} placeholders name the observation of that practice.
     */
    private void compose(AgentJob job, String lead, String... units) {
        ObjectNode output = (ObjectNode) Objects.requireNonNull(job.getOutput());
        ObjectNode feedback = (ObjectNode) output.get("feedback");
        feedback.put("lead", lead);
        var staged = feedback.putArray("observations");
        String written = "[" + String.join(",", units) + "]";
        for (Observation observation : observationRepository.findByAgentJobId(job.getId(), workspace.getId())) {
            String slug = observation.getPractice().getSlug();
            staged.addObject()
                    .put("id", observation.getId().toString())
                    .put("practiceSlug", slug)
                    .put("anchorable", false)
                    .putArray("citations");
            written = written.replace("{" + slug + "}", observation.getId().toString());
        }
        feedback.set("units", OBJECT_MAPPER.readTree(written));
        agentJobRepository.save(job);
    }

    private AgentJob reviewOf(
            Long pullRequestId, int number, String headSha, @Nullable String signal, String rawOutput) {
        return reviewOf(pullRequestId, number, headSha, signal, rawOutput, List.of());
    }

    private AgentJob reviewOf(
            Long pullRequestId,
            int number,
            String headSha,
            @Nullable String signal,
            String rawOutput,
            List<String> rechecked) {
        AgentJob next = new AgentJob();
        next.setWorkspace(workspace);
        next.setWorkerId("test-worker");
        next.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        next.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        next.setStatus(AgentJobStatus.COMPLETED);
        next.setConfigSnapshot(agentJob.getConfigSnapshot());
        ObjectNode metadata =
                (ObjectNode) Objects.requireNonNull(agentJob.getMetadata()).deepCopy();
        metadata.put("pull_request_id", pullRequestId);
        metadata.put("pr_number", number);
        metadata.put("commit_sha", headSha);
        if (signal != null) metadata.put(PracticeCatalogInjector.SIGNAL_METADATA_KEY, signal);
        if (!rechecked.isEmpty()) {
            var admitted = metadata.putArray(AgentJob.RECHECKED_PRACTICES_METADATA_KEY);
            rechecked.forEach(admitted::add);
        }
        next.setMetadata(metadata);
        next.setEvidenceSnapshot(agentJob.getEvidenceSnapshot().deepCopy());
        next = agentJobRepository.save(next);
        preparedEvidence.add(PreparedJobInputsFixtures.prepare(
                evidenceFiles, next, PreparedJobInputsFixtures.filesOnly(capturedFiles)));
        preparedJobIds.add(next.getId());
        return admitAndSetOutput(next, rawOutput);
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
            var unit = new ComposedFeedbackUnit(
                    FeedbackChannel.IN_CONTEXT,
                    "error-handling",
                    List.of(landing.getId().toString(), supporting.getId().toString()),
                    ComposedFeedbackUnit.Action.NEW,
                    null,
                    null,
                    "Null reaches the handler",
                    null,
                    "Guard the input before the lookup",
                    null,
                    new ComposedFeedbackUnit.InContextPlacement(
                            ComposedFeedbackUnit.InContextPlacement.PlacementKind.DIFF,
                            new ComposedFeedbackUnit.ResolvedAnchor(
                                    landing.getId().toString(), 0, "src/App.java", "NEW", 3, 3)));
            List<ValidatedObservation> admitted =
                    List.of(validated(summarised, null), inlineA, inlineB, validated(supporting, null));
            DeliveryContent content = Objects.requireNonNull(DeliveryComposer.composeAdmitted(
                    admitted, ArtifactKinds.PULL_REQUEST, Map.of(), List.of(unit), null));
            String landingKey = "observation:" + landing.getOccurrenceKey();
            String failingKey = "observation:" + failing.getOccurrenceKey();
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("summary-ref"));
            when(diffNotePoster.reconcileInlineNotes(eq(agentJob), any()))
                    .thenReturn(
                            new DiffNotePoster.DiffNoteResult(
                                    1,
                                    1,
                                    List.of(
                                            signal(landingKey, 3, Disposition.POSTED),
                                            signal(failingKey, 9, Disposition.FAILED))),
                            new DiffNotePoster.DiffNoteResult(
                                    1,
                                    1,
                                    List.of(
                                            signal(landingKey, 3, Disposition.PRESERVED_EXISTING),
                                            signal(failingKey, 9, Disposition.FAILED))));

            assertThatThrownBy(() -> feedbackDeliveryService.deliverFeedback(
                            agentJob, content, content.contributingPracticeSlugs(admitted)))
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
            verify(diffNotePoster, times(2)).reconcileInlineNotes(eq(agentJob), any());
            assertThat(stateOf(FeedbackDeliveryState.DELIVERED))
                    .as("the summary, the note that landed and what that note cites; never the note that failed")
                    .containsExactlyInAnyOrder(summarised.getId(), landing.getId(), supporting.getId());
            assertThat(stateOf(FeedbackDeliveryState.FAILED)).containsExactly(failing.getId());
            assertThat(jdbcTemplate.queryForList(
                            "SELECT body FROM feedback WHERE agent_job_id = ? AND delivery_state = 'FAILED'",
                            String.class,
                            agentJob.getId()))
                    .singleElement()
                    .asString()
                    .contains("Second unchecked input");
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

        private static DeliveredSignal signal(String key, int line, Disposition disposition) {
            boolean landed = disposition != Disposition.FAILED;
            return new DeliveredSignal(
                    key,
                    new DiffAnchor("src/App.java", line, null),
                    disposition,
                    landed ? "note-" + key : null,
                    landed ? "discussion-" + key : null);
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
    class RepeatedPraise {

        /** The Ready-review lead on staging: a critique no observation in the review supports. */
        private static final String LEAD =
                "SwiftLint has stalled in the pipeline and the VoiceOver pass is still unchecked.";

        private static final String BOTH_HOLD = """
                {"observations": [
                  {"practiceSlug": "pr-description-quality", "summary": "The description names the issue it closes",
                   "outcome": "MET", "severity": null,
                   "evidenceRationale": "The body closes the issue."},
                  {"practiceSlug": "error-handling", "summary": "Errors reach the caller",
                   "outcome": "MET", "severity": null,
                   "evidenceRationale": "Every failure path returns an error."}
                ]}""";

        /** The manual MR !2 shape on staging: one strength held again, the other practice had no subject. */
        private static final String ONE_HOLDS_ONE_ABSTAINS = """
                {"observations": [
                  {"practiceSlug": "pr-description-quality", "summary": "The description names the issue it closes",
                   "outcome": "MET", "severity": null,
                   "evidenceRationale": "The body closes the issue."},
                  {"practiceSlug": "error-handling", "summary": "No error path changed",
                   "outcome": "NOT_APPLICABLE", "severity": null,
                   "evidenceRationale": "The change touches no error path.",
                   "evidence": {
                     "citations": [{"sourceKind": "scm.pull-request.core", "artifactPath": "context/metadata.json",
                                    "path": "body", "startLine": 1, "endLine": 1, "quote": "Test body"}],
                     "inapplicability": {"consulted": ["scm.pull-request.core"], "subject": "an error path",
                                         "ruledOutBy": "the change adds no failing call"}}}
                ]}""";

        @Test
        void aStrengthAlreadyPraisedOnAnEarlierMergeRequestIsNotPostedAgainOnEitherReviewOfTheNextOne() {
            Long firstPr = pullRequest(8000L, 49, "firstsha");
            AgentJob first = reviewOf(firstPr, 49, "firstsha", null, BOTH_HOLD);
            compose(first, LEAD, strengthUnit("The issue link names the outcome", "Keep closing issues this way"));
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-first"));
            handler.deliver(first);
            verify(commentPoster).post(argThat(write -> write.job().equals(first)));
            assertThat(deliveredBody(first)).contains("The issue link names the outcome");

            Map<String, byte[]> history = new HashMap<>();
            historySource.contribute(new ContextRequest.PracticeReviewRequest(agentJob), history);
            assertThat(new String(history.get(SandboxLayout.HISTORY_PREFIX + "feedback.json"), StandardCharsets.UTF_8))
                    .as("the next review's composer is given the praise already delivered")
                    .contains("The issue link names the outcome");

            autonomy(PracticeAutonomy.HUMAN_APPROVAL);
            AgentJob manual = reviewOf(prId, 50, "pipelinesha", null, ONE_HOLDS_ONE_ABSTAINS);
            compose(manual, LEAD, abstentionUnit());
            handler.deliver(manual);

            autonomy(PracticeAutonomy.AUTOMATIC);
            AgentJob ready = reviewOf(prId, 50, "pipelinesha", "scm.pull_request.ready", BOTH_HOLD);
            compose(ready, LEAD);
            handler.deliver(ready);

            verify(commentPoster, never()).post(argThat(write -> !write.job().equals(first)));
            assertThat(feedbackRepository.findAll())
                    .filteredOn(feedback -> !feedback.getAgentJobId().equals(first.getId()))
                    .noneMatch(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.AWAITING_APPROVAL
                            || feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED);
            assertThat(observationRepository.findByAgentJobId(ready.getId(), workspace.getId()))
                    .hasSize(2);
            assertThat(jdbcTemplate.queryForObject("""
                            SELECT count(*) FROM feedback_observation fo
                            JOIN feedback f ON f.id = fo.feedback_id WHERE f.agent_job_id IN (?, ?)
                            """, Integer.class, manual.getId(), ready.getId()))
                    .isZero();
        }

        /** One unchanged lapse, raised on the work and prepared for a conversation, as each review composes it. */
        private static final String SWALLOWED_ERROR = """
                {"observations": [
                  {"practiceSlug": "error-handling", "summary": "Export errors stop at the log",
                   "outcome": "NOT_MET", "severity": "MAJOR",
                   "evidenceRationale": "The export call logs the failure and returns an empty file."}
                ]}""";

        @Test
        void shouldSuppressRepeatedGuidanceWhenARepairRecheckStillRecordsTheSameNegative() {
            AgentJob first = reviewOf(prId, 50, "pipelinesha", null, SWALLOWED_ERROR);
            compose(first, LEAD, lapseNote(), lapseBrief());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-first"));
            handler.deliver(first);

            AgentJob second = reviewOf(
                    prId,
                    50,
                    "pipelinesha",
                    ScmSignals.PULL_REQUEST_EDITED.value(),
                    SWALLOWED_ERROR,
                    List.of("error-handling"));
            compose(second, LEAD, lapseNote(), lapseBrief());
            handler.deliver(second);

            verify(commentPoster, times(1)).post(any());
            assertThat(deliveredBody(first)).contains("Return the export error to the caller");
            List<UUID> current = observationRepository.findByAgentJobId(second.getId(), workspace.getId()).stream()
                    .map(Observation::getId)
                    .toList();
            assertThat(unitsOf(second, FeedbackChannel.IN_CONTEXT))
                    .singleElement()
                    .satisfies(held -> {
                        assertThat(held.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
                        assertThat(held.getSuppressionReason())
                                .isEqualTo(FeedbackSuppressionReason.REPEATS_DELIVERED_NOTE);
                        assertThat(boundTo(held)).containsExactlyElementsOf(current);
                    });
            assertThat(unitsOf(first, FeedbackChannel.IN_CHAT))
                    .as("a note on the work is not echoed into a conversation")
                    .isEmpty();
            assertThat(unitsOf(second, FeedbackChannel.IN_CHAT))
                    .as("the withheld note leaves this review's own brief to its own routing")
                    .singleElement()
                    .satisfies(brief -> {
                        assertThat(brief.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
                        assertThat(boundTo(brief)).containsExactlyElementsOf(current);
                    });
        }

        @Test
        void shouldReplaceTheCurrentNegativeWithAPositiveFromAnAdmittedRepairReview() {
            AgentJob first = reviewOf(prId, 50, "pipelinesha", null, SWALLOWED_ERROR);
            List<Observation> earlier = observationRepository.findByAgentJobId(first.getId(), workspace.getId());
            String repaired = """
                    {"observations": [
                      {"practiceSlug": "error-handling", "summary": "Export errors reach the caller",
                       "outcome": "MET", "severity": null,
                       "evidenceRationale": "The caller receives the export failure instead of an empty file."}
                    ]}""";
            AgentJob second = reviewOf(
                    prId,
                    50,
                    "pipelinesha",
                    ScmSignals.PULL_REQUEST_EDITED.value(),
                    repaired,
                    List.of("error-handling"));
            List<Observation> later = observationRepository.findByAgentJobId(second.getId(), workspace.getId());
            assertThat(later).singleElement().satisfies(row -> {
                assertThat(row.getAgentJobId()).isEqualTo(second.getId());
                assertThat(row.getOutcome()).isEqualTo(Outcome.MET);
            });
            assertThat(LatestRun.perClaim(observationRepository.findStandingForWork(
                            workspace.getId(), ArtifactKinds.PULL_REQUEST, prId, developer.getId())))
                    .extracting(Observation::getId)
                    .containsExactly(later.getFirst().getId());
            assertThat(observationRepository.findById(earlier.getFirst().getId()))
                    .isPresent();
        }

        private String lapseNote() {
            return """
                    {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"error-handling",
                     "basedOn":["{error-handling}"],"title":"Export errors stop at the log",
                     "nextStep":"Return the export error to the caller","placement":{"kind":"ARTIFACT"}}""";
        }

        private String lapseBrief() {
            return """
                    {"channel":"IN_CHAT","action":"NEW","practiceSlug":"error-handling",
                     "basedOn":["{error-handling}"],"title":"Where export failures go",
                     "notes":{"situation":"The export call logs a failure and returns an empty file.",
                              "capability":"Deciding who should learn that an export failed.",
                              "evidenceSummary":"The review cites the logging branch of the export call.",
                              "inConversationSignal":"They name who needs the error and how it reaches them."}}""";
        }

        @Test
        void aDistinctComposedStrengthIsPostedAutomaticallyWithoutTheLead() {
            AgentJob review = reviewOf(prId, 50, "pipelinesha", "scm.pull_request.ready", BOTH_HOLD);
            compose(
                    review,
                    LEAD,
                    strengthUnit("The retry path returns the upstream error", "Add the same to the export call"));
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-distinct"));

            handler.deliver(review);

            String body = deliveredBody(review);
            assertThat(body)
                    .contains("The retry path returns the upstream error")
                    .doesNotContain(LEAD);
            assertThat(jdbcTemplate.queryForList("""
                            SELECT p.slug FROM feedback_observation fo
                            JOIN feedback f ON f.id = fo.feedback_id
                            JOIN observation o ON o.id = fo.observation_id
                            JOIN practice p ON p.id = o.practice_id
                            WHERE f.agent_job_id = ? AND f.delivery_state = 'DELIVERED'
                            """, String.class, review.getId()))
                    .as("the strength the note never mentioned stays open to the other channels")
                    .containsExactly("pr-description-quality");
        }

        private String strengthUnit(String title, String nextStep) {
            return """
                    {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"pr-description-quality",
                     "basedOn":["{pr-description-quality}"],"title":"%s","nextStep":"%s",
                     "placement":{"kind":"ARTIFACT"}}""".formatted(title, nextStep);
        }

        private String abstentionUnit() {
            return """
                    {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"error-handling",
                     "basedOn":["{error-handling}"],"title":"Tick the issue's done list as it lands",
                     "nextStep":"Tick the done items before merging","placement":{"kind":"ARTIFACT"}}""";
        }

        private void autonomy(PracticeAutonomy autonomy) {
            for (Practice practice : practiceRepository.findByWorkspaceIdAndSlugIn(
                    workspace.getId(), Set.of("pr-description-quality", "error-handling"))) {
                practice.setAutonomy(autonomy);
                practiceRepository.saveAndFlush(practice);
            }
        }

        private String deliveredBody(AgentJob job) {
            return feedbackRepository.findAll().stream()
                    .filter(feedback -> feedback.getAgentJobId().equals(job.getId())
                            && feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                    .map(Feedback::getBody)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow();
        }

        private Long pullRequest(long nativeId, int number, String headSha) {
            Instant now = Instant.now();
            pullRequestRepository.upsertCore(
                    nativeId,
                    Objects.requireNonNull(repository.getProvider().getId()),
                    number,
                    "Earlier change",
                    "Closes #1",
                    "OPEN",
                    null,
                    "https://github.com/org/pipeline-repo/pull/" + number,
                    false,
                    null,
                    0,
                    now,
                    now,
                    now,
                    developer.getId(),
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
                    "feature/earlier",
                    "main",
                    headSha,
                    "basesha",
                    null,
                    null);
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(repository.getId(), number)
                    .orElseThrow()
                    .getId();
        }
    }

    /**
     * A developer's dispute holds for the observation it was about: the next review of the same work says nothing
     * about it, and once the developer withdraws the dispute the observation may be raised again.
     */
    @Nested
    class DisputedFeedback {

        private static final String UNCHECKED_EXPORT = """
                {"observations": [
                  {"practiceSlug": "error-handling", "summary": "Export errors stop at the log",
                   "outcome": "NOT_MET", "severity": "MAJOR",
                   "evidenceRationale": "The export call logs the failure and returns an empty file."}
                ]}""";

        @Test
        void shouldWithholdTheSameObservationFromTheNextReviewUntilTheDisputeIsWithdrawn() {
            AgentJob first = reviewOf(prId, 50, "pipelinesha", null, UNCHECKED_EXPORT);
            compose(first, "", note("Export errors stop at the log", "Return the export error to the caller"));
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-first"));
            handler.deliver(first);
            Feedback delivered = unitsOf(first, FeedbackChannel.IN_CONTEXT).stream()
                    .filter(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                    .findFirst()
                    .orElseThrow();

            reactionRepository.save(Reaction.builder()
                    .feedback(delivered)
                    .reactorUserId(developer.getId())
                    .resolution(FeedbackResolution.DISPUTED)
                    .explanation("The caller retries the export and reports the failure.")
                    .build());

            AgentJob second = reviewOf(prId, 50, "pipelinesha", null, UNCHECKED_EXPORT);
            compose(second, "", note("The export swallows its failure", "Report the failed export upward"));
            handler.deliver(second);

            verify(commentPoster, never()).post(argThat(write -> write.job().equals(second)));
            List<UUID> observedAgain =
                    observationRepository.findByAgentJobId(second.getId(), workspace.getId()).stream()
                            .map(Observation::getId)
                            .toList();
            assertThat(unitsOf(second, FeedbackChannel.IN_CONTEXT))
                    .noneMatch(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                    .anySatisfy(held -> {
                        assertThat(held.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
                        assertThat(held.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.REACTED_DISPUTED);
                        assertThat(boundTo(held)).containsExactlyElementsOf(observedAgain);
                    });

            // Withdrawing the dispute is a response with nothing in it, as the developer's page writes it.
            reactionRepository.save(Reaction.builder()
                    .feedback(delivered)
                    .reactorUserId(developer.getId())
                    .build());

            AgentJob third = reviewOf(prId, 50, "pipelinesha", null, UNCHECKED_EXPORT);
            compose(third, "", note("Export failures never reach the caller", "Return the failure to the caller"));
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-third"));
            handler.deliver(third);

            verify(commentPoster).post(argThat(write -> write.job().equals(third)));
            assertThat(unitsOf(third, FeedbackChannel.IN_CONTEXT))
                    .anyMatch(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED);
        }

        private String note(String title, String nextStep) {
            return """
                    {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"error-handling",
                     "basedOn":["{error-handling}"],"title":"%s","nextStep":"%s",
                     "placement":{"kind":"ARTIFACT"}}""".formatted(title, nextStep);
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
            verify(diffNotePoster, never()).reconcileInlineNotes(any(), any());

            releaseSilentMode();
            assertThat(feedbackRepository.findAll())
                    .noneMatch(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.PREPARED);

            AgentJob newEvent = newJobWithOutput(validAgentOutput());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-after-release"));
            handler.deliver(newEvent);

            verify(commentPoster).post(argThat(write -> write.job().equals(newEvent)));
            verify(diffNotePoster).reconcileInlineNotes(eq(newEvent), any());
            assertThat(observationRepository.findAll()).hasSize(4);
            assertThat(feedbackRepository.findAll())
                    .extracting(Feedback::getDeliveryState)
                    .containsExactlyInAnyOrder(FeedbackDeliveryState.SUPPRESSED, FeedbackDeliveryState.DELIVERED);
        }

        @Test
        void fullPipelineFromParseToDelivery() {
            setJobOutput(validAgentOutput());
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-123"));
            when(diffNotePoster.reconcileInlineNotes(any(), any()))
                    .thenReturn(new DiffNotePoster.DiffNoteResult(1, 0, List.of()));

            handler.deliver(agentJob);

            List<Observation> observations = observationRepository.findAll();
            assertThat(observations).hasSize(2);
            assertThat(observations)
                    .extracting(Observation::getOutcome)
                    .containsExactlyInAnyOrder(Outcome.MET, Outcome.NOT_MET);

            verify(commentPoster).post(argThat(write -> write.job().equals(agentJob)));
            verify(diffNotePoster).reconcileInlineNotes(eq(agentJob), any());

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
            agentJob = admitAndSetOutput(agentJob, output, false);

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(1);
            verify(commentPoster, never()).post(any());
        }

        @Test
        void aPartialReviewStillReportsTheProblemItFound() {
            agentJob = admitAndSetOutput(agentJob, validAgentOutput(), false);
            when(commentPoster.post(any())).thenReturn(new SummaryHandle("comment-partial"));
            when(diffNotePoster.reconcileInlineNotes(any(), any()))
                    .thenReturn(new DiffNotePoster.DiffNoteResult(1, 0, List.of()));

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
            verify(diffNotePoster, never()).reconcileInlineNotes(any(), any());
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
            verify(diffNotePoster, never()).reconcileInlineNotes(any(), any());

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
            when(diffNotePoster.reconcileInlineNotes(any(), any()))
                    .thenReturn(new DiffNotePoster.DiffNoteResult(1, 0, List.of()));

            handler.deliver(agentJob);
            assertThat(observationRepository.findAll()).hasSize(2);

            handler.deliver(agentJob);
            assertThat(observationRepository.findAll()).hasSize(2);
        }
    }
}
