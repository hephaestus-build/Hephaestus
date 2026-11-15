package de.tum.cit.aet.hephaestus.practices.feedback;

import static de.tum.cit.aet.hephaestus.practices.result.ResultTestFixtures.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackCompositionContextService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackPreparationService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackResponseSuppressionFilter;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.PracticePageFeedbackRouter;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.PracticePageSupportReader;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.RepeatedSummaryCheck;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class FeedbackAdmissionReasonsTest {
    private final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID reviewId = UUID.randomUUID();
    private final ReviewSnapshot snapshot = new ReviewSnapshot(
            reviewId,
            1,
            new ItemRef(1, 3, new ItemKind("scm.pull_request"), 42L, null, null),
            ReviewKind.ITEM,
            null,
            ReviewOrigin.EVENT,
            0,
            List.of(new PinnedPractice(
                    5,
                    8,
                    DefinitionTestFixtures.definition(),
                    "v6:" + "a".repeat(64),
                    new ReviewMethod(6, "a".repeat(64), List.of()))));
    private final Result current = Result.builder()
            .id(UUID.randomUUID())
            .workspaceId(1)
            .reviewId(reviewId)
            .practiceId(5)
            .practiceVersionId(8L)
            .itemId(3)
            .developerId(7L)
            .outcome(ResultOutcome.NOT_MET)
            .evidenceVersion((short) 1)
            .summary("Add a focused test.")
            .recordedAt(now.minusSeconds(60))
            .build();
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final ResultRepository results = mock(ResultRepository.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final PracticeVersionRepository versions = mock(PracticeVersionRepository.class);
    private final FeedbackQueryRepository queries = mock(FeedbackQueryRepository.class);
    private final FeedbackGate gate = mock(FeedbackGate.class);
    private final ItemDescriptorRegistry catalog = mock(ItemDescriptorRegistry.class);
    private final FeedbackBasisRepository basis = mock(FeedbackBasisRepository.class);
    private final PreviousPracticePageFeedback previous = mock(PreviousPracticePageFeedback.class);
    private final PracticePageSupportReader support = new PracticePageSupportReader(previous, practices, queries);
    private final FeedbackCompositionContextService composition = new FeedbackCompositionContextService(
            reviews,
            results,
            practices,
            versions,
            support,
            new PracticePageFeedbackRouter(),
            gate,
            mock(ResultHistoryQuery.class),
            mock(ConversationReadinessQuery.class),
            clock);
    private final FeedbackPreparationService preparation = new FeedbackPreparationService(
            reviews,
            results,
            mock(ProblemRepository.class),
            basis,
            catalog,
            clock,
            versions,
            mock(RepeatedSummaryCheck.class),
            mock(FeedbackResponseSuppressionFilter.class),
            mock(FeedbackSupersession.class),
            support,
            composition);

    @BeforeEach
    void setup() {
        when(reviews.find(1, reviewId)).thenReturn(Optional.of(snapshot));
        when(queries.reviewOrigins(1, List.of(reviewId))).thenReturn(List.of(ReviewOrigin.EVENT));
        when(gate.mode(current)).thenReturn(FeedbackMode.AUTOMATIC);
        when(versions.findByIdAndWorkspaceId(8L, 1L))
                .thenReturn(Optional.of(TestEntities.practiceVersion(
                        8,
                        1,
                        new JsonMapper().valueToTree(DefinitionTestFixtures.definition()),
                        "v6:" + "a".repeat(64))));
        when(results.findByWorkspaceIdAndIdIn(1, List.of(current.getId()))).thenReturn(List.of(current));
        var practice = new Practice();
        practice.setId(5L);
        practice.setWorkspace(TestEntities.workspace(1L));
        practice.setSlug("focused-tests");
        when(practices.findByIdAndWorkspaceId(5L, 1L)).thenReturn(Optional.of(practice));
        when(previous.find(1, 7, "focused-tests", now)).thenReturn(Optional.empty());
        when(queries.findPageSupport(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(List.of(current));
        var descriptor = mock(ItemDescriptor.class);
        when(descriptor.copyCapabilities()).thenReturn(Set.of(CopyCapability.SUMMARY));
        when(catalog.descriptorFor(snapshot.item().kind())).thenReturn(Optional.of(descriptor));
    }

    @ParameterizedTest
    @CsvSource({
        "NO_AI,NO_AI",
        "DELIVERY_OFF,DELIVERY_OFF",
        "PERSON_SUPPRESSED,PERSON_SUPPRESSED",
        "WORKSPACE_DELIVERY_PAUSED,WORKSPACE_DELIVERY_PAUSED",
        "EVIDENCE_NOT_PERMITTED,EVIDENCE_NOT_PERMITTED"
    })
    void retainsEachConversationRefusalThroughPreparation(FeedbackStopReason gateReason, String expected) {
        when(gate.evaluateComposition(any(), anyLong(), anyList(), any()))
                .thenReturn(new FeedbackCheck(
                        FeedbackCheckStage.COMPOSITION,
                        false,
                        gateReason,
                        now,
                        new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false)));
        var admission = composition.conversationDecision(snapshot, current);
        assertThat(admission.decision()).isEqualTo(ConversationDecision.GATE_REFUSED);
        assertThat(admission.stopReason()).isNotNull().hasToString(expected);
        var route = Objects.requireNonNull(
                        composition.read(1, reviewId, List.of(current.getId())).conversationEligibility())
                .results()
                .getFirst();
        assertThat(route.decision()).isEqualTo(ConversationDecision.GATE_REFUSED);
        assertThat(route.stopReason()).isNotNull().hasToString(expected);
        var prepared =
                preparation.prepare(conversationDraft(), Optional.empty()).draft();
        assertThat(prepared.state()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(prepared.stopReason()).isNotNull().hasToString(expected);
    }

    @Test
    void separatesAnUnattributableSubjectFromAnUnavailableRecipient() {
        var developerless = resultBuilder(current).developerId(null).build();
        var admission = composition.conversationDecision(snapshot, developerless);
        assertThat(admission.decision()).isEqualTo(ConversationDecision.UNATTRIBUTABLE_SUBJECT);
        assertThat(admission.stopReason()).isEqualTo(FeedbackStopReason.PUBLIC_SUBJECT_INELIGIBLE);
    }

    @Test
    void rejectsNonNegativeConversationInputBeforePreparingFeedback() {
        when(results.findByWorkspaceIdAndIdIn(1, List.of(current.getId())))
                .thenReturn(List.of(
                        resultBuilder(current).outcome(ResultOutcome.MET).build()));
        assertThatThrownBy(() -> preparation.prepare(conversationDraft(), Optional.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("NOT_MET");
    }

    @Test
    void preservesPermissionRefusalWhenThePrimaryResultIsOutsideTheSupportWindow() {
        var about = resultBuilder(current)
                .recordedAt(now.minusSeconds(100 * 86400L))
                .build();
        var historical = resultBuilder(current).id(UUID.randomUUID()).itemId(4).build();
        var practice = new Practice();
        practice.setId(5L);
        practice.setWorkspace(TestEntities.workspace(1L));
        practice.setSlug("focused-tests");
        when(practices.findByIdAndWorkspaceId(5L, 1L)).thenReturn(Optional.of(practice));
        when(previous.find(1, 7, "focused-tests", now)).thenReturn(Optional.empty());
        when(queries.findPageSupport(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(List.of(historical));
        when(gate.evaluateComposition(any(), anyLong(), anyList(), any())).thenAnswer(call -> {
            List<Result> rows = call.getArgument(2);
            boolean allowed = rows.equals(List.of(about));
            return new FeedbackCheck(
                    FeedbackCheckStage.COMPOSITION,
                    allowed,
                    allowed ? null : FeedbackStopReason.EVIDENCE_NOT_PERMITTED,
                    now,
                    new FeedbackCheckFacts(0, 0, true, allowed, true, true, false, true, true, false));
        });
        var selected = support.select(1, 7, 5, now);
        assertThat(selected.evidence()).containsExactly(historical);
        var admission = composition.pageDecision(about, selected.evidence(), selected, now);
        assertThat(admission.decision()).isEqualTo(PracticePageDecision.GATE_REFUSED);
        assertThat(admission.stopReason()).isEqualTo(FeedbackStopReason.EVIDENCE_NOT_PERMITTED);
    }

    @Test
    void refusesToInventBackfillOriginForMissingReviewAuthority() {
        when(reviews.find(1, reviewId)).thenReturn(Optional.empty());
        var selection = new PracticePageSupportReader.Selection(Optional.empty(), List.of(current));
        assertThatThrownBy(() -> composition.pageDecision(current, selection.evidence(), selection, now))
                .isInstanceOf(NoSuchElementException.class);
    }

    private FeedbackDraft conversationDraft() {
        return new FeedbackDraft(
                UUID.randomUUID(),
                1,
                reviewId,
                3L,
                7,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                null,
                "Add a focused test.",
                List.of(new FeedbackBasisInput(current.getId(), null, 0)),
                List.of(),
                Map.of(),
                null,
                null);
    }
}
