package de.tum.cit.aet.hephaestus.practices.feedback.page;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.AbstractFeedbackIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class PracticePageFeedbackWorkResolutionIntegrationTest extends AbstractFeedbackIntegrationTest {

    @Test
    void shouldNotTreatMissingLaterProofAsCleanWork() {
        var parent = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, null);
        var result = result();
        bind(parent, result);
        assertThat(later(AT, AT.plusSeconds(3600), result.getPracticeId())).isEmpty();
    }

    @Test
    void shouldLeaveTheCurrentClaimOccupiedAfterAnAcceptedMark() {
        var result = result();
        jdbc.update(
                "INSERT INTO"
                        + " result_mark(id,workspace_id,result_id,kind,reason,created_by_id,created_at)"
                        + " VALUES (?,?,?,'ACCEPTED','Accepted for this change.',?,?)",
                UUID.randomUUID(),
                workspace.getId(),
                result.getId(),
                developerId,
                Timestamp.from(AT));
        assertThat(results.findById(result.getId()).orElseThrow().getEndedAt()).isNull();
        occasionedByTheWork();
        assertThat(later(AT.minusSeconds(1), AT.plusSeconds(1), result.getPracticeId()))
                .extracting(Result::getId)
                .containsExactly(result.getId());
    }

    @Test
    void shouldOfferOnlyLaterWorkThatCanCloseTheCard() {
        var result = result();
        assertThat(later(AT.minusSeconds(1), AT.plusSeconds(1), result.getPracticeId()))
                .as("requested work never closes a card")
                .isEmpty();
        occasionedByTheWork();
        assertThat(later(AT.minusSeconds(1), AT.plusSeconds(1), result.getPracticeId() + 1))
                .as("another practice's result")
                .isEmpty();
        // Result facts are immutable to the application; only a test fixture rewrites one into migrated history.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update("UPDATE result SET evidence_version=0 WHERE id=?", result.getId());
        });
        assertThat(later(AT.minusSeconds(1), AT.plusSeconds(1), result.getPracticeId()))
                .as("migrated history")
                .isEmpty();
    }

    private List<Result> later(Instant from, Instant to, long practiceId) {
        return basis.findLaterOpportunities(
                workspace.getId(), new Long[] {developerId}, new Long[] {practiceId}, from, to);
    }

    private void occasionedByTheWork() {
        jdbc.update("UPDATE review SET origin='EVENT' WHERE workspace_id=? AND id=?", workspace.getId(), reviewId);
    }
}
