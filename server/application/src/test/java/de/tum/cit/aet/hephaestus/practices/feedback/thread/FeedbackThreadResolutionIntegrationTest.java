package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadResolutionWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkFeedbackThreadChangedEvent;
import de.tum.cit.aet.hephaestus.practices.adapter.FeedbackThreadStateRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.AbstractResultAdmissionIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.report.ProblemComparison;
import de.tum.cit.aet.hephaestus.practices.spi.CapturedTextProbe;
import de.tum.cit.aet.hephaestus.practices.spi.CitationProof;
import de.tum.cit.aet.hephaestus.practices.spi.CitationVerificationStatus;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.IntroducedBasis;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A thread on the work follows the problems its note cites: the seal's problem lineage resolves it when all of them are
 * No longer present and reopens it when one comes back, and a person's change hands it to people for good.
 */
class FeedbackThreadResolutionIntegrationTest extends AbstractResultAdmissionIntegrationTest {
    private static final String CHANGED = "changed();";
    private static final String OTHER = "other();";
    private static final String PERSON = "999";

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultStore store;

    @Autowired
    ResultRepository results;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    FeedbackCopyRepository copies;

    @Autowired
    FeedbackBasisRepository basis;

    @Autowired
    FeedbackThreadResolutionRepository resolutions;

    @Autowired
    FeedbackVisibilityService visibility;

    @Autowired
    FeedbackThreadEvidence threadEvidence;

    @Autowired
    FeedbackThreadStateRepository threadStates;

    @Autowired
    PlatformTransactionManager transactionManager;

    // The steps of this test answer permission and the data hold from mocks. The seal keeps the real authorization,
    // and the scenario's account has no provider identity, which the real fence needs; the send's tests cover it.
    private final EvidenceAuthorization evidence = mock(EvidenceAuthorization.class);
    private final PersonDataWriteFence privacy = mock(PersonDataWriteFence.class);

    @Autowired
    ActionExecutor executor;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ApplicationEventPublisher events;

