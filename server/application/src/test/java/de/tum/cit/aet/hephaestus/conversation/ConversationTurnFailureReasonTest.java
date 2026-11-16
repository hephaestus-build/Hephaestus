package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurn.FailureReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class ConversationTurnFailureReasonTest {

    @Test
    void shouldNameTheReasonOfEachCauseThatTheLedgerRecords() {
        assertThat(FailureReason.of(ConversationTurn.START_WAIT_EXPIRED_CAUSE))
                .isEqualTo(FailureReason.START_WAIT_EXPIRED);
        assertThat(FailureReason.of(ConversationTurn.LEASE_EXPIRED_CAUSE)).isEqualTo(FailureReason.ATTEMPTS_EXHAUSTED);
        assertThat(FailureReason.of(ConversationTurn.CONTEXT_CHANGED_CAUSE)).isEqualTo(FailureReason.CONTEXT_ERASED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STALLED", "ERROR: provider_unavailable", "ERROR: upstream_stalled", "ERROR: model_error"})
    void shouldNameTheModelWhenTheSandboxReportsThatItStoppedAnswering(String report) {
        assertThat(FailureReason.of(ConversationTurn.SANDBOX_REPORTED_CAUSE + report))
                .isEqualTo(FailureReason.MODEL_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "its conversation sandbox reported ERROR",
                "its conversation sandbox reported ERROR: worker_unavailable",
                "its conversation sandbox reported DEADLINE: deadline",
                "its sandbox ended the run without the turn",
                "digest_no_longer_eligible",
            })
    void shouldNameNoReasonForAnyOtherCause(String cause) {
        assertThat(FailureReason.of(cause)).isEqualTo(FailureReason.UNKNOWN);
    }

    @Test
    void shouldNameNoReasonWhenTheTurnRecordedNoCause() {
        @Nullable String none = null;
        assertThat(FailureReason.of(none)).isEqualTo(FailureReason.UNKNOWN);
    }
}
