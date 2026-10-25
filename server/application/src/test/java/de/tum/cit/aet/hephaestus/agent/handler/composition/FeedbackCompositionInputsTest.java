package de.tum.cit.aet.hephaestus.agent.handler.composition;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class FeedbackCompositionInputsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRequestTheSharedDestinationsWhenAnchoredOnWorkIsSupported() throws Exception {
        var input = mapper.readTree(FeedbackCompositionInputs.request(
                        ReviewKind.ITEM,
                        ReviewOrigin.EVENT,
                        Set.of(FeedbackDestination.ON_WORK, FeedbackDestination.PRACTICE_PAGE),
                        Set.of(CopyCapability.SUMMARY, CopyCapability.LINE_NOTE),
                        mapper)
                .orElseThrow());
        try (var fixture =
                Objects.requireNonNull(getClass().getResourceAsStream("/agent/feedback-composition-request.json"))) {
            assertThat(input).isEqualTo(mapper.readTree(fixture));
        }
    }

    @Test
    void shouldAllowOnWorkSummariesWhenTheItemDoesNotSupportAnchoredNotes() {
        var input = mapper.readTree(FeedbackCompositionInputs.request(
                        ReviewKind.ITEM,
                        ReviewOrigin.EVENT,
                        Set.of(FeedbackDestination.ON_WORK),
                        Set.of(CopyCapability.SUMMARY),
                        mapper)
                .orElseThrow());
        assertThat(input.path("destinations").path("ON_WORK").path("maxPieces").asInt())
                .isEqualTo(1);
        assertThat(input.path("onWorkCopyForms").isArray()).isTrue();
        assertThat(input.path("onWorkCopyForms").isEmpty()).isTrue();
    }

    @Test
    void shouldKeepPrivateDestinationsWhenTheItemDoesNotSupportOnWorkCopies() {
        var input = mapper.readTree(FeedbackCompositionInputs.request(
                        ReviewKind.ITEM,
                        ReviewOrigin.EVENT,
                        EnumSet.allOf(FeedbackDestination.class),
                        Set.of(CopyCapability.PRACTICE_PAGE, CopyCapability.CONVERSATION),
                        mapper)
                .orElseThrow());
        assertThat(input.path("destinations").has("ON_WORK")).isFalse();
        assertThat(input.path("destinations")
                        .path("PRACTICE_PAGE")
                        .path("maxPieces")
                        .asInt())
                .isEqualTo(1);
        assertThat(input.path("destinations")
                        .path("CONVERSATION")
                        .path("maxPieces")
                        .asInt())
                .isEqualTo(1);
        assertThat(input.path("onWorkCopyForms").isEmpty()).isTrue();
    }

    @Test
    void shouldKeepScanBackfillAndUnsupportedRequestsQuiet() {
        assertThat(FeedbackCompositionInputs.request(
                        ReviewKind.SCAN,
                        ReviewOrigin.REQUEST,
                        Set.of(FeedbackDestination.ON_WORK),
                        Set.of(CopyCapability.SUMMARY),
                        mapper))
                .isEmpty();
        assertThat(FeedbackCompositionInputs.request(
                        ReviewKind.ITEM,
                        ReviewOrigin.BACKFILL,
                        Set.of(FeedbackDestination.CONVERSATION),
                        Set.of(CopyCapability.CONVERSATION),
                        mapper))
                .isEmpty();
        assertThat(FeedbackCompositionInputs.request(
                        ReviewKind.ITEM, ReviewOrigin.EVENT, Set.of(FeedbackDestination.ON_WORK), Set.of(), mapper))
                .isEmpty();
        assertThat(FeedbackCompositionInputs.request(
                        ReviewKind.ITEM, ReviewOrigin.EVENT, Set.of(), Set.of(CopyCapability.SUMMARY), mapper))
                .isEmpty();
    }
}
