package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousPracticePageFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class FeedbackCompositionContextServiceTest {
    final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    final ResultRepository results = mock(ResultRepository.class);
    final PracticeRepository practices = mock(PracticeRepository.class);
    final PracticeVersionRepository versions = mock(PracticeVersionRepository.class);
    final PracticePageSupportReader support = mock(PracticePageSupportReader.class);
    final FeedbackGate gate = mock(FeedbackGate.class);
    final ResultHistoryQuery history = mock(ResultHistoryQuery.class);
    final ConversationReadinessQuery conversation = mock(ConversationReadinessQuery.class);
    final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    final UUID reviewId = UUID.randomUUID();
    final Result current = result(3, reviewId), historical = result(4, UUID.randomUUID());
    final ReviewSnapshot snapshot = mock(ReviewSnapshot.class);
    final ItemRef item = new ItemRef(1, 3, new ItemKind("scm.pull_request"), 42L, null, null);
    final PinnedPractice pin = mock(PinnedPractice.class);
    final PracticeVersion version = TestEntities.practiceVersion(
            8, 1, new JsonMapper().valueToTree(DefinitionTestFixtures.definition()), "v6:" + "a".repeat(64));
    final FeedbackCompositionContextService service = new FeedbackCompositionContextService(
            reviews,
            results,
            practices,
            versions,
            support,
            new PracticePageFeedbackRouter(),
            gate,
            history,
            conversation,
            Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(snapshot.id()).thenReturn(reviewId);
        when(snapshot.workspaceId()).thenReturn(1L);
        when(snapshot.kind()).thenReturn(ReviewKind.ITEM);
        when(snapshot.origin()).thenReturn(ReviewOrigin.EVENT);
        when(snapshot.item()).thenReturn(item);
        when(pin.practiceId()).thenReturn(5L);
        when(snapshot.practices()).thenReturn(List.of(pin));
        when(reviews.find(1, reviewId)).thenReturn(Optional.of(snapshot));
        when(reviews.find(1, historical.getReviewId())).thenReturn(Optional.of(snapshot));
        when(results.findByWorkspaceIdAndIdIn(1, List.of(current.getId()))).thenReturn(List.of(current));
        var practice = new Practice();
        practice.setWorkspace(TestEntities.workspace(1L));
        practice.setSlug("focused-tests");
        when(practices.findByIdAndWorkspaceId(5L, 1L)).thenReturn(Optional.of(practice));
        when(versions.findByIdAndWorkspaceId(8L, 1L)).thenReturn(Optional.of(version));
        var check = mock(FeedbackCheck.class);
        when(check.allowed()).thenReturn(true);
        when(gate.evaluateComposition(any(), anyLong(), anyList(), any())).thenReturn(check);
        when(gate.mode(any())).thenReturn(FeedbackMode.AUTOMATIC);
        when(conversation.isReady(1)).thenReturn(true);
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(Optional.empty(), List.of(current, historical)));
    }

    private Result result(long itemId, UUID review) {
        return Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(review)
                .practiceId(5)
                .practiceVersionId(8L)
                .itemId(itemId)
                .developerId(7L)
                .outcome(ResultOutcome.NOT_MET)
                .summary("Add a focused test.")
                .recordedAt(now.minusSeconds(60))
                .build();
    }

    private void authorizeHistory(Result row) {
        var entry = mock(ResultHistoryEntry.class);
        when(entry.resultId()).thenReturn(row.getId());
        when(entry.developerAccountId()).thenReturn(7L);
        when(entry.practiceId()).thenReturn(5L);
        when(entry.itemId()).thenReturn(row.getItemId());
        when(entry.itemKind()).thenReturn("scm.pull_request");
        when(entry.outcome()).thenReturn(ResultOutcome.NOT_MET);
        when(entry.origin()).thenReturn(ReviewOrigin.EVENT);
        when(entry.summary()).thenReturn(row.getSummary());
        when(entry.recordedAt()).thenReturn(row.getRecordedAt());
        when(entry.copyAuthorityComplete()).thenReturn(true);
        when(history.find(1, row.getId(), 7, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(Optional.of(entry));
    }

    @Test
    void shouldExposeOnlyAuthorizedCurrentDistinctWorkAndRouteConversation() {
        authorizeHistory(current);
        authorizeHistory(historical);
        var read = service.read(1, reviewId, List.of(current.getId()));
        var page = Objects.requireNonNull(read.practicePageSupport());
        assertThat(page.state()).isEqualTo(PracticePageSupport.State.COMPLETE);
        assertThat(page.readAt()).isEqualTo(now);
        assertThat(page.practices().getFirst().decision()).isEqualTo(PracticePageDecision.ADMIT);
        assertThat(page.practices().getFirst().occurrences())
                .extracting(PracticePageSupport.Occurrence::resultId)
                .containsExactly(current.getId(), historical.getId());
        assertThat(Objects.requireNonNull(read.conversationEligibility())
                        .results()
                        .getFirst()
                        .decision())
                .isEqualTo(ConversationDecision.ADMIT);
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackStopReason.class,
            names = {"DELIVERY_OFF", "NO_AI", "PERSON_SUPPRESSED", "WORKSPACE_DELIVERY_PAUSED"})
    void shouldPreserveTheGateRefusalInsteadOfClaimingMissingEvidence(FeedbackStopReason reason) {
        var refusal = mock(FeedbackCheck.class);
        when(refusal.reason()).thenReturn(reason);
        when(gate.evaluateComposition(snapshot, 7, List.of(current, historical), FeedbackDestination.PRACTICE_PAGE))
                .thenReturn(refusal);
        var page = Objects.requireNonNull(
                service.read(1, reviewId, List.of(current.getId())).practicePageSupport());
        assertThat(page.practices().getFirst().decision()).isEqualTo(PracticePageDecision.GATE_REFUSED);
        assertThat(page.practices().getFirst().stopReason()).isEqualTo(reason);
        assertThat(page.practices().getFirst().occurrences()).isEmpty();
        verifyNoInteractions(history);
    }

    @Test
    void shouldReportNoEvidenceOnlyWhenTheAuthorizedSelectionIsEmpty() {
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(Optional.empty(), List.of()));
        var page = Objects.requireNonNull(
                service.read(1, reviewId, List.of(current.getId())).practicePageSupport());
        assertThat(page.practices().getFirst().decision()).isEqualTo(PracticePageDecision.NO_EVIDENCE);
        assertThat(page.practices().getFirst().stopReason()).isEqualTo(FeedbackStopReason.NO_EVIDENCE);
    }

    @Test
    void shouldCopyNoPartialPatternWhenHistoryCustodyIsUnavailable() {
        authorizeHistory(current);
        var page = Objects.requireNonNull(
                service.read(1, reviewId, List.of(current.getId())).practicePageSupport());
        assertThat(page.state()).isEqualTo(PracticePageSupport.State.UNAVAILABLE);
        assertThat(page.refusal()).isEqualTo("HISTORY_UNAVAILABLE");
        assertThat(page.practices()).isEmpty();
    }

    @Test
    void shouldRequireOneAttributedDeveloperAndRejectForeignReviewIds() {
        var unattributed = resultBuilder(current).developerId(null).build();
        when(results.findByWorkspaceIdAndIdIn(1, List.of(current.getId()))).thenReturn(List.of(unattributed));
        assertThat(Objects.requireNonNull(service.read(1, reviewId, List.of(current.getId()))
                                .practicePageSupport())
                        .state())
                .isEqualTo(PracticePageSupport.State.UNAVAILABLE);
        var foreign = resultBuilder(current).reviewId(UUID.randomUUID()).build();
        when(results.findByWorkspaceIdAndIdIn(1, List.of(current.getId()))).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.read(1, reviewId, List.of(current.getId())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(history, conversation);
    }

    @Test
    void shouldReportTheConversationUnavailableWhenTheConversationIsNotReady() {
        when(conversation.isReady(1)).thenReturn(false);
        assertThat(service.conversationDecision(snapshot, current))
                .isEqualTo(new FeedbackCompositionContextService.ConversationAdmission(
                        ConversationDecision.CONVERSATION_UNAVAILABLE, FeedbackStopReason.RECIPIENT_UNAVAILABLE));
    }

    @Test
    void shouldKeepABackfillQuietWhenTheReviewIsABackfill() {
        when(snapshot.origin()).thenReturn(ReviewOrigin.BACKFILL);
        assertThat(service.conversationDecision(snapshot, current))
                .isEqualTo(new FeedbackCompositionContextService.ConversationAdmission(
                        ConversationDecision.BACKFILL_QUIET, FeedbackStopReason.BACKFILL_QUIET));
    }

    @Test
    void shouldReportFeedbackOffWhenTheResultsFeedbackModeIsOff() {
        when(gate.mode(current)).thenReturn(FeedbackMode.OFF);
        assertThat(service.conversationDecision(snapshot, current))
                .isEqualTo(new FeedbackCompositionContextService.ConversationAdmission(
                        ConversationDecision.FEEDBACK_OFF, FeedbackStopReason.FEEDBACK_OFF));
    }

    @Test
    void shouldDeferReviewerFeedbackWhenThePracticeIsAboutTheReviewer() {
        var reviewerVersion = TestEntities.practiceVersion(
                8,
                1,
                new JsonMapper()
                        .valueToTree(DefinitionTestFixtures.withAbout(
                                DefinitionTestFixtures.definition(), AboutRole.REVIEWER)),
                "v6:" + "a".repeat(64));
        when(versions.findByIdAndWorkspaceId(8L, 1L)).thenReturn(Optional.of(reviewerVersion));
        assertThat(service.conversationDecision(snapshot, current))
                .isEqualTo(new FeedbackCompositionContextService.ConversationAdmission(
                        ConversationDecision.REVIEWER_DEFERRED, FeedbackStopReason.PUBLIC_SUBJECT_INELIGIBLE));
    }

    @Test
    void shouldAdmitConversationWhenItsOwnRoutePermitsIt() {
        // The decision reads no anchor or delivery on the work; the persisted case proves a posted summary.
        assertThat(service.conversationDecision(snapshot, current))
                .isEqualTo(
                        new FeedbackCompositionContextService.ConversationAdmission(ConversationDecision.ADMIT, null));
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackStopReason.class,
            names = {"DELIVERY_OFF", "NO_AI", "PERSON_SUPPRESSED", "WORKSPACE_DELIVERY_PAUSED"})
    void shouldKeepTheConversationGateRefusalWhenTheGateRefuses(FeedbackStopReason reason) {
        var refusal = mock(FeedbackCheck.class);
        when(refusal.reason()).thenReturn(reason);
        when(gate.evaluateComposition(snapshot, 7, List.of(current), FeedbackDestination.CONVERSATION))
                .thenReturn(refusal);
        assertThat(service.conversationDecision(snapshot, current))
                .isEqualTo(new FeedbackCompositionContextService.ConversationAdmission(
                        ConversationDecision.GATE_REFUSED, reason));
    }

    @Test
    void shouldKeepARecentlySurfacedPracticeQuietWithoutCopyingWords() {
        var previous =
                new PreviousPracticePageFeedback.Previous(UUID.randomUUID(), now.minusSeconds(3600), null, false);
        when(support.select(1, 7, 5, now))
                .thenReturn(
                        new PracticePageSupportReader.Selection(Optional.of(previous), List.of(current, historical)));
        var page = Objects.requireNonNull(
                service.read(1, reviewId, List.of(current.getId())).practicePageSupport());
        assertThat(page.practices().getFirst().decision()).isEqualTo(PracticePageDecision.RECENTLY_SURFACED);
        assertThat(page.practices().getFirst().occurrences()).isEmpty();
        verifyNoInteractions(history);
    }

    @Test
    void shouldKeepConsentWithholdingWhenTheSupportReaderReturnsNoWords() {
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(Optional.empty(), List.of()));
        var refusal = mock(FeedbackCheck.class);
        when(refusal.reason()).thenReturn(FeedbackStopReason.NO_AI);
        when(gate.evaluateComposition(snapshot, 7, List.of(current), FeedbackDestination.PRACTICE_PAGE))
                .thenReturn(refusal);
        var page = Objects.requireNonNull(
                service.read(1, reviewId, List.of(current.getId())).practicePageSupport());
        assertThat(page.practices().getFirst().decision()).isEqualTo(PracticePageDecision.GATE_REFUSED);
        assertThat(page.practices().getFirst().stopReason()).isEqualTo(FeedbackStopReason.NO_AI);
        assertThat(page.practices().getFirst().occurrences()).isEmpty();
        verifyNoInteractions(history);
    }
}