    private final Threads provider = new Threads();
    private FeedbackTarget target;
    private ReviewIntegrationData.Scenario scenario;
    private long developer;
    private FeedbackThreadResolutionAdapter adapter;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        developer = Objects.requireNonNull(scenario.account().getId());
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
        when(privacy.holdForAccountWrite(anyLong())).thenReturn(true);
        when(evidence.permitsAll(anyLong(), anyCollection(), any()))
                .thenAnswer(call -> call.<Collection<ResultFacts>>getArgument(1).stream()
                        .map(facts -> facts.id())
                        .collect(Collectors.toSet()));
        var targets = mock(FeedbackTargetResolver.class);
        target = new FeedbackTarget(
                new ItemRef(
                        scenario.workspace().getId(),
                        scenario.item().getId(),
                        new ItemKind("scm.pull_request"),
                        42L,
                        null,
                        null),
                new IntegrationRef(IntegrationKind.GITHUB, scenario.workspace().getId(), "https://example.test"),
                new ItemVersion("v1", null));
        when(targets.resolveExisting(any())).thenAnswer(call -> target);
        // A proxy applies the steps' own transaction boundaries, as the bean's proxy does.
        var proxy = new ProxyFactory(new FeedbackThreadSteps(
                resolutions,
                copies,
                feedback,
                results,
                visibility,
                basis,
                evidence,
                threadEvidence,
                privacy,
                threadStates,
                new FeedbackThreadMeters(new SimpleMeterRegistry()),
                Clock.systemUTC()));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                (TransactionManager) transactionManager, new AnnotationTransactionAttributeSource()));
        adapter = new FeedbackThreadResolutionAdapter(
                new FeedbackThreadResolutionService((FeedbackThreadSteps) proxy.getProxy(), targets, List.of(provider)),
                mapper);
    }

    @Test
    void shouldResolveAThreadWhenItsProblemIsNoLongerPresentAndReopenItWhenTheProblemComesBack() {
        var first = sealed("v1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));

        sealed("v2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        var resolving = resolution(thread);
        assertThat(resolving.isResolved()).isTrue();
        assertThat(resolving.getState()).isEqualTo(FeedbackThreadResolutionState.PENDING);
        assertThat(run(resolving)).containsInstanceOf(ActionResult.Done.class);
        assertThat(provider.threads).containsEntry(thread, true);
        var resolved = resolution(thread);
        assertThat(resolved.getState()).isEqualTo(FeedbackThreadResolutionState.DONE);
        assertThat(resolved.getActor()).isEqualTo(Threads.BOT);
        // GitHub reopens when the problem comes back, so the resolved note keeps standing as advice.
        assertThat(copies.findClosedAsFixed(scenario.workspace().getId(), List.of(copyOf(thread))))
                .isEmpty();

        // The thread event that the resolve causes names the bot: it is Hephaestus's own, so people keep nothing.
        changed(thread, true, Threads.BOT);
        assertThat(humanControlled(thread)).isFalse();

        // The problem is back: the next review finds it again as New.
        sealed("v3", 10, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        var reopening = resolution(thread);
        assertThat(reopening.isResolved()).isFalse();
        assertThat(reopening.getState()).isEqualTo(FeedbackThreadResolutionState.PENDING);
        assertThat(run(reopening)).containsInstanceOf(ActionResult.Done.class);
        assertThat(provider.threads).containsEntry(thread, false);
        assertThat(provider.changes).containsExactly(thread, thread);
    }

    @Test
    void shouldResolveANoteThatCitesTwoProblemsOnlyWhenBothAreNoLongerPresent() {
        var first = sealed(
                "m1",
                30,
                ResultOutcome.NOT_MET,
                c -> withProblems(c, List.of(problem(0, "problem-1", CHANGED), problem(1, "problem-2", OTHER))),
                CapturedTextProbe.NONE);
        String thread = note(first, problems(first));

        // One problem is gone, the other is still there: the thread stays open.
        sealed(
                "m2",
                20,
                ResultOutcome.NOT_MET,
                c -> withProblems(c, List.of(problem(0, "problem-2", OTHER))),
                cited -> cited.quote().equals(CHANGED)
                        ? CapturedTextProbe.Presence.GONE
                        : CapturedTextProbe.Presence.PRESENT);
        assertThat(resolutions.findAll()).noneMatch(row -> row.getThreadRef().equals(thread));

        // The second one is gone too. The first stays gone, as no later review found it again.
        sealed("m3", 10, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        var resolving = resolution(thread);
        assertThat(resolving.isResolved()).isTrue();
        assertThat(run(resolving)).containsInstanceOf(ActionResult.Done.class);
        assertThat(provider.threads).containsEntry(thread, true);
    }

    @Test
    void shouldHandAThreadToPeopleWhenAPersonResolvesItWhileHephaestusStillWaitsAndNeverReopenIt() {
        var first = sealed("r1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("r2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        var pending = resolution(thread);
        assertThat(pending.getState()).isEqualTo(FeedbackThreadResolutionState.PENDING);

        // A person resolves the thread before Hephaestus's action runs.
        changed(thread, true, PERSON);
        assertThat(humanControlled(thread)).isTrue();
        assertThat(resolution(thread).getStopReason()).isEqualTo(FeedbackThreadStopReason.HUMAN_CONTROLLED);

        assertThat(run(pending)).containsInstanceOf(ActionResult.Cancelled.class);
        assertThat(provider.changes).isEmpty();

        // The problem comes back, but the person owns the thread now.
        sealed("r3", 10, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        assertThat(resolution(thread).getStopReason()).isEqualTo(FeedbackThreadStopReason.HUMAN_CONTROLLED);
        assertThat(provider.changes).isEmpty();
    }

    @Test
    void shouldTakeTheThreadEventOfItsOwnWriteAsItsOwnEvenWhenItArrivesBeforeTheWriteIsConfirmed() {
        var first = sealed("o1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("o2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        provider.during = () -> changed(thread, true, Threads.BOT);

        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Done.class);
        assertThat(humanControlled(thread)).isFalse();
        assertThat(resolution(thread).getState()).isEqualTo(FeedbackThreadResolutionState.DONE);
    }

    @Test
    void shouldTakeAThreadEventWithoutAnActorAsAPersons() {
        var first = sealed("n1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("n2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Done.class);

        changed(thread, true, null);

        assertThat(humanControlled(thread)).isTrue();
        assertThat(resolution(thread).getStopReason()).isEqualTo(FeedbackThreadStopReason.HUMAN_CONTROLLED);
    }

    @Test
    void shouldNotResolveAThreadWhoseEvidenceNoLongerPermitsDelivery() {
        var first = sealed("p1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("p2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        when(evidence.permitsAll(anyLong(), anyCollection(), any())).thenReturn(Set.of());

        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Cancelled.class);
        assertThat(resolution(thread).getStopReason()).isEqualTo(FeedbackThreadStopReason.NOT_PERMITTED);
        assertThat(provider.changes).isEmpty();
    }

    @Test
    void shouldNotResolveAThreadWhereAPersonRepliedAfterTheNote() {
        var first = sealed("q1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("q2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        provider.replied.add(thread);

        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Cancelled.class);
        assertThat(resolution(thread).getStopReason()).isEqualTo(FeedbackThreadStopReason.REPLIED);
        assertThat(provider.changes).isEmpty();
    }

    @Test
    void shouldWithdrawAResolveThatNeverStartedWhenTheProblemComesBackWithoutTouchingTheThread() {
        var first = sealed("u1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("u2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        var pending = resolution(thread);

        sealed("u3", 10, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);

        var withdrawn = resolution(thread);
        assertThat(withdrawn.isResolved()).isFalse();
        assertThat(withdrawn.getState()).isEqualTo(FeedbackThreadResolutionState.DONE);
        assertThat(run(pending)).isEmpty();
        assertThat(provider.changes).isEmpty();
    }

    @Test
    void shouldHandAThreadToPeopleWhenItFindsItResolvedByAPersonWithoutAnEvent() {
        var first = sealed("t1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("t2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        provider.taken.add(thread);

        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Cancelled.class);

        assertThat(resolution(thread).getStopReason()).isEqualTo(FeedbackThreadStopReason.HUMAN_CONTROLLED);
        assertThat(humanControlled(thread)).isTrue();
    }

    @Test
    void shouldReopenAResolvedThreadWhenAPersonMarksItsProofIncorrect() {
        var first = sealed("k1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        var proof = sealed(
                "k2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Done.class);
        // A readback of the resolved thread does not read Hephaestus's own resolve as a person's.
        assertThat(copies.findResolvedByHephaestus(scenario.workspace().getId(), List.of(copyOf(thread))))
                .containsExactly(copyOf(thread));

        store.mark(scenario.workspace().getId(), proof, ResultMarkKind.INCORRECT, developer, "The fix is not there.");

        var reopening = resolution(thread);
        assertThat(reopening.isResolved()).isFalse();
        assertThat(run(reopening)).containsInstanceOf(ActionResult.Done.class);
        assertThat(provider.threads).containsEntry(thread, false);
    }

    @Test
    void shouldNotReopenAThreadWhenAPersonMarksTheResultItsNoteRestsOnIncorrect() {
        // An earlier result makes the note's own result write lineage that names its problem.
        sealed("w0", 40, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        var first = sealed("w1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("w2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Done.class);
        int generation = resolution(thread).getGeneration();

        // The note is wrong, so it is retracted. Its thread must not tell the developer that the problem is back.
        store.mark(
                scenario.workspace().getId(), first, ResultMarkKind.INCORRECT, developer, "This was never a problem.");

        assertThat(resolution(thread).getGeneration()).isEqualTo(generation);
        assertThat(resolution(thread).isResolved()).isTrue();
        assertThat(provider.threads).containsEntry(thread, true);
    }

    @Test
    void shouldKeepAGitLabThreadResolvedWhenItsProblemComesBack() {
        target = new FeedbackTarget(
                target.item(),
                new IntegrationRef(IntegrationKind.GITLAB, scenario.workspace().getId(), "https://gitlab.example.com"),
                target.version());
        provider.kind = IntegrationKind.GITLAB;
        var first = sealed("g1", 30, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);
        String thread = note(first, problems(first));
        sealed("g2", 20, ResultOutcome.MET, c -> withProblems(c, List.of()), cited -> CapturedTextProbe.Presence.GONE);
        assertThat(run(resolution(thread))).containsInstanceOf(ActionResult.Done.class);
        assertThat(resolution(thread).getProvider()).isEqualTo(IntegrationKind.GITLAB);
        int generation = resolution(thread).getGeneration();

        // GitLab cannot tell Hephaestus's own reopen from a person's, so Hephaestus does not reopen there.
        sealed("g3", 10, ResultOutcome.NOT_MET, c -> c, CapturedTextProbe.NONE);

        var kept = resolution(thread);
        assertThat(kept.isResolved()).isTrue();
        assertThat(kept.getState()).isEqualTo(FeedbackThreadResolutionState.DONE);
        assertThat(kept.getGeneration()).isEqualTo(generation);
        assertThat(run(kept)).isEmpty();
        assertThat(provider.changes).containsExactly(thread);
        // The note stays closed as fixed: a readback does not read it as a person's, and the history that the next
        // review on the work reads no longer offers it as advice, so the problem is raised again in a new note.
        assertThat(copies.findResolvedByHephaestus(scenario.workspace().getId(), List.of(copyOf(thread))))
                .containsExactly(copyOf(thread));
        assertThat(copies.findClosedAsFixed(scenario.workspace().getId(), List.of(copyOf(thread))))
                .containsExactly(copyOf(thread));
    }

    private UUID sealed(
            String version,
            int minutesAgo,
            ResultOutcome outcome,
            UnaryOperator<ResultCandidate> shaped,
            CapturedTextProbe probe) {
        var pending =
                admissions.pending(scenario, Instant.now().minus(Duration.ofMinutes(minutesAgo)), developer, version);
        admissions.running(pending, 1);
        when(runtime.activeJobIds(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Set.of(pending.jobId()));
        when(runtime.unsettledJobIds(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Set.of());
        when(runtime.runtime(pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        1,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "test-trace")))));
        var candidate = shaped.apply(admissions.candidate(pending, outcome));
        return store.admit(
                        pending.review().getWorkspaceId(),
                        pending.review().getId(),
                        pending.jobId(),
                        1,
                        List.of(candidate),
                        probe)
                .resultIds()
                .getFirst();
    }

    private static ResultCandidate withProblems(ResultCandidate c, List<ProblemCandidate> problems) {
        return new ResultCandidate(
                c.practiceId(),
                c.practiceVersionId(),
                c.itemId(),
                c.itemVersion(),
                c.developerId(),
                c.outcome(),
                c.summary(),
                c.explanation(),
                c.evidence(),
                c.example(),
                c.coverage(),
                problems);
    }

    private static ProblemCandidate problem(int position, String key, String quote) {
        var digest = ProblemComparison.sha256(quote);
        return new ProblemCandidate(
                position,
                key,
                Severity.MAJOR,
                "Check " + key,
                "The captured change introduces " + key + ".",
                List.of(new EvidenceCitation(
                        new SourceKind("scm.pull-request.diff"),
                        "inputs/sources/scm/diff.patch",
                        "src/Main.java",
                        CitationSide.NEW,
                        "commit-1",
                        1,
                        1,
                        quote,
                        new CitationProof(CitationVerificationStatus.VERIFIED, digest, digest))),
                new IntroducedBasis(0, "The problem is introduced here."),
                "Correct " + key + ".");
    }

    private List<UUID> problems(UUID result) {
        return jdbc.query(
                "SELECT id FROM problem WHERE result_id = ? ORDER BY position",
                (row, index) -> Objects.requireNonNull(row.getObject(1, UUID.class)),
                result);
    }

    /** Posts a delivered note on the work whose composition cites {@code problems}, and returns its thread. */
    private String note(UUID resultId, List<UUID> problems) {
        Result result = results.findById(resultId).orElseThrow();
        long workspaceId = scenario.workspace().getId();
        var now = Instant.now();
        var row = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .reviewId(result.getReviewId())
                .itemId(scenario.item().getId())
                .developerId(developer)
                .destination(FeedbackDestination.ON_WORK)
                .state(FeedbackState.DELIVERED)
                .createdAt(now)
                .deliveredAt(now)
                .build());
        for (int index = 0; index < problems.size(); index++)
            basis.saveAndFlush(FeedbackBasis.builder()
                    .id(FeedbackIds.basis(row.getId(), resultId, problems.get(index)))
                    .workspaceId(workspaceId)
                    .feedbackId(row.getId())
                    .resultId(resultId)
                    .problemId(problems.get(index))
                    .developerId(developer)
                    .ordinal(index)
                    .build());
        String thread = "PRRT_" + UUID.randomUUID();
        UUID copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.LINE_NOTE, 0);
        copies.saveAndFlush(FeedbackCopy.builder()
                .id(copyId)
                .workspaceId(workspaceId)
                .feedbackId(row.getId())
                .kind(FeedbackCopyKind.LINE_NOTE)
                .position(0)
                .body("Check the changed branch.")
                .path("src/Main.java")
                .startLine(1)
                .endLine(1)
                .side(CitationSide.NEW)
                .state(FeedbackCopyState.POSTED)
                .writeKey(copyId.toString())
                .writeMarker(FeedbackIds.writeMarker(copyId))
                .writeStartedAt(now)
                .postedAt(now)
                .externalRef("PRRC_" + UUID.randomUUID())
                .threadRef(thread)
                .createdAt(now)
                .build());
        var support = new ArrayList<Map<String, Object>>();
        for (UUID problem : problems) support.add(Map.of("resultId", resultId, "problemId", problem));
        String trace = mapper.writeValueAsString(Map.of(
                "copies",
                List.of(Map.of("kind", "LINE_NOTE", "position", 0, "basedOn", support)),
                "withheld",
                List.of()));
        jdbc.update("""
                UPDATE review SET checks = jsonb_set(checks, '{feedbackComposition}',
                  COALESCE(checks->'feedbackComposition', '{}'::jsonb) || jsonb_build_object(?::text, ?::jsonb))
                WHERE id = ?
                """, row.getId().toString(), trace, result.getReviewId());
        return thread;
    }

    private UUID copyOf(String thread) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT id FROM feedback_copy WHERE thread_ref = ?", UUID.class, thread));
    }

    private FeedbackThreadResolution resolution(String thread) {
        return resolutions.findAll().stream()
                .filter(row -> row.getThreadRef().equals(thread))
                .findFirst()
                .orElseThrow();
    }

    private Optional<ActionResult> run(FeedbackThreadResolution row) {
        String lane = "feedback-thread:" + row.getId();
        ActionTestSupport.due(jdbc, lane);
        return ActionTestSupport.runLane(executor, jdbc, lane, adapter);
    }

    private void changed(String thread, boolean resolved, @Nullable String actor) {
        transactions.executeWithoutResult(status -> events.publishEvent(new WorkFeedbackThreadChangedEvent(
                scenario.workspace().getId(), scenario.item().getId(), thread, resolved, actor)));
    }

    private boolean humanControlled(String thread) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_or(f.composition->>'humanControlled' = 'true') FROM feedback f
                JOIN feedback_copy c ON c.feedback_id = f.id AND c.workspace_id = f.workspace_id
                WHERE c.thread_ref = ?
                """, Boolean.class, thread));
    }

    /** A provider that holds each thread's state, writing as its bot. */
    private static final class Threads implements ThreadResolutionWriter {
        static final String BOT = "4242";

        final Map<String, Boolean> threads = new HashMap<>();
        final List<String> changes = new ArrayList<>();

        /** Threads where a person replied after the owned note. */
        final Set<String> replied = new HashSet<>();

        /** Threads that a person resolved, with no event that told Hephaestus. */
        final Set<String> taken = new HashSet<>();

        @Nullable
        Runnable during;

        IntegrationKind kind = IntegrationKind.GITHUB;

        @Override
        public IntegrationKind kind() {
            return kind;
        }

        @Override
        public String actor(FeedbackTarget target) {
            return BOT;
        }

        @Override
        public ThreadChange resolveThread(FeedbackTarget target, CopyReceipt copy, boolean resolved, String actor) {
            var hook = during;
            during = null;
            if (hook != null) hook.run();
            String thread = Objects.requireNonNull(copy.threadRef());
            if (taken.contains(thread))
                return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.TAKEN, "A person resolved it");
            if (resolved && replied.contains(thread))
                return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.REPLIED, "A person replied");
            if (!Objects.equals(threads.get(thread), resolved)) {
                threads.put(thread, resolved);
                changes.add(thread);
            }
            return ThreadChange.done(copy.copyId());
        }

        // GitLab cannot tell Hephaestus's own reopen from a person's, as its adapter declares.
        @Override
        public boolean reopensThreads() {
            return kind != IntegrationKind.GITLAB;
        }
    }
}
