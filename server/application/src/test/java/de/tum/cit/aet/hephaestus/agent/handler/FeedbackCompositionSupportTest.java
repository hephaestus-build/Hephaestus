package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationEligibility;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedReviewQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class FeedbackCompositionSupportTest extends BaseUnitTest {
    private static final Instant AT = Instant.parse("2026-11-07T10:00:00Z");
    private final FeedbackStore feedback = mock(FeedbackStore.class);
    private final ResultHistoryQuery history = mock(ResultHistoryQuery.class);
    private final HistoryCopies copies = mock(HistoryCopies.class);
    private final ProductReferenceQuery references = mock(ProductReferenceQuery.class);
    private final FeedbackCompositionService composition = new FeedbackCompositionService(
            feedback,
            mock(PreparedReviewQuery.class),
            mock(ReviewAssignmentStore.class),
            history,
            copies,
            mock(FeedbackHistoryQuery.class),
            mock(FeedbackRepair.class),
            references);
    private final ReviewJob job = RuntimeReviewTestData.job();
    private final ResultHistoryEntry current = result(42), earlier = result(84);
    private final List<AdmittedResultSnapshot> admitted = List.of(new AdmittedResultSnapshot(
            current.resultId(),
            new ResultCandidate(
                    11,
                    21,
                    42,
                    "v1",
                    5L,
                    ResultOutcome.NOT_MET,
                    "View owns I/O",
                    "The view decodes data.",
                    new ResultEvidence(List.of(), null, null, List.of()),
                    null,
                    new HostCoverage(true, List.of(), 0, 0, 0, List.of()),
                    List.of()),
            List.of()));
    private final ConversationEligibility conversation = new ConversationEligibility(
            ConversationEligibility.State.COMPLETE,
            AT,
            List.of(new ConversationEligibility.ResultDecision(current.resultId(), ConversationDecision.ADMIT, null)));

    @BeforeEach
    void setup() {
        var page = new PracticePageSupport(
                PracticePageSupport.State.COMPLETE,
                AT,
                List.of(new PracticePageSupport.PracticeSupport(
                        "view-io",
                        PracticePageDecision.ADMIT,
                        null,
                        List.of(occurrence(current), occurrence(earlier)))),
                null);
        when(feedback.compositionContext(1, job.getReviewId(), List.of(current.resultId())))
                .thenReturn(new FeedbackCompositionContext(page, conversation));
        for (var row : List.of(current, earlier)) {
            when(history.find(1, row.resultId(), 5, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    .thenReturn(Optional.of(row));
            when(copies.of(row)).thenReturn(new HistoryCopy(true, List.of(), List.of(), List.of()));
        }
    }

    @Test
    void shouldExposeTheAuthorizedOlderWorkThroughThePageSupportWireContract() {
        when(references.items(1, List.of(42L, 84L)))
                .thenReturn(Map.of(42L, item(42, "Current menu work", 17), 84L, item(84, "Earlier menu work", 9)));

        var read = composition.read(job, admitted);
        var json = new JsonMapper().valueToTree(FeedbackCompositionService.page(read));
        assertThat(json.at("/practices/0/occurrences/0/item/id").asString()).isEqualTo("42");
        assertThat(json.at("/practices/0/occurrences/1/item/id").asString()).isEqualTo("84");
        assertThat(json.at("/practices/0/occurrences/1/item/title").asString()).isEqualTo("Earlier menu work");
        assertThat(json.at("/practices/0/occurrences/1/item/label").asString()).isEqualTo("#9");
        assertThat(json.at("/practices/0/occurrences/1/item/url").asString())
                .isEqualTo("https://example.test/project/pull/9");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepMissingDisplayMetadataUnknownWithoutChangingTheSupportDecision(boolean indexed) {
        var unknown = new ItemRefDTO(
                "84", "scm.pull_request", null, null, "Pull or merge request", null, null, null, null, null);
        when(references.items(1, List.of(42L, 84L))).thenReturn(indexed ? Map.of(84L, unknown) : Map.of());

        var read = composition.read(job, admitted);
        var json = new JsonMapper().valueToTree(FeedbackCompositionService.page(read));
        assertThat(json.at("/practices/0/decision").asString()).isEqualTo("ADMIT");
        var item = json.at("/practices/0/occurrences/1/item");
        if (indexed) {
            assertThat(item.path("title").isNull()).isTrue();
            assertThat(item.path("url").isNull()).isTrue();
        } else {
            assertThat(item.isNull()).isTrue();
        }
    }

    @Test
    void shouldNotReadWorkLabelsWhenHistoricalCopyAuthorityFails() {
        when(copies.of(earlier)).thenReturn(new HistoryCopy(false, List.of(), List.of(), List.of()));

        var read = composition.read(job, admitted);
        assertThat(Objects.requireNonNull(read.context().practicePageSupport()).state())
                .isEqualTo(PracticePageSupport.State.REFUSED);
        assertThat(read.supportItems()).isEmpty();
        assertThat(read.context().conversationEligibility()).isEqualTo(conversation);
        verifyNoInteractions(references);
    }

    @Test
    void shouldNotRepeatTheDisplayReadWhenPreparationRechecksSupport() {
        var read = composition.readForPreparation(job, admitted);
        assertThat(read.support())
                .extracting(ResultHistoryEntry::resultId)
                .containsExactly(current.resultId(), earlier.resultId());
        assertThat(read.supportItems()).isEmpty();
        verifyNoInteractions(references);
    }

    private static PracticePageSupport.Occurrence occurrence(ResultHistoryEntry row) {
        return new PracticePageSupport.Occurrence(
                row.resultId(),
                row.itemKind(),
                Long.toString(row.itemId()),
                row.outcome(),
                row.origin(),
                Objects.requireNonNull(row.summary()),
                row.explanation(),
                row.evidence(),
                "21",
                row.recordedAt());
    }

    private ResultHistoryEntry result(long itemId) {
        return new ResultHistoryEntry(
                UUID.randomUUID(),
                job.getReviewId(),
                11,
                "view-io",
                "View I/O",
                1,
                itemId,
                "scm.pull_request",
                "v1",
                5L,
                ResultOutcome.NOT_MET,
                null,
                "View owns I/O",
                AT,
                null,
                null,
                AT,
                ResultHistoryEntry.StoredWorkVersion.SAME,
                List.of(),
                List.of(),
                List.of(),
                new ItemRef(1, itemId, new ItemKind("scm.pull_request"), itemId, null, null),
                ReviewOrigin.EVENT,
                "The view decodes data.",
                null,
                null,
                null,
                null,
                (short) 1,
                List.of(),
                List.of(),
                List.of(),
                true);
    }

    private static ItemRefDTO item(long id, String title, int number) {
        return new ItemRefDTO(
                Long.toString(id),
                "scm.pull_request",
                null,
                IntegrationKind.GITHUB,
                "#" + number,
                title,
                "https://example.test/project/pull/" + number,
                "example/project",
                null,
                null);
    }
}
