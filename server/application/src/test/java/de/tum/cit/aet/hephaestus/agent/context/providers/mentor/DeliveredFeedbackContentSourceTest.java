package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DeliveredFeedbackContentSourceTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(FeedbackResolution.class)
    void shouldShowConfirmedWordsAndAResponseWithoutChangingTheRecordedDeliveryState(FeedbackResolution resolution) {
        var feedback = mock(FeedbackHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var entry = mock(FeedbackHistoryEntry.class);
        Instant now = Instant.now();
        when(feedback.forDeveloper(eq(1L), eq(2L), any(), eq(31))).thenReturn(List.of(entry));
        UUID resultId = UUID.randomUUID();
        when(entry.feedbackId()).thenReturn(UUID.randomUUID());
        when(entry.resultIds()).thenReturn(List.of(resultId));
        when(entry.practiceSlugs()).thenReturn(List.of("describe-why"));
        when(entry.state()).thenReturn(FeedbackState.UNCONFIRMED);
        when(entry.destination()).thenReturn(FeedbackDestination.CONVERSATION);
        when(entry.createdAt()).thenReturn(now);
        when(entry.deliveredAt()).thenReturn(now);
        when(entry.body()).thenReturn("Confirmed shown words");
        when(entry.resolution()).thenReturn(resolution);
        var copy = new HistoryCopy(true, List.of(), List.of(), List.of());
        when(copies.of(entry)).thenReturn(copy);
        when(copies.record(copy)).thenReturn(true);
        var source = new DeliveredFeedbackContentSource(
                feedback, copies, mock(ConversationConsentGate.class), new JsonMapper());
        var payload = source.buildPayload(1L, 2L);
        assertThat(payload.path("deliveredFeedback").get(0).path("body").asString())
                .isEqualTo("Confirmed shown words");
        assertThat(payload.path("deliveredFeedback").get(0).has("bodyTruncated"))
                .isFalse();
        assertThat(payload.path("feedbackStates").get(0).path("state").asString())
                .isEqualTo("UNCONFIRMED");
        assertThat(payload.path("deliveredFeedback")
                        .get(0)
                        .path("developerResponse")
                        .path("resolution")
                        .asString())
                .isEqualTo(resolution.name());
        assertThat(payload.path("deliveredFeedback")
                        .get(0)
                        .path("developerResponse")
                        .size())
                .isEqualTo(1);
        assertThat(payload.path("feedbackStates").get(0).has("developerResponse"))
                .isFalse();
        // The basis is listed once, on the state entry; each result's detail names its review and problems.
        var state = payload.path("feedbackStates").get(0);
        assertThat(state.path("resultIds")).extracting(JsonNode::asString).containsExactly(resultId.toString());
        assertThat(state.path("practiceSlugs")).extracting(JsonNode::asString).containsExactly("describe-why");
        assertThat(state.has("problemIds")).isFalse();
        assertThat(state.has("reviewId")).isFalse();
        assertThat(payload.path("deliveredFeedback").get(0).has("practiceSlugs"))
                .isFalse();
    }

    @Test
    void shouldCutLongDeliveredWordsAndSayHowLongTheyWere() {
        var feedback = mock(FeedbackHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var entry = mock(FeedbackHistoryEntry.class);
        Instant now = Instant.now();
        String body = "w".repeat(DeliveredFeedbackContentSource.MAX_BODY_CHARS + 500);
        UUID feedbackId = UUID.randomUUID();
        when(feedback.forDeveloper(eq(1L), eq(2L), any(), eq(31))).thenReturn(List.of(entry));
        when(entry.feedbackId()).thenReturn(feedbackId);
        UUID reviewId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        UUID problemId = UUID.randomUUID();
        when(entry.reviewId()).thenReturn(reviewId);
        when(entry.resultIds()).thenReturn(List.of(resultId));
        when(entry.problemIds()).thenReturn(List.of(problemId));
        when(entry.practiceSlugs()).thenReturn(List.of("describe-why"));
        when(entry.state()).thenReturn(FeedbackState.DELIVERED);
        when(entry.destination()).thenReturn(FeedbackDestination.ON_WORK);
        when(entry.createdAt()).thenReturn(now);
        when(entry.deliveredAt()).thenReturn(now);
        when(entry.body()).thenReturn(body);
        when(entry.resolution()).thenReturn(null);
        var copy = new HistoryCopy(true, List.of(), List.of(), List.of());
        when(copies.of(entry)).thenReturn(copy);
        when(copies.record(copy)).thenReturn(true);

        when(feedback.find(1L, feedbackId, 2L)).thenReturn(Optional.of(entry));
        var source = new DeliveredFeedbackContentSource(
                feedback, copies, mock(ConversationConsentGate.class), new JsonMapper());

        var row = source.buildPayload(1L, 2L).path("deliveredFeedback").get(0);

        assertThat(row.path("body").asString()).hasSize(DeliveredFeedbackContentSource.LISTED_BODY_CHARS);
        assertThat(row.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(row.path("bodyChars").asInt()).isEqualTo(body.length());
        assertThat(row.has("developerResponse")).isFalse();
        String resource = row.path("resource").asString();
        assertThat(resource).isEqualTo(DeliveredFeedbackContentSource.resourceOf(feedbackId));

        var detail = source.read(1L, 2L, resource).orElseThrow();
        assertThat(detail.path("body").asString()).hasSize(DeliveredFeedbackContentSource.MAX_BODY_CHARS);
        assertThat(detail.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(detail.path("bodyChars").asInt()).isEqualTo(body.length());
        assertThat(detail.path("reviewId").asString()).isEqualTo(reviewId.toString());
        assertThat(detail.path("resultIds").get(0).asString()).isEqualTo(resultId.toString());
        assertThat(detail.path("problemIds").get(0).asString()).isEqualTo(problemId.toString());
        assertThat(detail.path("practiceSlugs").get(0).asString()).isEqualTo("describe-why");
        // The account guard is in FeedbackHistoryQuery.find: a piece it does not return is NOT_FOUND here.
        when(feedback.find(1L, feedbackId, 3L)).thenReturn(Optional.empty());
        assertThat(source.read(1L, 3L, resource).orElseThrow().path("status").asString())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void shouldAnswerNotFoundForAPieceWithoutAReceiptAndNothingForAPathItDoesNotOwn() {
        var feedback = mock(FeedbackHistoryQuery.class);
        var entry = mock(FeedbackHistoryEntry.class);
        UUID feedbackId = UUID.randomUUID();
        when(feedback.find(1L, feedbackId, 2L)).thenReturn(Optional.of(entry));
        when(entry.deliveredAt()).thenReturn(null);
        var source = new DeliveredFeedbackContentSource(
                feedback, mock(HistoryCopies.class), mock(ConversationConsentGate.class), new JsonMapper());

        assertThat(source.read(1L, 2L, DeliveredFeedbackContentSource.resourceOf(feedbackId))
                        .orElseThrow()
                        .path("status")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(source.read(1L, 2L, "inputs/context/results_history/" + UUID.randomUUID() + ".json"))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackState.class,
            names = {"READY", "UNCONFIRMED", "FAILED"})
    void shouldKeepPreparedOrFailedFeedbackSeparateFromConfirmedWords(FeedbackState state) {
        var feedback = mock(FeedbackHistoryQuery.class);
        var entry = mock(FeedbackHistoryEntry.class);
        var copies = mock(HistoryCopies.class);
        var resultId = UUID.randomUUID();
        when(feedback.forDeveloper(eq(1L), eq(2L), any(), eq(31))).thenReturn(List.of(entry));
        when(entry.feedbackId()).thenReturn(UUID.randomUUID());
        when(entry.resultIds()).thenReturn(List.of(resultId));
        when(entry.state()).thenReturn(state);
        when(entry.destination()).thenReturn(FeedbackDestination.CONVERSATION);
        when(entry.createdAt()).thenReturn(Instant.now());
        lenient().when(entry.body()).thenReturn("Prepared guidance, not a later assessment.");
        when(entry.deliveredAt()).thenReturn(null);
        lenient().when(entry.resolution()).thenReturn(FeedbackResolution.ADDRESSED);
        // Missing receipt alone refuses the words, even when copying would be authorized.
        var copy = new HistoryCopy(true, List.of(), List.of(), List.of());
        lenient().when(copies.of(entry)).thenReturn(copy);
        lenient().when(copies.record(copy)).thenReturn(true);
        var payload = new DeliveredFeedbackContentSource(
                        feedback, copies, mock(ConversationConsentGate.class), new JsonMapper())
                .buildPayload(1L, 2L);

        assertThat(payload.path("deliveredFeedback")).isEmpty();
        var row = payload.path("feedbackStates").get(0);
        assertThat(row.path("state").asString()).isEqualTo(state.name());
        assertThat(row.has("reviewId")).isFalse();
        assertThat(row.path("resultIds")).extracting(JsonNode::asString).containsExactly(resultId.toString());
        assertThat(row.has("outcome")).isFalse();
        assertThat(row.has("body")).isFalse();
        assertThat(row.has("developerResponse")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(FeedbackResolution.class)
    void shouldOmitTheResponseWhenTheDeliveredWordsHaveNoCopyAuthority(FeedbackResolution resolution) {
        var feedback = mock(FeedbackHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        var entry = mock(FeedbackHistoryEntry.class);
        when(feedback.forDeveloper(eq(1L), eq(2L), any(), eq(31))).thenReturn(List.of(entry));
        when(entry.feedbackId()).thenReturn(UUID.randomUUID());
        when(entry.state()).thenReturn(FeedbackState.DELIVERED);
        when(entry.destination()).thenReturn(FeedbackDestination.PRACTICE_PAGE);
        when(entry.createdAt()).thenReturn(Instant.now());
        when(entry.deliveredAt()).thenReturn(Instant.now());
        when(entry.body()).thenReturn("Confirmed words whose copy authority was lost.");
        lenient().when(entry.resolution()).thenReturn(resolution);
        var denied = new HistoryCopy(false, List.of(), List.of(), List.of());
        when(copies.of(entry)).thenReturn(denied);
        when(copies.record(denied)).thenReturn(false);

        var payload = new DeliveredFeedbackContentSource(
                        feedback, copies, mock(ConversationConsentGate.class), new JsonMapper())
                .buildPayload(1L, 2L);

        assertThat(payload.path("deliveredFeedback")).isEmpty();
        assertThat(payload.path("feedbackStates").get(0).has("developerResponse"))
                .isFalse();
    }

    @Test
    void noReceiptOrCopyAuthorityProducesNoDeliveredWords() {
        var feedback = mock(FeedbackHistoryQuery.class);
        var copies = mock(HistoryCopies.class);
        when(feedback.forDeveloper(eq(1L), eq(2L), any(), eq(31))).thenReturn(List.of());
        var source = new DeliveredFeedbackContentSource(
                feedback, copies, mock(ConversationConsentGate.class), new JsonMapper());
        assertThat(source.buildPayload(1L, 2L).path("deliveredFeedback").size()).isZero();
    }
}
