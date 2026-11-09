package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CompositionPackagesTest {
    private static final UUID REVIEW = UUID.randomUUID();
    private static final FeedbackPackage PROPOSAL = new FeedbackPackage(List.of(), List.of(), true, "history-1");

    @Test
    void shouldBindTheDraftIdToItsReviewAndExactHistoryAndPayload() {
        assertThat(PROPOSAL.draftId(REVIEW)).hasSize(64);
        assertThat(PROPOSAL.draftId(REVIEW)).isNotEqualTo(PROPOSAL.draftId(UUID.randomUUID()));
        assertThat(PROPOSAL.draftId(REVIEW))
                .isNotEqualTo(new FeedbackPackage(List.of(), List.of(), true, "history-2").draftId(REVIEW));
        assertThat(PROPOSAL.draftId(REVIEW))
                .isNotEqualTo(
                        new FeedbackPackage(List.of(UUID.randomUUID()), List.of(), true, "history-1").draftId(REVIEW));
    }
}
