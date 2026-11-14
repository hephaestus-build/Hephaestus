package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.AbstractFeedbackIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversationalFeedbackDeliveryLoopIntegrationTest extends AbstractFeedbackIntegrationTest {

    @Autowired
    ConversationFeedbackTtlSweeper sweeper;

    @Test
    void shouldExpireOnlyUnfencedReadyConversationFeedbackAndRetainItsHistory() {
        var unsent = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        var unknown = piece(FeedbackDestination.CONVERSATION, FeedbackState.UNCONFIRMED, null);
        UUID copyId = FeedbackIds.copy(unknown.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        copies.saveAndFlush(FeedbackCopy.builder()
                .id(copyId)
                .workspaceId(workspace.getId())
                .feedbackId(unknown.getId())
                .kind(FeedbackCopyKind.CHAT_TURN)
                .state(FeedbackCopyState.WRITE_STARTED)
                .writeStartedAt(AT)
                .writeKey(copyId.toString())
                .body("Try a focused test.")
                .createdAt(AT)
                .build());
        sweeper.expireWorkspaceBefore(workspace.getId(), AT.plusSeconds(1));
        assertThat(feedback.findById(unsent.getId()).orElseThrow().getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(feedback.findById(unsent.getId()).orElseThrow().getStopReason())
                .isEqualTo(FeedbackStopReason.CONVERSATION_EXPIRED);
        assertThat(feedback.findById(unknown.getId()).orElseThrow().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(copies.findById(copyId).orElseThrow().getWriteStartedAt()).isEqualTo(AT);
    }

    @Test
    void shouldExpireTheOtherRowsWhenOneRowCannotExpire() {
        var first = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        var second = piece(FeedbackDestination.CONVERSATION, FeedbackState.READY, null);
        // Rows expire in id order, so the poisoned row is the one reached first.
        boolean firstIsReachedFirst =
                first.getId().toString().compareTo(second.getId().toString()) < 0;
        var poisoned = firstIsReachedFirst ? first : second;
        var healthy = firstIsReachedFirst ? second : first;
        String block = "test_block_ttl_" + Long.toUnsignedString(System.nanoTime());
        // A fixture changes the schema as its owner: the application role cannot.
        JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();
        owner.execute("CREATE FUNCTION " + block
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'expiry blocked'; END $$");
        try {
            owner.execute("CREATE TRIGGER " + block + " BEFORE UPDATE ON feedback FOR EACH ROW WHEN (OLD.id = '"
                    + poisoned.getId() + "') EXECUTE FUNCTION " + block + "()");

            sweeper.expireWorkspaceBefore(workspace.getId(), AT.plusSeconds(1));
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS " + block + " ON feedback");
            owner.execute("DROP FUNCTION IF EXISTS " + block + "()");
        }
        assertThat(feedback.findById(poisoned.getId()).orElseThrow().getState()).isEqualTo(FeedbackState.READY);
        var withheld = feedback.findById(healthy.getId()).orElseThrow();
        assertThat(withheld.getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(withheld.getStopReason()).isEqualTo(FeedbackStopReason.CONVERSATION_EXPIRED);
    }
}
