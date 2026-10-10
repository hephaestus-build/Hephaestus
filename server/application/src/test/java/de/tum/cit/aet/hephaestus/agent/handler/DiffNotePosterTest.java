package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineFeedback;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Readback;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.node.JsonNodeFactory;

class DiffNotePosterTest extends BaseUnitTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final PullRequestCommentPoster commentPoster = mock(PullRequestCommentPoster.class);
    private final PracticeFeedbackCommentFormatter commentFormatter = new PracticeFeedbackCommentFormatter(
            new ApplicationProperties(null, new ApplicationProperties.Webapp("https://hephaestus.example")),
            teamWorkspace(),
            new WorkspaceSubdomainProperties(false, ""));

    /** Every receipt the attempt stored before a request, in order. */
    private final List<List<DeliveredSignal>> stored = new ArrayList<>();

    private FeedbackDispatchStateMachine.Reservation recordAttempt(List<DeliveredSignal> receipt) {
        stored.add(List.copyOf(receipt));
        return FeedbackDispatchStateMachine.Reservation.RESERVED;
    }

    /** The work still sits at the commit the package was reviewed at. */
    private static PracticeFeedbackDeliveryPolicy.ReviewedRevision atReviewed() {
        return PracticeFeedbackDeliveryPolicy.ReviewedRevision.CURRENT;
    }

    /** A freshly sealed automatic package: its own marker, and no inline write begun. */
    private static final InlinePackageScope FRESH =
            new InlinePackageScope(InlinePackageScope.automaticMarker(JOB), null, true, true, null);

    /** The same package once an inline write began: an unrecorded note is no longer provably unrequested. */
    private static final InlinePackageScope STARTED =
            new InlinePackageScope(InlinePackageScope.automaticMarker(JOB), null, true, false, null);

    private static final DiffNote A =
            new DiffNote("src/A.java", 10, null, "First note", "observation:a:0", List.of("a"));
    private static final DiffNote B =
            new DiffNote("src/B.java", 20, null, "Second note", "observation:b:0", List.of("b"));

    @Test
    void shouldPostASealedAutomaticPackageUnderItsOwnMarkerWithTheOriginalKeysAndText() {
        ScriptedChannel channel = new ScriptedChannel();
        DiffNote multi = new DiffNote("src/A.java", 10, 14, "Sealed text", "observation:a:0", List.of("a"));

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(), FRESH, List.of(multi), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        InlineFeedback sent = channel.given.getFirst().getFirst();
        FeedbackAnchor.DiffAnchor anchor = (FeedbackAnchor.DiffAnchor) sent.anchor();
        assertThat(anchor.startLine()).isEqualTo(10);
        assertThat(anchor.newLineNumber()).isEqualTo(14);
        assertThat(sent.body()).isEqualTo("Sealed text");
        assertThat(sent.marker()).isEqualTo("<!-- hephaestus-review-package:" + JOB + " -->");
        assertThat(sent.deliveryKey()).isEqualTo("observation:a:0");
        assertThat(channel.readback).isEqualTo(Readback.AUTHORED);
        assertThat(result.complete()).isTrue();
        assertThat(result.signals())
                .singleElement()
                .satisfies(signal -> assertThat(signal.acknowledged()).isTrue());
    }

    @Test
    void shouldKeepTheHistoricalMarkerKeyAndDisclosureForAnAutomaticPackageSealedBeforeIt() {
        ScriptedChannel channel = new ScriptedChannel();
        InlinePackageScope historical =
                new InlinePackageScope(InlinePackageScope.SHARED_MARKER, null, false, true, null);

        poster(channel)
                .deliverPackage(
                        job(), historical, List.of(A), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        InlineFeedback sent = channel.given.getFirst().getFirst();
        assertThat(sent.marker()).isEqualTo("<!-- hephaestus-diff-note -->");
        assertThat(sent.deliveryKey()).isEqualTo("observation:a:0");
        assertThat(sent.body())
                .startsWith("First note")
                .contains("<sub>AI-generated feedback. Answer or dispute it in"
                        + " [Hephaestus](https://hephaestus.example/w/team/feedback/scm.pull_request/42).</sub>");
        assertThat(channel.readback).isEqualTo(Readback.SHARED);
    }

    @Test
    void shouldPostAnApprovedPackageUnderItsMarkerAndOrdinalKeys() {
        ScriptedChannel channel = new ScriptedChannel();
        UUID feedbackId = UUID.randomUUID();
        InlinePackageScope approved = new InlinePackageScope(
                InlinePackageScope.approvedMarker(feedbackId), feedbackId, true, true, "proposal-sha");

        poster(channel)
                .deliverPackage(
                        job(), approved, List.of(A, B), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(channel.given.getFirst())
                .extracting(InlineFeedback::deliveryKey)
                .containsExactly("approved:" + feedbackId + ":0", "approved:" + feedbackId + ":1");
        assertThat(channel.given.getFirst().getFirst().marker())
                .isEqualTo("<!-- hephaestus-approved-package:" + feedbackId + " -->");
        assertThat(channel.given.getFirst().getFirst().body()).isEqualTo("First note");
        assertThat(channel.target).extracting(FeedbackTarget::reviewedRevision).isEqualTo("proposal-sha");
    }

    @Test
    void shouldKeepAnApprovedNotesOriginalOrdinalWhenOnlyItIsStillOwed() {
        ScriptedChannel channel = new ScriptedChannel();
        UUID feedbackId = UUID.randomUUID();
        InlinePackageScope approved =
                new InlinePackageScope(InlinePackageScope.approvedMarker(feedbackId), feedbackId, true, false, null);
        String first = "approved:" + feedbackId + ":0";
        String second = "approved:" + feedbackId + ":1";
        List<DeliveredSignal> persisted =
                List.of(DeliveredSignal.attempted(first, anchor(A)), DeliveredSignal.notSent(second, anchor(B)));

        poster(channel)
                .deliverPackage(
                        job(), approved, List.of(A, B), persisted, DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(channel.lookedUp).containsExactly(first);
        assertThat(channel.requested).containsExactly(second);
        assertThat(stored.getFirst())
                .filteredOn(signal -> first.equals(signal.deliveryKey()))
                .singleElement()
                .satisfies(signal -> assertThat(signal.unconfirmed()).isTrue());
    }

    @Test
    void shouldKeepAnUnrecordedNoteUnknownWhenAStartedPackageFencesAnother() {
        ScriptedChannel channel = new ScriptedChannel();
        DiffNote c = new DiffNote("src/C.java", 30, null, "Third note", "observation:c:0", List.of("c"));
        List<DeliveredSignal> persisted = List.of(
                new DeliveredSignal("observation:a:0", anchor(A), Disposition.FAILED, null, null),
                DeliveredSignal.notSent("observation:b:0", anchor(B)));

        poster(channel)
                .deliverPackage(
                        job(),
                        STARTED,
                        List.of(A, B, c),
                        persisted,
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);

        assertThat(channel.requested).containsExactly("observation:b:0");
        assertThat(channel.lookedUp).containsExactly("observation:a:0", "observation:c:0");
        assertThat(signal(stored.getFirst(), A).writeMayHaveStarted()).isNull();
        assertThat(signal(stored.getFirst(), c).writeMayHaveStarted()).isNull();
        assertThat(signal(stored.getFirst(), c).unconfirmed()).isTrue();
    }

    @Test
    void shouldStoreEveryNoteBeforeTheFirstRequestAndTheReturnedHandleBeforeTheNext() {
        ScriptedChannel channel = new ScriptedChannel();

        poster(channel)
                .deliverPackage(
                        job(), FRESH, List.of(A, B), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(stored).hasSize(2);
        assertThat(signal(stored.get(0), A).unconfirmed()).isTrue();
        assertThat(signal(stored.get(0), B).writeMayHaveStarted()).isFalse();
        assertThat(signal(stored.get(1), A).externalRef()).isEqualTo("note-observation:a:0");
        assertThat(signal(stored.get(1), B).unconfirmed()).isTrue();
    }

    @Test
    void shouldReadBackANoteWhoseHandleWasLostAndWriteTheOneNeverRequestedAfterAStop() {
        ScriptedChannel first = new ScriptedChannel();
        first.stopAfter = "observation:a:0";
        poster(first)
                .deliverPackage(
                        job(), FRESH, List.of(A, B), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);
        List<DeliveredSignal> persisted = stored.getLast();

        ScriptedChannel second = new ScriptedChannel();
        second.copies.put("observation:a:0", "note-a");
        DiffNotePoster.DiffNoteResult result = poster(second)
                .deliverPackage(
                        job(), STARTED, List.of(A, B), persisted, DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(first.requested).containsExactly("observation:a:0");
        assertThat(second.requested).containsExactly("observation:b:0");
        assertThat(signal(result.signals(), A).externalRef()).isEqualTo("note-a");
        assertThat(result.complete()).isTrue();
    }

    @Test
    void shouldNeverRequestALostNoteAgainEvenWhenAScanFindsNothingButStillWriteTheUnrequestedOne() {
        ScriptedChannel channel = new ScriptedChannel();
        List<DeliveredSignal> persisted = List.of(
                DeliveredSignal.attempted("observation:a:0", anchor(A)),
                DeliveredSignal.notSent("observation:b:0", anchor(B)));

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(), STARTED, List.of(A, B), persisted, DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(channel.lookedUp).containsExactly("observation:a:0");
        assertThat(channel.requested).containsExactly("observation:b:0");
        assertThat(result.complete()).isFalse();
        assertThat(result.unconfirmed()).isTrue();
        assertThat(signal(result.signals(), A).unconfirmed()).isTrue();
        assertThat(signal(result.signals(), B).acknowledged()).isTrue();
    }

    @Test
    void shouldLeaveEveryNoteOfALostBatchToReadbackAndNeverReissueTheOnesNotFound() {
        ScriptedChannel first = new ScriptedChannel();
        first.batch = true;
        first.lostResponses.addAll(Set.of("observation:a:0", "observation:b:0"));
        poster(first)
                .deliverPackage(
                        job(), FRESH, List.of(A, B), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        ScriptedChannel second = new ScriptedChannel();
        second.copies.put("observation:a:0", "note-a");
        DiffNotePoster.DiffNoteResult result = poster(second)
                .deliverPackage(
                        job(),
                        STARTED,
                        List.of(A, B),
                        stored.getLast(),
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);

        assertThat(first.requested).containsExactlyInAnyOrder("observation:a:0", "observation:b:0");
        assertThat(second.requested).isEmpty();
        assertThat(signal(result.signals(), A).acknowledged()).isTrue();
        assertThat(signal(result.signals(), B).unconfirmed()).isTrue();
        assertThat(result.unconfirmed()).isTrue();
    }

    @Test
    void shouldRetryANoteRefusedBeforeItsRequestButOnlyReadBackOneWhoseRequestMayHaveLeft() {
        ScriptedChannel refused = new ScriptedChannel();
        refused.refusedBeforeRequest.add("observation:a:0");
        DiffNotePoster.DiffNoteResult preflight = poster(refused)
                .deliverPackage(
                        job(), FRESH, List.of(A), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(refused.requested).isEmpty();
        assertThat(preflight.unconfirmed()).isFalse();
        ScriptedChannel next = new ScriptedChannel();
        poster(next)
                .deliverPackage(
                        job(),
                        STARTED,
                        List.of(A),
                        preflight.signals(),
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);
        assertThat(next.requested).containsExactly("observation:a:0");

        stored.clear();
        ScriptedChannel stopped = new ScriptedChannel();
        stopped.throwAfterFence = true;
        DiffNotePoster.DiffNoteResult transport = poster(stopped)
                .deliverPackage(
                        job(), FRESH, List.of(A), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(transport.unconfirmed()).isTrue();
        ScriptedChannel after = new ScriptedChannel();
        poster(after)
                .deliverPackage(
                        job(),
                        STARTED,
                        List.of(A),
                        transport.signals(),
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);
        assertThat(after.requested).isEmpty();
        assertThat(after.lookedUp).containsExactly("observation:a:0");
    }

    @Test
    void shouldNotCountAPlacementWithoutANativeIdAsDelivered() {
        ScriptedChannel channel = new ScriptedChannel();
        channel.withoutIds.add("observation:a:0");

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(), FRESH, List.of(A), List.of(), DiffNotePosterTest::atReviewed, this::recordAttempt);

        assertThat(result.complete()).isFalse();
        assertThat(result.unconfirmed()).isTrue();
        assertThat(signal(result.signals(), A).acknowledged()).isFalse();
    }

    @Test
    void shouldOnlyReadBackAHistoricalPackageWhoseInlineWriteMayHaveBegunWithoutReceipts() {
        ScriptedChannel channel = new ScriptedChannel();
        InlinePackageScope historicalStarted =
                new InlinePackageScope(InlinePackageScope.SHARED_MARKER, null, false, false, null);

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(),
                        historicalStarted,
                        List.of(A, B),
                        List.of(),
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);

        assertThat(channel.requested).isEmpty();
        assertThat(channel.lookedUp).containsExactly("observation:a:0", "observation:b:0");
        assertThat(result.unconfirmed()).isTrue();
        assertThat(stored).isEmpty();
    }

    @Test
    void shouldDeliverAHistoricalPackageThatProvablyBeganNoInlineWriteRecordingItsWholeKeySetFirst() {
        ScriptedChannel channel = new ScriptedChannel();
        channel.refusedBeforeRequest.add("observation:b:0");
        InlinePackageScope historicalUntouched =
                new InlinePackageScope(InlinePackageScope.SHARED_MARKER, null, false, true, null);

        poster(channel)
                .deliverPackage(
                        job(),
                        historicalUntouched,
                        List.of(A, B),
                        List.of(),
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);

        assertThat(channel.requested).containsExactly("observation:a:0");
        assertThat(stored.getFirst())
                .extracting(DeliveredSignal::deliveryKey)
                .containsExactly("observation:a:0", "observation:b:0");
        assertThat(signal(stored.getFirst(), B).writeMayHaveStarted()).isFalse();
    }

    @Test
    void shouldRequestNothingOnceTheReceiptCannotBeStored() {
        ScriptedChannel channel = new ScriptedChannel();

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(),
                        FRESH,
                        List.of(A),
                        List.of(),
                        DiffNotePosterTest::atReviewed,
                        receipt -> FeedbackDispatchStateMachine.Reservation.LEASE_LOST);

        assertThat(channel.requested).isEmpty();
        assertThat(result.leaseLost()).isTrue();
        assertThat(result.unconfirmed()).isFalse();
    }

    @Test
    void shouldPreserveTheFinalPolicyRefusalWithoutRequestingTheNote() {
        ScriptedChannel channel = new ScriptedChannel();
        var refused = FeedbackDispatchStateMachine.Reservation.refused(FeedbackSuppressionReason.ARTIFACT_CLOSED);
        var result = poster(channel)
                .deliverPackage(
                        job(), FRESH, List.of(A), List.of(), DiffNotePosterTest::atReviewed, receipt -> refused);
        assertThat(channel.requested).isEmpty();
        assertThat(result.refusalReason()).isEqualTo(refused.refusal());
        assertThat(result.revisionChanged()).isFalse();
        assertThat(result.leaseLost()).isFalse();
        assertThat(result.unconfirmed()).isFalse();
    }

    /** The early read saw the reviewed work; the final check under the work's lock did not. */
    @ParameterizedTest
    @EnumSource(
            value = FeedbackDispatchStateMachine.ReservationStatus.class,
            names = {"STALE", "UNKNOWN"})
    void shouldRequestNothingWhenTheLockedCheckRefusesWhatTheEarlyReadAllowed(
            FeedbackDispatchStateMachine.ReservationStatus refusedStatus) {
        var refused = new FeedbackDispatchStateMachine.Reservation(refusedStatus, null);
        ScriptedChannel channel = new ScriptedChannel();

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(), FRESH, List.of(A), List.of(), DiffNotePosterTest::atReviewed, receipt -> refused);

        assertThat(channel.requested).isEmpty();
        assertThat(result.leaseLost()).isFalse();
        assertThat(result.revisionChanged())
                .as("only a proven difference is stale; an unknown one leaves the note owed")
                .isEqualTo(refused.equals(FeedbackDispatchStateMachine.Reservation.STALE));
        assertThat(result.complete()).isFalse();
    }

    @Test
    void shouldReadBackALostNoteButRequestNothingOnceTheWorkMovedPastItsReviewedCommit() {
        ScriptedChannel channel = new ScriptedChannel();
        channel.copies.put("observation:a:0", "note-a");
        List<DeliveredSignal> persisted = List.of(
                DeliveredSignal.attempted("observation:a:0", anchor(A)),
                DeliveredSignal.notSent("observation:b:0", anchor(B)));

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(),
                        STARTED,
                        List.of(A, B),
                        persisted,
                        () -> PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED,
                        this::recordAttempt);

        assertThat(channel.lookedUp).containsExactly("observation:a:0");
        assertThat(channel.requested).isEmpty();
        assertThat(stored).isEmpty();
        assertThat(result.revisionChanged()).isTrue();
        assertThat(result.leaseLost()).isFalse();
        assertThat(result.unconfirmed()).isFalse();
        assertThat(signal(result.signals(), A).acknowledged()).isTrue();
        assertThat(signal(result.signals(), B).acknowledged()).isFalse();
        assertThat(signal(result.signals(), B).writeMayHaveStarted()).isFalse();
    }

    @Test
    void shouldLeaveANoteOwedWithoutCallingTheWorkChangedWhenItsHeadCannotBeCompared() {
        ScriptedChannel channel = new ScriptedChannel();

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(),
                        FRESH,
                        List.of(A),
                        List.of(),
                        () -> PracticeFeedbackDeliveryPolicy.ReviewedRevision.UNKNOWN,
                        this::recordAttempt);

        assertThat(channel.requested).isEmpty();
        assertThat(stored).isEmpty();
        assertThat(result.revisionChanged()).isFalse();
        assertThat(result.complete()).isFalse();
        assertThat(result.unconfirmed()).isFalse();
    }

    @Test
    void shouldPostNothingForABlankNoteAndNotHoldThePackageOpenForIt() {
        ScriptedChannel channel = new ScriptedChannel();
        DiffNote blank = new DiffNote("src/A.java", 10, null, "   ", "observation:blank:0", List.of("blank"));

        DiffNotePoster.DiffNoteResult result = poster(channel)
                .deliverPackage(
                        job(),
                        FRESH,
                        List.of(blank, A),
                        List.of(),
                        DiffNotePosterTest::atReviewed,
                        this::recordAttempt);

        assertThat(channel.requested).containsExactly("observation:a:0");
        assertThat(result.complete()).isTrue();
        assertThat(result.unconfirmed()).isFalse();
    }

    @Test
    void duplicateChannelKind_inConstructor_throws() {
        InlineFeedbackChannel a = mock(InlineFeedbackChannel.class);
        InlineFeedbackChannel b = mock(InlineFeedbackChannel.class);
        when(a.kind()).thenReturn(IntegrationKind.GITLAB);
        when(b.kind()).thenReturn(IntegrationKind.GITLAB);

        assertThatThrownBy(() -> new DiffNotePoster(commentPoster, commentFormatter, List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate InlineFeedbackChannel for kind");
    }

    private DiffNotePoster poster(ScriptedChannel channel) {
        when(commentPoster.buildTarget(any(), eq(IntegrationKind.GITLAB), eq(1L)))
                .thenReturn(new FeedbackTarget(
                        new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", null));
        return new DiffNotePoster(commentPoster, commentFormatter, List.of(channel));
    }

    private static AgentJob job() {
        AgentJob job = TestEntities.agentJob();
        Workspace ws = new Workspace();
        ws.setId(1L);
        job.setWorkspace(ws);
        job.setIntegrationKind(IntegrationKind.GITLAB);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(JsonNodeFactory.instance.objectNode().put("pull_request_id", 42L));
        return job;
    }

    private static FeedbackAnchor.DiffAnchor anchor(DiffNote note) {
        return FeedbackAnchor.DiffAnchor.singleLine(note.filePath(), note.startLine());
    }

    private static DeliveredSignal signal(List<DeliveredSignal> signals, DiffNote note) {
        return signals.stream()
                .filter(signal -> String.valueOf(note.deliveryKey()).equals(signal.deliveryKey()))
                .findFirst()
                .orElseThrow();
    }

    /** Every workspace is "team": what the footer's link needs of the workspace. */
    private static WorkspaceSummaryQuery teamWorkspace() {
        WorkspaceSummaryQuery workspaces = mock(WorkspaceSummaryQuery.class);
        when(workspaces.findById(anyLong()))
                .thenReturn(Optional.of(new WorkspaceSummaryQuery.WorkspaceSummary(1L, "team", "Team")));
        return workspaces;
    }

    /**
     * A provider as the immutable contract describes it: one fenced create per note, or one fenced batch, with
     * each request's outcome scripted by key; a lookup answers from {@code copies}.
     */
    private static final class ScriptedChannel implements InlineFeedbackChannel {

        final List<List<InlineFeedback>> given = new ArrayList<>();
        final List<String> requested = new ArrayList<>();
        final List<String> lookedUp = new ArrayList<>();
        final Map<String, String> copies = new HashMap<>();
        final Set<String> lostResponses = new HashSet<>();
        final Set<String> withoutIds = new HashSet<>();
        final Set<String> refusedBeforeRequest = new HashSet<>();
        boolean batch;
        boolean throwAfterFence;

        @Nullable
        String stopAfter;

        @Nullable
        Readback readback;

        @Nullable
        FeedbackTarget target;

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITLAB;
        }

        @Override
        public InlineResult postImmutablePackage(
                FeedbackTarget target, List<InlineFeedback> items, Readback readback, WriteFence fence) {
            this.readback = readback;
            this.target = target;
            given.add(items);
            List<DeliveredSignal> done = new ArrayList<>();
            if (batch) {
                if (!fence.beforeCreate(items, done)) return InlineResult.of(notSent(items));
                items.forEach(item -> done.add(request(item)));
                return InlineResult.of(done);
            }
            for (InlineFeedback item : items) {
                if (refusedBeforeRequest.contains(item.deliveryKey())) {
                    done.add(DeliveredSignal.notSent(item.deliveryKey(), item.anchor()));
                    continue;
                }
                if (!fence.beforeCreate(List.of(item), List.copyOf(done))) {
                    done.add(DeliveredSignal.notSent(item.deliveryKey(), item.anchor()));
                    continue;
                }
                if (throwAfterFence) throw new IllegalStateException("connection reset after the request left");
                done.add(request(item));
                if (String.valueOf(item.deliveryKey()).equals(stopAfter)) {
                    throw new IllegalStateException("the process stopped");
                }
            }
            return InlineResult.of(done);
        }

        @Override
        public List<DeliveredSignal> findPosted(FeedbackTarget target, List<InlineFeedback> items, Readback readback) {
            this.readback = readback;
            List<DeliveredSignal> found = new ArrayList<>();
            for (InlineFeedback item : items) {
                lookedUp.add(String.valueOf(item.deliveryKey()));
                String id = copies.get(item.deliveryKey());
                if (id != null) {
                    found.add(new DeliveredSignal(
                            item.deliveryKey(), item.anchor(), Disposition.PRESERVED_EXISTING, id, null));
                }
            }
            return found;
        }

        private DeliveredSignal request(InlineFeedback item) {
            String key = String.valueOf(item.deliveryKey());
            requested.add(key);
            if (lostResponses.contains(key)) return DeliveredSignal.attempted(key, item.anchor());
            if (withoutIds.contains(key)) {
                return new DeliveredSignal(key, item.anchor(), Disposition.POSTED, null, null);
            }
            return new DeliveredSignal(key, item.anchor(), Disposition.POSTED, "note-" + key, null, null, true);
        }

        private static List<DeliveredSignal> notSent(List<InlineFeedback> items) {
            return items.stream()
                    .map(item -> DeliveredSignal.notSent(item.deliveryKey(), item.anchor()))
                    .toList();
        }
    }
}
