package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettings;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
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
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeDetectionCompletedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RecordApplicationEvents
class PracticeDetectionPipelineIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private JobTypeHandlerRegistry handlerRegistry;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ContentAddressedStore cas;

    @Autowired
    private AgentJobRepository agentJobRepository;

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

    @AfterEach
    void resetHandlerDoubles() {
        org.mockito.Mockito.reset(commentPoster, diffNotePoster, accountPreferencesQuery);
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(commentPoster, diffNotePoster, accountPreferencesQuery);
        databaseTestUtils.cleanDatabase();
        releaseSilentMode();
        when(commentPoster.findExistingSummaryComment(any())).thenReturn(ExistingDeliveryLookup.absent());

        workspace = WorkspaceTestFixtures.activeWorkspace("pipeline-test");
        workspace.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(workspace);

        Practice description = createPractice("pr-description-quality", "PR Description Quality");
        Practice errors = createPractice("error-handling", "Error Handling");

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        User developer = TestUserFactory.createUser(500L, "pipeline-author", provider);
        developer = userRepository.save(developer);
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
        agentJob.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setStatus(AgentJobStatus.COMPLETED);
        agentJob.setConfigSnapshot(
                OBJECT_MAPPER.valueToTree(Map.of("model", "claude-3.5", "agentType", "CLAUDE_CODE")));

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

    /**
     * @param reachedEveryPractice the coverage ledger the run left behind, which decides whether this
     *     review is allowed to say it found nothing
     */
    private AgentJob admitAndSetOutput(AgentJob job, String rawOutput, boolean reachedEveryPractice) {
        JsonNode observations = OBJECT_MAPPER.readTree(withEvidence(rawOutput)).path("observations");
        ((PullRequestReviewHandler) handler).admitObservations(job, observations);
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
        next.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        next.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        next.setStatus(AgentJobStatus.COMPLETED);
        next.setConfigSnapshot(agentJob.getConfigSnapshot());
        JsonNode metadata = agentJob.getMetadata();
        org.junit.jupiter.api.Assertions.assertNotNull(metadata);
        next.setMetadata(metadata.deepCopy());
        next.setEvidenceSnapshot(agentJob.getEvidenceSnapshot().deepCopy());
        next = agentJobRepository.save(next);
        return admitAndSetOutput(next, rawOutput);
    }

    private ObjectNode evidenceSnapshot(Practice... practices) {
        ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
        var sources =
                snapshot.putObject("manifest").put("contractVersion", "1.0.0").putArray("sources");
        addArtifact(
                sources.addObject().put("kind", "scm.pull-request.core"),
                "inputs/context/metadata.json",
                "{\"body\":\"Test body\"}");
        addArtifact(
                sources.addObject().put("kind", "scm.pull-request.diff"),
                "inputs/context/diff.patch",
                "diff --git a/src/Main.java b/src/Main.java\n+++ b/src/Main.java\n@@ -10 +10 @@\n[L10] + insecure();\n");
        var admitted = snapshot.putArray("practices");
        for (Practice practice : practices) {
            admitted.addObject()
                    .put("slug", practice.getSlug())
                    .put("revisionId", practice.getCurrentRevision().getId());
        }
        return snapshot;
    }

    private void addArtifact(ObjectNode source, String path, String content) {
        var facts = source.putObject("state")
                .put("availability", "AVAILABLE")
                .put("content", "NON_EMPTY")
                .put("completeness", "COMPLETE")
                .putObject("facts")
                .put("capturedAt", "2026-08-03T00:00:00Z");
        if ("scm.pull-request.diff".equals(source.path("kind").asString())) {
            facts.put("immutableIdentity", "pipelinesha");
        } else {
            facts.put("sourceEffectiveAt", "2026-08-03T00:00:00Z");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        source.putArray("artifacts")
                .addObject()
                .put("path", path)
                .put("mediaType", path.endsWith(".json") ? "application/json" : "text/x-diff")
                .put("sha256", cas.put(bytes))
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
                  "presence": "PRESENT",
                  "assessment": "GOOD",
                  "severity": "INFO",
                  "evidenceRationale": "The description names what changed."
                },
                {
                  "practiceSlug": "error-handling",
                  "summary": "Missing null check",
                  "presence": "ABSENT",
                  "assessment": "BAD",
                  "severity": "MAJOR",
                  "evidenceRationale": "The method does not check for null input."
                }
              ]""";
        return observations + "\n}";
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
            verify(commentPoster, never()).postFormattedBody(any(), any());
            verify(diffNotePoster, never()).reconcileInlineNotes(any(), any());

            releaseSilentMode();
            assertThat(feedbackRepository.findAll())
                    .noneMatch(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.PREPARED);

            AgentJob newEvent = newJobWithOutput(validAgentOutput());
            when(commentPoster.postFormattedBody(any(), any())).thenReturn("comment-after-release");
            handler.deliver(newEvent);

            verify(commentPoster).postFormattedBody(eq(newEvent), any(String.class));
            verify(diffNotePoster).reconcileInlineNotes(eq(newEvent), any());
            assertThat(observationRepository.findAll()).hasSize(4);
            assertThat(feedbackRepository.findAll())
                    .extracting(Feedback::getDeliveryState)
                    .containsExactlyInAnyOrder(FeedbackDeliveryState.SUPPRESSED, FeedbackDeliveryState.DELIVERED);
        }

        @Test
        void fullPipelineFromParseToDelivery() {
            setJobOutput(validAgentOutput());
            when(commentPoster.postFormattedBody(any(), any())).thenReturn("comment-123");
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

            verify(commentPoster).postFormattedBody(eq(agentJob), any(String.class));
            verify(diffNotePoster).reconcileInlineNotes(eq(agentJob), any());

            // AgentJobExecutor persists deliveryStatus, not handler.deliver(), so it stays null here.
            assertThat(agentJob.getDeliveryCommentId()).isEqualTo("comment-123");
            assertThat(agentJob.getDeliveryStatus()).isNull();
        }

        @Test
        void allPositiveFindingsPostsApproval() {
            String output = """
                {
                  "observations": [
                    {
                      "practiceSlug": "pr-description-quality",
                      "summary": "Good description",
                      "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": "INFO",
                      "evidenceRationale": "The description explains the change."
                    },
                    {
                      "practiceSlug": "error-handling",
                      "summary": "Proper error handling",
                      "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": "INFO",
                      "evidenceRationale": "The implementation handles errors explicitly."
                    }
                  ]
                }""";
            setJobOutput(output);
            when(commentPoster.postFormattedBody(any(), any())).thenReturn("comment-approval");

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(2);

            // A observations summary reaches this same call, so only the body text tells an approval apart.
            var body = ArgumentCaptor.forClass(String.class);
            verify(commentPoster).postFormattedBody(eq(agentJob), body.capture());
            assertThat(body.getValue()).contains("What's working well here");

            verify(diffNotePoster).reconcileInlineNotes(eq(agentJob), eq(List.of()));
        }

        @Test
        void allPositiveFindingsStayQuietWhenTheReviewDidNotReachEveryPractice() {
            String output = """
                {
                  "observations": [
                    {
                      "practiceSlug": "pr-description-quality",
                      "summary": "Good description",
                      "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": "INFO",
                      "evidenceRationale": "The description explains the change."
                    }
                  ]
                }""";
            agentJob = admitAndSetOutput(agentJob, output, false);

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(1);
            verify(commentPoster, never()).postFormattedBody(any(), any());
        }

        @Test
        void aPartialReviewStillReportsTheProblemItFound() {
            agentJob = admitAndSetOutput(agentJob, validAgentOutput(), false);
            when(commentPoster.postFormattedBody(any(), any())).thenReturn("comment-partial");
            when(diffNotePoster.reconcileInlineNotes(any(), any()))
                    .thenReturn(new DiffNotePoster.DiffNoteResult(1, 0, List.of()));

            handler.deliver(agentJob);

            assertThat(observationRepository.findAll()).hasSize(2);
            verify(commentPoster).postFormattedBody(eq(agentJob), any(String.class));
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
                      "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": "INFO",
                      "evidenceRationale": "The description explains the change."
                    },
                    {
                      "practiceSlug": "nonexistent-practice",
                      "summary": "Unknown practice",
                      "presence": "PRESENT",
                      "assessment": "GOOD",
                      "severity": "INFO",
                      "evidenceRationale": "The submitted practice does not exist."
                    },
                    {
                      "practiceSlug": "error-handling",
                      "summary": "Good handling",
                      "presence": "ABSENT",
                      "assessment": "BAD",
                      "severity": "MINOR",
                      "evidenceRationale": "The implementation omits the required check."
                    }
                  ]
                }""";
            JsonNode submitted = OBJECT_MAPPER.readTree(withEvidence(output)).path("observations");

            assertThatThrownBy(() -> ((PullRequestReviewHandler) handler).admitObservations(agentJob, submitted))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("practice not admitted to the job");

            assertThat(observationRepository.findAll()).isEmpty();
            verify(commentPoster, never()).postFormattedBody(any(), any());
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

            verify(commentPoster, never()).postFormattedBody(any(), any());
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
            when(commentPoster.postFormattedBody(any(), any())).thenReturn("comment-789");
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
