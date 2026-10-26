package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.AppliesWhenEvaluator;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptRunRecorderFixtures;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptRunRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.AppliesWhen;
import de.tum.cit.aet.hephaestus.practices.AppliesWhenClause;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalogLoader;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.AbstractResultAdmissionIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class AppliesWhenExecutionIntegrationTest extends AbstractResultAdmissionIntegrationTest {
    private static final String OTHERWISE = "The change touches no Swift file.";
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ReviewJobRepository jobs;

    @Autowired
    ReviewJobAssignments assignments;

    @Autowired
    ReviewAssignmentStore members;

    @Autowired
    AdmittedResultQuery proof;

    @Autowired
    ResultStore store;

    @Autowired
    ResultRepository results;

    @Autowired
    ReviewPracticeRepository practices;

    @Autowired
    ReviewItemRepository reviewItems;

    @Autowired
    PracticeRepository practiceRows;

    @Autowired
    PracticeVersionRepository versionRows;

    @Autowired
    PracticeVersionService practiceVersions;

    @Autowired
    ItemDescriptorRegistry descriptors;

    @Autowired
    JsonMapper mapper;

    @Autowired
    ClasspathSourceCatalogRegistry catalogs;

    @Autowired
    BundledPracticeCatalogLoader bundled;

    @Autowired
    ScriptRunRepository scriptRuns;

    @Autowired
    MeterRegistry meters;

    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final WorkspaceContextBuilder context = mock(WorkspaceContextBuilder.class);
    private final ItemSource source = mock(ItemSource.class);

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
    }

    private ReviewAdmissionData.Pending pending() {
        var d = DefinitionTestFixtures.definition();
        var definition = new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                new AppliesWhen(List.of(new AppliesWhenClause(List.of("**/*.swift"), null, null)), OTHERWISE),
                d.about(),
                new PracticeReads("2.0.0", List.of(new PracticeReadSource(DIFF, ReadNeed.REQUIRED))),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                d.scan(),
                d.lineNotes(),
                d.guidance());
        return pending(definition);
    }

    private ReviewAdmissionData.Pending pending(PracticeDefinition definition) {
        return pending(data.scenario(definition));
    }

    private ReviewAdmissionData.Pending pending(ReviewIntegrationData.Scenario scenario) {
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        running(pending, 1);
        return pending;
    }

    /** Runs the pending review's job at {@code attempt}, as the runtime reports it. */
    private void running(ReviewAdmissionData.Pending pending, int attempt) {
        admissions.running(pending, attempt);
        when(runtime.runtime(pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        attempt,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "test-trace")))));
    }

    private DefaultReviewExecutionHandler handler(ReviewJob job, boolean available, Set<String> paths) {
        var assignment = assignments.load(job).getFirst();
        var snapshot = mock(ItemSnapshot.class);
        when(source.supports(assignment.item())).thenReturn(true);
        when(source.pin(assignment.item(), assignment.version())).thenReturn(snapshot);
        SourceCaptureState state = available
                ? new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.COMPLETE,
                        new SourceCaptureFacts(Instant.now(), null, null, "base:head"))
                : new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER);
        var manifest = new JobFolderIndex(
                new SourceContractVersion("2.0.0"),
                "a".repeat(64),
                assignment.item().kind().value(),
                Instant.now(),
                List.of(new SourceCapture(
                        DIFF,
                        state,
                        EvidenceSnapshotFixtures.citation(DIFF),
                        available
                                ? List.of(new CapturedSource(
                                        "context/change.json", "application/json", "a".repeat(64), 1))
                                : List.of())),
                List.of(),
                List.of(),
                List.of());
        var captured = available
                ? Map.of(
                        "context/change.json",
                        mapper.writeValueAsBytes(mapper.createObjectNode()
                                .put("base_sha", "a".repeat(40))
                                .put("head_sha", "b".repeat(40))
                                .put("diff", "+changed();\n")
                                .set("changed_paths", mapper.valueToTree(paths))))
                : Map.<String, byte[]>of();
        when(context.prepare(eq(snapshot), any())).thenReturn(new PreparedEvidence(captured, manifest));
        captureFiles(job);
        if (available && paths.stream().anyMatch(path -> path.endsWith(".swift")))
            when(source.record(eq(snapshot), any()))
                    .thenReturn(mapper.createObjectNode().put("title", "Captured change"));
        return handler(context);
    }

    private DefaultReviewExecutionHandler handler(WorkspaceContextBuilder contexts) {
        return new DefaultReviewExecutionHandler(
                assignments,
                List.of(source),
                contexts,
                files,
                new PracticeCatalogInjector(mapper),
                new PracticeScriptInputs(mapper),
                mock(ReviewHistoryContentSource.class),
                members,
                descriptors,
                List.of(),
                mapper,
                Clock.systemUTC(),
                new AppliesWhenEvaluator(List.of(new ScmCapturedApplicability(mapper))),
                mock(ResultAdmissionService.class),
                catalogs);
    }

    private void captureFiles(ReviewJob job) {
        when(files.prepare(eq(job), any(), any())).thenAnswer(call -> {
            PreparedEvidence evidence = call.getArgument(1);
            var capture = mapper.createObjectNode();
            capture.set("manifest", mapper.valueToTree(evidence.manifest()));
            capture.set("readiness", mapper.valueToTree(call.getArgument(2)));
            capture.set("index", mapper.readTree(evidence.files().get(SandboxLayout.PRACTICES_PREFIX + "index.json")));
            job.setCapture(capture);
            return new PreparedJobInputs(Map.of(), List.of(), List.of(), evidence.manifest(), call.getArgument(2));
        });
    }

    private DefaultReviewExecutionHandler bundledHandler(ReviewJob job, boolean inlineCommentPresent) {
        var assignment = assignments.load(job).getFirst();
        var pullRequests = mock(PullRequestRepository.class);
        var inlineComments = mock(PullRequestReviewCommentRepository.class);
        var delivered = mock(DeliveredPullRequestCommentLookup.class);
        var pullRequest = new PullRequest();
        long sourceId = Objects.requireNonNull(assignment.item().sourceId());
        when(pullRequests.findByIdForReviewContext(sourceId)).thenReturn(Optional.of(pullRequest));
        when(pullRequests.findById(sourceId)).thenReturn(Optional.of(pullRequest));
        when(pullRequests.existsByIdAndDeletedAtIsNull(sourceId)).thenReturn(true);
        when(delivered.findForPullRequest(anyLong(), eq(sourceId), any()))
                .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of()));
        var comment = new PullRequestReviewComment();
        var author = new User();
        author.setLogin("reviewer");
        comment.setAuthor(author);
        comment.setBody("Please explain this branch.");
        comment.setPath("src/Main.java");
        comment.setCreatedAt(Instant.now());
        when(inlineComments.findRecentHumanByPullRequestIdWithAuthor(eq(sourceId), any(), any()))
                .thenReturn(inlineCommentPresent ? List.of(comment) : List.of());
        var pullCapture = new PullRequestContentSource(
                mapper,
                mock(GitRepositoryManager.class),
                pullRequests,
                inlineComments,
                mock(ReviewRepositoryPreparer.class),
                delivered);
        var threads = new ReviewThreadContentSource(
                mapper,
                pullRequests,
                mock(PullRequestReviewThreadRepository.class),
                mock(PullRequestReviewRepository.class));
        var general = new GeneralReviewCommentContentSource(
                mapper, mock(IssueCommentRepository.class), pullRequests, delivered);
        var metadata = mapper.createObjectNode()
                .put("pull_request_id", sourceId)
                .put("repository_id", 1L)
                .put("title", "Explain the branch")
                .put("body", "Describe the change.");
        var fixture = ScmCaptureFixtures.context(metadata, assignment.item().workspaceId(), false);
        var snapshot = ItemFixtures.snapshot(assignment.item(), Map.of())
                .version(assignment.version())
                .build();
        when(source.supports(assignment.item())).thenReturn(true);
        when(source.pin(assignment.item(), assignment.version())).thenReturn(snapshot);
        when(source.capture(eq(snapshot), any())).thenAnswer(call -> {
            CaptureRequest request = call.getArgument(1);
            var capture = new ScmCaptureContext(
                    snapshot, request, fixture.work(), fixture.preparation(), fixture.capturedAt());
            if (request.sources().contains(ReviewThreadContentSource.KIND))
                return threads.capture(capture, request.sources());
            if (request.sources().contains(GeneralReviewCommentContentSource.KIND))
                return general.capture(capture, request.sources());
            return pullCapture.capture(capture, request.sources());
        });
        if (inlineCommentPresent)
            when(source.record(eq(snapshot), any()))
                    .thenReturn(PullRequestContentSource.workRecord(pullRequest, fixture.work(), fixture.capturedAt()));
        captureFiles(job);
        return handler(new WorkspaceContextBuilder(
                List.of(), List.of(source), catalogs, new JobFolderIndexBuilder(catalogs, mapper, Clock.systemUTC())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldEvaluateTheBundledCommentPracticeThroughCapture(boolean inlineCommentPresent) {
        var definition = bundled.catalog().practices().stream()
                .filter(p -> p.slug().equals("engaging-with-inline-review-comments"))
                .findFirst()
                .orElseThrow()
                .definition()
                .definition();
        var pending = pending(definition);
        var job = jobs.findById(pending.jobId()).orElseThrow();
        try (var prepared = bundledHandler(job, inlineCommentPresent).prepareInputs(job)) {
            assertThat(prepared.folderIndex()).isNotNull();
            assertThat(assignments.admissible(job)).hasSize(inlineCommentPresent ? 1 : 0);
            assertThat(Objects.requireNonNull(job.getCapture()).path("index").size())
                    .isEqualTo(inlineCommentPresent ? 1 : 0);
            var report = Objects.requireNonNull(prepared.automatedReviewReadinessReport());
            var check = report.applicability().values().iterator().next();
            assertThat(check.absent()).isEqualTo(!inlineCommentPresent);
            assertThat(check.clauses())
                    .extracting(c -> c.readFrom().value())
                    .containsExactlyInAnyOrder(
                            "scm.review-threads", "scm.pull-request.comments", "scm.general-review-comments");
            assertThat(check.clauses())
                    .extracting(c -> c.result())
                    .containsExactlyInAnyOrder(
                            AppliesWhenCheck.Result.NOT_FOUND,
                            AppliesWhenCheck.Result.NOT_FOUND,
                            inlineCommentPresent ? AppliesWhenCheck.Result.FOUND : AppliesWhenCheck.Result.NOT_FOUND);
            if (!inlineCommentPresent) {
                var output = new ReviewOutputService(
                        assignments, mock(CandidateEvidenceVerifier.class), store, mapper, files, catalogs, members);
                output.prepare(job, new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), 1, List.of(), List.of()))
                        .record(job);
                var resultId = Objects.requireNonNull(job.getCapture())
                        .path("admission")
                        .path("resultIds")
                        .get(0)
                        .asString();
                var recorded = results.findById(UUID.fromString(resultId)).orElseThrow();
                assertThat(recorded.getOutcome()).isEqualTo(ResultOutcome.DOES_NOT_APPLY);
                assertThat(recorded.getExplanation())
                        .isEqualTo(
                                Objects.requireNonNull(definition.appliesWhen()).otherwise());
            }
        }
    }

    @Test
    void shouldAdmitDoesNotApplyWithTheAuthoredReasonWhenChangedPathsDoNotMatch() {
        var pending = pending();
        var job = jobs.findById(pending.jobId()).orElseThrow();
        var handler = handler(job, true, Set.of("src/Main.java"));
        try (var prepared = handler.prepareInputs(job)) {
            assertThat(prepared.folderIndex()).isNotNull();
            assertThat(assignments.admissible(job)).isEmpty();
            assertThat(job.getCapture()).isNotNull();
            assertThat(Objects.requireNonNull(job.getCapture()).path("index").isEmpty())
                    .isTrue();
            var output = new ReviewOutputService(
                    assignments, mock(CandidateEvidenceVerifier.class), store, mapper, files, catalogs, members);
            output.prepare(job, new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), 1, List.of(), List.of()))
                    .record(job);
        }
        var resultId = Objects.requireNonNull(job.getCapture())
                .path("admission")
                .path("resultIds")
                .get(0)
                .asString();
        var recorded = results.findById(UUID.fromString(resultId)).orElseThrow();
        assertThat(recorded.getOutcome()).isEqualTo(ResultOutcome.DOES_NOT_APPLY);
        assertThat(recorded.getExplanation()).isEqualTo(OTHERWISE);
        assertThat(recorded.getPracticeVersionId())
                .isEqualTo(pending.scenario().practice().getCurrentVersion().getId());
    }

    /**
     * The runner reports only the practices it was given. A complete report that leaves out a practice whose
     * Applies when capture proved absent says nothing about its script, because that practice was never staged.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecordNotFinishedOnlyForAStagedPracticeWhenACompleteReportLeavesItOut(boolean swiftChanged) {
        var pending = pending();
        var job = jobs.findById(pending.jobId()).orElseThrow();
        try (var prepared = handler(job, true, Set.of(swiftChanged ? "src/App.swift" : "src/Main.java"))
                .prepareInputs(job)) {
            assertThat(prepared.folderIndex()).isNotNull();
            assertThat(Objects.requireNonNull(job.getCapture()).path("index").size())
                    .isEqualTo(swiftChanged ? 1 : 0);
        }
        byte[] report = "{\"schemaVersion\":2,\"truncated\":false,\"practices\":[]}".getBytes(StandardCharsets.UTF_8);
        PracticeScriptRunRecorderFixtures.record(
                new PiResultParser(mapper, meters),
                scriptRuns,
                meters,
                mapper,
                assignments,
                job,
                report,
                Map.of(),
                Instant.now());

        var recorded =
                scriptRuns.findByWorkspaceIdAndJobIdAndAttempt(job.getWorkspaceId(), job.getId(), job.getAttempt());
        if (swiftChanged) {
            assertThat(recorded).singleElement().satisfies(run -> {
                assertThat(run.getId().getPracticeId())
                        .isEqualTo(pending.scenario().practice().getId());
                assertThat(run.getStatus()).isEqualTo(ScriptRunStatus.NOT_FINISHED);
            });
        } else {
            assertThat(recorded).isEmpty();
        }
    }

    @Test
    void shouldStageThePracticeForReviewWhenChangedPathsMatch() {
        var pending = pending();
        var job = jobs.findById(pending.jobId()).orElseThrow();
        try (var prepared = handler(job, true, Set.of("src/App.swift")).prepareInputs(job)) {
            assertThat(prepared.folderIndex()).isNotNull();
            assertThat(assignments.admissible(job)).hasSize(1);
            assertThat(job.getCapture()).isNotNull();
            assertThat(Objects.requireNonNull(job.getCapture()).path("index").size())
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldRecordTheCaptureFailureWithoutAnInapplicabilityResultWhenTheSourceIsUnavailable() {
        var pending = pending();
        var job = jobs.findById(pending.jobId()).orElseThrow();
        var handler = handler(job, false, Set.of());
        assertThatThrownBy(() -> handler.prepareInputs(job)).isInstanceOf(InsufficientEvidenceException.class);
        var practice = practices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        job.getWorkspaceId(),
                        job.getReviewId(),
                        pending.scenario().practice().getId())
                .orElseThrow();
        assertThat(practice.getReason()).isEqualTo("readiness_refused");
        assertThat(practice.getCaptureDecision()).isNotNull();
        assertThat(Objects.requireNonNull(practice.getCaptureDecision())
                        .path("applicability")
                        .isMissingNode())
                .isTrue();
        assertThat(Objects.requireNonNull(practice.getCaptureDecision())
                        .path("reasonCodes")
                        .toString())
                .contains("DECLARED_EVIDENCE_INSUFFICIENT");
        assertThat(job.getCapture()).isNotNull();
        assertThat(Objects.requireNonNull(job.getCapture())
                        .path("readiness")
                        .path("applicability")
                        .isEmpty())
                .isTrue();
        assertThat(Objects.requireNonNull(job.getCapture())
                        .path("manifest")
                        .path("sources")
                        .get(0)
                        .path("state")
                        .path("reasonCode")
                        .asString())
                .isEqualTo("NO_PROVIDER");
        assertThat(assignments.admissible(job)).isEmpty();
        assertThat(proof.forAttempt(job.getWorkspaceId(), job.getReviewId(), job.getId(), job.getAttempt()))
                .isEmpty();
    }

    /**
     * Scope: the handler and the real assignment store, not the executor. It drives the same admissible lookup and
     * PREPARATION_FAILED call that terminal settlement makes after an evidence refusal with no readiness report.
     */
    @Test
    void shouldKeepTheUnsupportedSourceRefusalThroughSettlementWhenNoPracticeCanBeCaptured() {
        var d = DefinitionTestFixtures.definition();
        var scenario = data.scenario(d);
        // A version stored by an earlier release. The current validator rejects only new versions, so this
        // seeds the row the way PracticeVersionService.append stores one, without validation.
        var retained = mapper.valueToTree(new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                null,
                d.about(),
                new PracticeReads(
                        "2.0.0",
                        List.of(
                                new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.COMPLETE),
                                new PracticeReadSource(new SourceKind("outline.documents"), ReadNeed.OPTIONAL))),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                d.scan(),
                d.lineNotes(),
                d.guidance()));
        var practiceRow = practiceRows.findById(scenario.practice().getId()).orElseThrow();
        practiceRow.setCurrentVersion(versionRows.saveAndFlush(new PracticeVersion(practiceRow, 2, retained, null)));
        practiceRows.saveAndFlush(practiceRow);
        var pending = pending(new ReviewIntegrationData.Scenario(
                scenario.workspace(),
                scenario.account(),
                practiceRows.findById(practiceRow.getId()).orElseThrow(),
                scenario.item()));
        var job = jobs.findById(pending.jobId()).orElseThrow();
        var assignment = assignments.load(job).getFirst();
        when(source.supports(assignment.item())).thenReturn(true);
        var handler = handler(new WorkspaceContextBuilder(
                List.of(), List.of(source), catalogs, new JobFolderIndexBuilder(catalogs, mapper, Clock.systemUTC())));

        assertThatThrownBy(() -> handler.prepareInputs(job))
                .isInstanceOfSatisfying(
                        InsufficientEvidenceException.class,
                        refusal -> assertThat(refusal.preparedInputs().automatedReviewReadinessReport())
                                .isNull());
        // Terminal settlement: no readiness report, so every pinned practice is still admissible and is failed.
        var admissible = assignments.admissible(job);
        assertThat(admissible).extracting(a -> a.id()).containsExactly(assignment.id());
        for (var settled : admissible)
            members.fail(
                    job.getWorkspaceId(),
                    settled.id(),
                    ReviewStopReason.PREPARATION_FAILED,
                    "The execution ended without an admitted result");

        var practice = practices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        job.getWorkspaceId(),
                        job.getReviewId(),
                        assignment.practice().practiceId())
                .orElseThrow();
        assertThat(practice.getStatus()).isEqualTo(ReviewPracticeStatus.WITHHELD);
        assertThat(practice.getReason()).isEqualTo(DefaultReviewExecutionHandler.SOURCE_CONTRACT_UNSUPPORTED);
        assertThat(Objects.requireNonNull(practice.getCaptureDecision())
                        .path("unsupportedSources")
                        .get(0)
                        .path("source")
                        .asString())
                .isEqualTo("outline.documents");
        var member = reviewItems
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        job.getWorkspaceId(),
                        job.getReviewId(),
                        assignment.practice().practiceId(),
                        assignment.item().itemId())
                .orElseThrow();
        assertThat(member.getState()).isEqualTo(ReviewItemState.WITHHELD);
        assertThat(member.getReason()).isEqualTo(DefaultReviewExecutionHandler.SOURCE_CONTRACT_UNSUPPORTED);
        assertThat(proof.forAttempt(job.getWorkspaceId(), job.getReviewId(), job.getId(), job.getAttempt()))
                .isEmpty();
    }

    /**
     * A retry captures the review again. What the first attempt withheld at capture keeps its state and reason, and
     * the practices still open run.
     */
    @Test
    void shouldKeepTheFirstAttemptsCaptureWithholdingWhenARetryCapturesAgain() {
        var pending = pending();
        var scenario = pending.scenario();
        var d = DefinitionTestFixtures.definition();
        var unsupported = practiceVersions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "retry-unsupported-" + UUID.randomUUID().toString().substring(0, 8),
                scenario.item().getKind(),
                d,
                null,
                0,
                scenario.account().getId()));
        // A version stored by an earlier release, which reads a source that this item cannot supply.
        var retained = mapper.valueToTree(new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                null,
                d.about(),
                new PracticeReads(
                        "2.0.0",
                        List.of(
                                new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.COMPLETE),
                                new PracticeReadSource(new SourceKind("outline.documents"), ReadNeed.OPTIONAL))),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                d.scan(),
                d.lineNotes(),
                d.guidance()));
        var row = practiceRows.findById(unsupported.getId()).orElseThrow();
        row.setCurrentVersion(versionRows.saveAndFlush(new PracticeVersion(row, 2, retained, null)));
        practiceRows.saveAndFlush(row);
        admissions.assigned(pending, practiceRows.findById(row.getId()).orElseThrow());
        long ready = scenario.practice().getId();

        var first = jobs.findById(pending.jobId()).orElseThrow();
        try (var prepared = handler(first, true, Set.of("src/App.swift")).prepareInputs(first)) {
            assertThat(prepared.folderIndex()).isNotNull();
            assertThat(assignments.admissible(first))
                    .extracting(a -> a.practice().practiceId())
                    .containsExactly(ready);
        }
        var withheld = reviewItems
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        first.getWorkspaceId(),
                        first.getReviewId(),
                        row.getId(),
                        scenario.item().getId())
                .orElseThrow();
        assertThat(withheld.getState()).isEqualTo(ReviewItemState.WITHHELD);
        assertThat(withheld.getReason()).isEqualTo(DefaultReviewExecutionHandler.SOURCE_CONTRACT_UNSUPPORTED);

        running(pending, 2);
        var retry = jobs.findById(pending.jobId()).orElseThrow();
        try (var prepared = handler(retry, true, Set.of("src/App.swift")).prepareInputs(retry)) {
            assertThat(prepared.folderIndex()).isNotNull();
            assertThat(assignments.admissible(retry))
                    .extracting(a -> a.practice().practiceId())
                    .containsExactly(ready);
            assertThat(Objects.requireNonNull(retry.getCapture()).path("index").size())
                    .isEqualTo(1);
            assertThat(Objects.requireNonNull(retry.getCapture())
                            .path("readiness")
                            .path("decisions")
                            .valueStream()
                            .map(decision -> decision.path("practiceSlug").asString()))
                    .containsExactly(scenario.practice().getSlug());
        }
        var kept = reviewItems
                .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        retry.getWorkspaceId(),
                        retry.getReviewId(),
                        row.getId(),
                        scenario.item().getId())
                .orElseThrow();
        assertThat(kept.getState()).isEqualTo(ReviewItemState.WITHHELD);
        assertThat(kept.getReason()).isEqualTo(DefaultReviewExecutionHandler.SOURCE_CONTRACT_UNSUPPORTED);
        assertThat(kept.getAttemptedAt()).isEqualTo(withheld.getAttemptedAt());
    }

    /** The store names the practices of the job that an earlier attempt ended, not the open ones or another job's. */
    @Test
    void shouldNameOnlyThePracticesOfTheJobWhoseMembershipEnded() {
        var pending = pending();
        var scenario = pending.scenario();
        var ended = practiceVersions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "retry-ended-" + UUID.randomUUID().toString().substring(0, 8),
                scenario.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                scenario.account().getId()));
        admissions.assigned(pending, ended);
        admissions.withheld(pending, DefaultReviewExecutionHandler.SOURCE_CONTRACT_UNSUPPORTED);
        long workspaceId = scenario.workspace().getId();
        var reviewId = pending.review().getId();
        assertThat(members.endedPractices(workspaceId, reviewId, pending.jobId()))
                .isEmpty();

        members.withhold(
                workspaceId,
                new ReviewAssignmentId(reviewId, ended.getId(), scenario.item().getId()),
                CandidateRefusal.MALFORMED_CANDIDATE,
                "refused");

        assertThat(members.endedPractices(workspaceId, reviewId, pending.jobId()))
                .containsExactly(ended.getId());
    }
}
