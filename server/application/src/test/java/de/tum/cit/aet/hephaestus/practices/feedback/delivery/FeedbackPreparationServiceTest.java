package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class FeedbackPreparationServiceTest {
    final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    final ResultRepository results = mock(ResultRepository.class);
    final FeedbackBasisRepository basis = mock(FeedbackBasisRepository.class);
    final ItemDescriptorRegistry catalog = mock(ItemDescriptorRegistry.class);
    final FeedbackCompositionContextService composition = mock(FeedbackCompositionContextService.class);
    final PracticePageSupportReader support = mock(PracticePageSupportReader.class);
    final UUID reviewId = UUID.randomUUID(), resultId = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    final ProblemRepository problems = mock(ProblemRepository.class);
    final FeedbackResponseSuppressionFilter suppression = mock(FeedbackResponseSuppressionFilter.class);
    final FeedbackPreparationService service = new FeedbackPreparationService(
            reviews,
            results,
            problems,
            basis,
            catalog,
            Clock.fixed(now, ZoneOffset.UTC),
            mock(PracticeVersionRepository.class),
            mock(RepeatedSummaryCheck.class),
            suppression,
            mock(FeedbackSupersession.class),
            support,
            composition);

    @BeforeEach
    void setup() {
        var snapshot = mock(ReviewSnapshot.class);
        var item = new ItemRef(1, 3, new ItemKind("scm.pull_request"), 42L, null, null);
        when(snapshot.item()).thenReturn(item);
        when(reviews.find(1, reviewId)).thenReturn(Optional.of(snapshot));
        var descriptor = mock(ItemDescriptor.class);
        when(descriptor.copyCapabilities()).thenReturn(Set.of(CopyCapability.SUMMARY));
        when(catalog.descriptorFor(item.kind())).thenReturn(Optional.of(descriptor));
        when(results.findByWorkspaceIdAndIdIn(1, List.of(resultId))).thenReturn(List.of(result(resultId, 3)));
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(Optional.empty(), List.of(result(resultId, 3))));
        when(composition.pageDecision(any(), anyList(), any(), any()))
                .thenReturn(new FeedbackCompositionContextService.PageAdmission(PracticePageDecision.ADMIT, null));
    }

    private Result result(UUID id, long item) {
        return Result.builder()
                .id(id)
                .reviewId(reviewId)
                .workspaceId(1)
                .practiceId(5)
                .itemId(item)
                .developerId(7L)
                .outcome(ResultOutcome.NOT_MET)
                .recordedAt(now.minusSeconds(60))
                .build();
    }

    private FeedbackDraft draft(List<UUID> ids) {
        var edges = new ArrayList<FeedbackBasisInput>();
        for (var id : ids) edges.add(new FeedbackBasisInput(id, null, edges.size()));
        return new FeedbackDraft(
                FeedbackIds.practicePage(1, reviewId, 7, resultId),
                1,
                reviewId,
                null,
                7,
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.READY,
                null,
                "Add a focused test when changing behavior.",
                edges,
                List.of(),
                Map.of(),
                null,
                null);
    }

    @Test
    void shouldBindOnlyServerSelectedDistinctProblemWorkToARecurringCard() {
        UUID previous = UUID.randomUUID();
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(
                        Optional.empty(), List.of(result(resultId, 3), result(previous, 4))));
        var prepared =
                service.prepare(draft(List.of(resultId)), Optional.empty()).draft();
        assertThat(prepared.state()).isEqualTo(FeedbackState.READY);
        assertThat(prepared.itemId()).isNull();
        assertThat(prepared.basis()).extracting(FeedbackBasisInput::resultId).containsExactly(resultId, previous);
    }

    @Test
    void shouldWithholdAnUncorroboratedPatternWithoutClaimingMissingCapture() {
        doReturn(new FeedbackCompositionContextService.PageAdmission(
                        PracticePageDecision.UNCORROBORATED, FeedbackStopReason.UNCORROBORATED))
                .when(composition)
                .pageDecision(any(), anyList(), any(), any());
        var prepared =
                service.prepare(draft(List.of(resultId)), Optional.empty()).draft();
        assertThat(prepared.state()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(prepared.stopReason()).isEqualTo(FeedbackStopReason.UNCORROBORATED);
    }

    @Test
    void shouldRejectCallerChosenHistoricalIdsBeforePreparingAnyFeedback() {
        UUID historical = UUID.randomUUID();
        var older =
                resultBuilder(result(historical, 4)).reviewId(UUID.randomUUID()).build();
        when(results.findByWorkspaceIdAndIdIn(1, List.of(resultId, historical)))
                .thenReturn(List.of(result(resultId, 3), older));
        assertThatThrownBy(() -> service.prepare(draft(List.of(resultId, historical)), Optional.empty()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldAcceptExactlyTheAuthorizedHistoricalBasisAndRecheckItAtPreparation() {
        UUID historical = UUID.randomUUID();
        var current = result(resultId, 3);
        var older =
                resultBuilder(result(historical, 4)).reviewId(UUID.randomUUID()).build();
        when(results.findByWorkspaceIdAndIdIn(1, List.of(resultId, historical))).thenReturn(List.of(current, older));
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(Optional.empty(), List.of(current, older)));
        assertThat(service.prepare(draft(List.of(resultId, historical)), Optional.empty())
                        .draft()
                        .state())
                .isEqualTo(FeedbackState.READY);
        when(support.select(1, 7, 5, now))
                .thenReturn(new PracticePageSupportReader.Selection(Optional.empty(), List.of(current)));
        assertThatThrownBy(() -> service.prepare(draft(List.of(resultId, historical)), Optional.empty()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldReplayTheFrozenCardBasisInsteadOfAddingNewHistory() {
        UUID retained = UUID.randomUUID();
        var stored = Feedback.builder().id(draft(List.of(resultId)).id()).build();
        when(basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(1, stored.getId()))
                .thenReturn(List.of(
                        FeedbackBasis.builder().resultId(resultId).build(),
                        FeedbackBasis.builder().resultId(retained).build()));
        when(results.findByWorkspaceIdAndIdIn(1, List.of(resultId, retained)))
                .thenReturn(List.of(result(resultId, 3), result(retained, 4)));
        var prepared =
                service.prepare(draft(List.of(resultId)), Optional.of(stored)).draft();
        assertThat(prepared.basis()).extracting(FeedbackBasisInput::resultId).containsExactly(resultId, retained);
        verify(support, never()).select(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldRecordTheSameGateReasonAtPagePreparation() {
        doReturn(new FeedbackCompositionContextService.PageAdmission(
                        PracticePageDecision.GATE_REFUSED, FeedbackStopReason.NO_AI))
                .when(composition)
                .pageDecision(any(), anyList(), any(), any());
        assertThat(service.prepare(draft(List.of(resultId)), Optional.empty())
                        .draft()
                        .stopReason())
                .isEqualTo(FeedbackStopReason.NO_AI);
    }

    @Test
    void shouldIgnoreASuppressedSiblingProblemThatTheCardDoesNotCommunicate() {
        UUID suppressedId = UUID.randomUUID(), communicatedId = UUID.randomUUID();
        var original = draft(List.of(resultId));
        var exact = new FeedbackDraft(
                original.id(),
                original.workspaceId(),
                original.reviewId(),
                null,
                original.developerId(),
                original.destination(),
                original.state(),
                null,
                original.body(),
                List.of(new FeedbackBasisInput(resultId, communicatedId, 0)),
                List.of(),
                Map.of(),
                null,
                null);
        when(problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(1, List.of(resultId)))
                .thenReturn(List.of(
                        Problem.builder().id(suppressedId).build(),
                        Problem.builder().id(communicatedId).build()));
        when(suppression.suppressedProblems(1, original.destination(), Set.of(communicatedId)))
                .thenReturn(Map.of());
        var prepared = service.prepare(exact, Optional.empty()).draft();
        assertThat(prepared.state()).isEqualTo(FeedbackState.READY);
        verify(suppression).suppressedProblems(1, original.destination(), Set.of(communicatedId));
        verify(problems, never()).findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(anyLong(), anyList());
    }
}
