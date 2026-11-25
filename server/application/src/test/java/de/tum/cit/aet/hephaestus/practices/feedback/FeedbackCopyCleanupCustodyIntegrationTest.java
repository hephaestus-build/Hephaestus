package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class FeedbackCopyCleanupCustodyIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    FeedbackCopyCleanupCustody custody;

    @Test
    void shouldRetainUnknownCopyCustodyUntilAReceiptOrProvedAbsenceExists() {
        var parent = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, null);
        var copy = summary(parent, FeedbackCopyState.WRITE_STARTED);
        assertThatThrownBy(() -> custody.requireAcknowledged(workspace.getId(), List.of(parent.getId())))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(copies.findById(copy.getId())).isPresent();
        copies.recordAbsent(workspace.getId(), parent.getId(), copy.getId(), "Exact provider readback");
        assertThatCode(() -> custody.requireAcknowledged(workspace.getId(), List.of(parent.getId())))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldEraseACopyThatReadbackProvedAbsentAndTheGateThenWithheld() {
        var parent = piece(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, null);
        var copy = summary(parent, FeedbackCopyState.WRITE_STARTED);
        copies.withholdAbsent(
                workspace.getId(), parent.getId(), copy.getId(), FeedbackStopReason.REVIEWED_VERSION_CHANGED.name());

        assertThatCode(() -> custody.requireAcknowledged(workspace.getId(), List.of(parent.getId())))
                .doesNotThrowAnyException();
        assertThat(feedback.expiredIds(workspace.getId(), AT, 100)).contains(parent.getId());
    }
}
