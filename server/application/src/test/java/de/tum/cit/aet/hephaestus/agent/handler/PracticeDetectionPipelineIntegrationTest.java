package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
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
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
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
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeDetectionCompletedEvent;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RecordApplicationEvents
class PracticeDetectionPipelineIntegrationTest extends BaseIntegrationTest {

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
    private de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout evidenceLayout;

    private final java.util.List<java.util.UUID> preparedJobIds = new java.util.ArrayList<>();
    private final Map<String, byte[]> capturedFiles = new java.util.LinkedHashMap<>();
    private final java.util.List<PreparedJobInputs> preparedEvidence = new java.util.ArrayList<>();

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
    private ApplicationEvents applicationEvents;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackDeliveryService feedbackDeliveryService;

    @Autowired
    private de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource historySource;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private PullRequestCommentPoster commentPoster;

    @Autowired
    private DiffNotePoster diffNotePoster;

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
        org.mockito.Mockito.reset(commentPoster, diffNotePoster, accountPreferencesQuery);
    }

    @org.junit.jupiter.api.AfterEach
    void releasePreparedEvidence() throws Exception {
        preparedEvidence.forEach(PreparedJobInputs::close);
        preparedEvidence.clear();
        for (var jobId : preparedJobIds) {
            org.apache.commons.io.FileUtils.deleteDirectory(evidenceLayout
                    .jobsRoot()
                    .resolve(workspace.getId().toString())
                    .resolve(jobId.toString())
                    .toFile());
        }
        preparedJobIds.clear();
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(commentPoster, diffNotePoster, accountPreferencesQuery);
        databaseTestUtils.cleanDatabase();
        releaseSilentMode();
        when(commentPoster.findExistingSummaryComment(any())).thenReturn(ExistingDeliveryLookup.absent());
        AgentHandlerTestDoubles.resolveSummaryWrites(commentPoster);
        when(commentPoster.findExisting(any())).thenReturn(ExistingDeliveryLookup.absent());

        workspace = WorkspaceTestFixtures.activeWorkspace("pipeline-test");
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);

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
        Long providerId = java.util.Objects.requireNonNull(provider.getId());
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
        preparedEvidence.add(evidenceFiles.prepare(agentJob, PreparedJobInputs.filesOnly(capturedFiles)));
        preparedJobIds.add(agentJob.getId());

        handler = handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW);
        when(diffNotePoster.reconcileInlineNotes(any(), any()))
                .thenReturn(new DiffNotePoster.DiffNoteResult(0, 0, List.of()));
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(anyLong())).thenReturn(true);
    }

    private Practice createPractice(String slug, String name) {
        Practice p = new Practice();
        p.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        p.setWorkspace(workspace);
        p.setAutonomy(PracticeAutonomy.AUTOMATIC);
        p.setSlug(slug);
        p.setName(name);
        p.setCriteria("Test " + slug);
        p.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
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
        org.junit.jupiter.api.Assertions.assertNotNull(jobMetadata);
        ObjectNode metadata = (ObjectNode) jobMetadata.deepCopy();
        metadata.put(ObservationAdmissionService.DIGEST_METADATA_KEY, digest);
        job.setMetadata(metadata);
        ObjectNode output = OBJECT_MAPPER.createObjectNode();
        output.putObject("feedback").put("admissionDigest", digest).putArray("units");
        output.putObject("practiceCoverage").put("eligible", 2).put("evaluated", reachedEveryPractice ? 2 : 1);
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
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        next.setMetadata(metadata.deepCopy());
        next.setEvidenceSnapshot(agentJob.getEvidenceSnapshot().deepCopy());
        next = agentJobRepository.save(next);
        preparedEvidence.add(evidenceFiles.prepare(next, PreparedJobInputs.filesOnly(capturedFiles)));
        preparedJobIds.add(next.getId());
        return admitAndSetOutput(next, rawOutput);
    }

    private ObjectNode evidenceSnapshot(Practice... practices) {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(OBJECT_MAPPER);
        addArtifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.core", null),
                "inputs/context/metadata.json",
                "{\"body\":\"Test body\"}");
        addArtifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.diff", BASE_SHA + ":" + HEAD_SHA),
                PullRequestContentSource.CHANGE_FILE,
                "{\"base_sha\":\"" + BASE_SHA + "\",\"head_sha\":\"" + HEAD_SHA + "\"}");
        for (Practice practice : practices) {
            EvidenceSnapshotFixtures.admittedPractice(
                    snapshot,
                    practice.getSlug(),
                    java.util.Objects.requireNonNull(
                            practice.getCurrentRevision().getId()));
        }
        return snapshot;
    }

    private void addArtifact(ObjectNode source, String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        capturedFiles.put(path, bytes);
        EvidenceSnapshotFixtures.artifact(source, path, ProvenanceDigest.sha256Hex(bytes))
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
                    citation.put("artifactPath", "inputs/context/metadata.json");
                    citation.put("path", "body");
                    citation.put("startLine", 1);
                    citation.put("endLine", 1);
                    citation.put("quote", "Test body");
                }
                // An ABSENT observation asserts a universal, so delivery requires it to record the
                // search that came up empty.
                if ("ABSENT".equals(observation.path("presence").asString(null))) {
                    var search = ((ObjectNode) observation.path("evidence")).putObject("search");
                    search.putArray("consulted").add("scm.pull-request.core");
                    search.put("lookedFor", "a null check on the changed method");
                    search.put("boundary", "the pull request metadata only");
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
                  "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                  "assessment": "GOOD",
                  "severity": null,
                  "evidenceRationale": "The description names what changed."
                },
                {
                  "practiceSlug": "error-handling",
                  "summary": "Missing null check",
                  "assessmentStatus": "ASSESSED", "presence": "ABSENT",
                  "assessment": "GOOD",
                  "severity": "MAJOR",
                  "evidenceRationale": "The method does not check for null input."
                }
              ]""";
        return observations + "\n}";
    }

    @Nested
    class PartialDelivery {

        private static final String FOUR_OBSERVATIONS = """
                {"observations": [
                  {"practiceSlug": "pr-description-quality", "summary": "The description never says why",
                   "assessmentStatus": "ASSESSED", "presence": "ABSENT", "assessment": "GOOD", "severity": "MINOR",
                   "evidenceRationale": "The body lists what changed only."},
                  {"practiceSlug": "error-handling", "summary": "Missing null check",
                   "assessmentStatus": "ASSESSED", "presence": "ABSENT", "assessment": "GOOD", "severity": "MAJOR",
                   "evidenceRationale": "The method does not check for null input."},
                  {"practiceSlug": "error-handling", "summary": "Second unchecked input",
                   "assessmentStatus": "ASSESSED", "presence": "ABSENT", "assessment": "GOOD", "severity": "MINOR",
                   "evidenceRationale": "The parser does not check its input either."},
                  {"practiceSlug": "pr-description-quality", "summary": "The description names the issue",
                   "assessmentStatus": "ASSESSED", "presence": "PRESENT", "assessment": "GOOD", "severity": null,
                   "evidenceRationale": "The body closes the issue."}
                ]}""";

        @Test
        void aLineNoteThatNeverLandsIsRecordedFailedAndNotBehindTheDeliveredComment() {
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
            DeliveryContent content = java.util.Objects.requireNonNull(DeliveryComposer.composeAdmitted(
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
                    row.getAssessmentStatus(),
                    row.getPresence(),
                    row.getAssessment(),
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
            citation.put("artifactPath", "inputs/context/diff.patch");
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
                    new de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor.DiffAnchor(
                            "src/App.java", line, null),
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
                   "assessmentStatus": "ASSESSED", "presence": "PRESENT", "assessment": "GOOD", "severity": null,
                   "evidenceRationale": "The body closes the issue."},
                  {"practiceSlug": "error-handling", "summary": "Errors reach the caller",
                   "assessmentStatus": "ASSESSED", "presence": "PRESENT", "assessment": "GOOD", "severity": null,
                   "evidenceRationale": "Every failure path returns an error."}
                ]}""";

        /** The manual MR !2 shape on staging: one strength held again, the other practice had no subject. */
        private static final String ONE_HOLDS_ONE_ABSTAINS = """
                {"observations": [
                  {"practiceSlug": "pr-description-quality", "summary": "The description names the issue it closes",
                   "assessmentStatus": "ASSESSED", "presence": "PRESENT", "assessment": "GOOD", "severity": null,
                   "evidenceRationale": "The body closes the issue."},
                  {"practiceSlug": "error-handling", "summary": "No error path changed",
                   "assessmentStatus": "NOT_APPLICABLE", "presence": null, "assessment": null, "severity": null,
                   "evidenceRationale": "The change touches no error path.",
                   "evidence": {
                     "citations": [{"sourceKind": "scm.pull-request.core", "artifactPath": "inputs/context/metadata.json",
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

            Map<String, byte[]> history = new java.util.HashMap<>();
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

        /**
         * The composer's output as the runner writes it: the job's admitted observations under their persisted
         * ids, and units whose {@code {practice-slug}} placeholders name the observation of that practice.
         */
        private void compose(AgentJob job, String lead, String... units) {
            ObjectNode output = (ObjectNode) java.util.Objects.requireNonNull(job.getOutput());
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

        private void autonomy(PracticeAutonomy autonomy) {
            for (Practice practice : practiceRepository.findByWorkspaceIdAndSlugIn(
                    workspace.getId(), java.util.Set.of("pr-description-quality", "error-handling"))) {
                practice.setAutonomy(autonomy);
                practiceRepository.saveAndFlush(practice);
            }
        }

        private String deliveredBody(AgentJob job) {
            return feedbackRepository.findAll().stream()
                    .filter(feedback -> feedback.getAgentJobId().equals(job.getId())
                            && feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                    .map(Feedback::getBody)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseThrow();
        }

        private Long pullRequest(long nativeId, int number, String headSha) {
            Instant now = Instant.now();
            pullRequestRepository.upsertCore(
                    nativeId,
                    java.util.Objects.requireNonNull(repository.getProvider().getId()),
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

        private AgentJob reviewOf(
                Long pullRequestId, int number, String headSha, @Nullable String signal, String rawOutput) {
            AgentJob next = new AgentJob();
            next.setWorkspace(workspace);
            next.setWorkerId("test-worker");
            next.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            next.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            next.setStatus(AgentJobStatus.COMPLETED);
            next.setConfigSnapshot(agentJob.getConfigSnapshot());
            ObjectNode metadata = (ObjectNode)
                    java.util.Objects.requireNonNull(agentJob.getMetadata()).deepCopy();
            metadata.put("pull_request_id", pullRequestId);
            metadata.put("pr_number", number);
            metadata.put("commit_sha", headSha);
            if (signal != null) metadata.put(PracticeCatalogInjector.SIGNAL_METADATA_KEY, signal);
            next.setMetadata(metadata);
            next.setEvidenceSnapshot(agentJob.getEvidenceSnapshot().deepCopy());
            next = agentJobRepository.save(next);
            preparedEvidence.add(evidenceFiles.prepare(next, PreparedJobInputs.filesOnly(capturedFiles)));
            preparedJobIds.add(next.getId());
            return admitAndSetOutput(next, rawOutput);
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
                    .extracting(Observation::getPresence)
                    .containsExactlyInAnyOrder(Presence.PRESENT, Presence.ABSENT);

            List<PracticeDetectionCompletedEvent> events = applicationEvents.stream(
                            PracticeDetectionCompletedEvent.class)
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).observationsInserted()).isEqualTo(2);
            assertThat(events.get(0).hasNegative()).isTrue();

            verify(commentPoster).post(argThat(write -> write.job().equals(agentJob)));
            verify(diffNotePoster).reconcileInlineNotes(eq(agentJob), any());

            // AgentJobExecutor persists deliveryStatus, not handler.deliver(), so it stays null here.
            assertThat(agentJob.getDeliveryCommentId()).isEqualTo("comment-123");
            assertThat(agentJob.getDeliveryStatus()).isNull();
        }

        @Test
        void allPositiveFindingsStayQuietWhenTheReviewDidNotReachEveryPractice() {
            String output = """
                {
                  "observations": [
                    {
                      "practiceSlug": "pr-description-quality",
                      "summary": "Good description",
                      "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                      "assessment": "GOOD",
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
                      "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": null,
                      "evidenceRationale": "The description explains the change."
                    },
                    {
                      "practiceSlug": "nonexistent-practice",
                      "summary": "Unknown practice",
                      "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": null,
                      "evidenceRationale": "The submitted practice does not exist."
                    },
                    {
                      "practiceSlug": "error-handling",
                      "summary": "Good handling",
                      "assessmentStatus": "ASSESSED", "presence": "ABSENT",
                      "assessment": "GOOD",
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
            var provider = java.util.Objects.requireNonNull(pr.getProvider());
            var author = java.util.Objects.requireNonNull(pr.getAuthor());
            var createdAt = java.util.Objects.requireNonNull(pr.getCreatedAt());
            pullRequestRepository.upsertCore(
                    8001L,
                    java.util.Objects.requireNonNull(provider.getId()),
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
    class FindingIdempotency {

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

            List<PracticeDetectionCompletedEvent> events = applicationEvents.stream(
                            PracticeDetectionCompletedEvent.class)
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).observationsInserted()).isEqualTo(2);
        }
    }
}
