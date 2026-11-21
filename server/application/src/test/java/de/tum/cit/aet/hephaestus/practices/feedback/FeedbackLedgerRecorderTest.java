package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.provenance.ProvenanceRecord;
import de.tum.cit.aet.hephaestus.core.provenance.ProvenanceSigner;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FeedbackLedgerRecorderTest {
    @Test
    void shouldSurfaceAConstraintFailureBeforeSendingIsPossible() {
        var feedback = mock(FeedbackRepository.class);
        var basis = mock(FeedbackBasisRepository.class);
        var copies = mock(FeedbackCopyRepository.class);
        var provenance = mock(ProvenanceSigner.class);
        var outcomes = mock(FeedbackOutcomes.class);
        var row = Feedback.builder().build();
        doThrow(new IllegalStateException("basis constraint")).when(basis).flush();
        assertThatThrownBy(() -> new FeedbackLedgerRecorder(feedback, basis, copies, provenance, outcomes)
                        .record(row, List.of(), List.of(), null, Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("basis constraint");
    }

    @Test
    void shouldSignTheFeedbackAndEachNoteBeforeSavingAndCountOnlyAfterTheRowsAreWritten() {
        var feedback = mock(FeedbackRepository.class);
        var basis = mock(FeedbackBasisRepository.class);
        var copies = mock(FeedbackCopyRepository.class);
        var provenance = mock(ProvenanceSigner.class);
        var outcomes = mock(FeedbackOutcomes.class);
        var record = mock(ProvenanceRecord.class);
        when(provenance.sign(eq(ProvenanceRecord.Kind.FEEDBACK), eq("summary"), eq("model"), eq(Instant.EPOCH)))
                .thenReturn(new ProvenanceSigner.Signed(record, "signed-summary"));
        when(provenance.sign(eq(ProvenanceRecord.Kind.FEEDBACK), eq("note"), eq("model"), eq(Instant.EPOCH)))
                .thenReturn(new ProvenanceSigner.Signed(record, "signed-note"));
        var row = Feedback.builder().body("summary").build();
        var note = FeedbackCopy.builder().body("note").build();

        new FeedbackLedgerRecorder(feedback, basis, copies, provenance, outcomes)
                .record(row, List.of(), List.of(note), "model", Instant.EPOCH);

        assertThat(row.getProvenance()).isEqualTo("signed-summary");
        assertThat(note.getProvenance()).isEqualTo("signed-note");
        var order = inOrder(provenance, feedback, copies, outcomes);
        order.verify(provenance).sign(any(), eq("summary"), any(), any());
        order.verify(provenance).sign(any(), eq("note"), any(), any());
        order.verify(feedback).saveAndFlush(row);
        order.verify(copies).saveAll(List.of(note));
        order.verify(copies).flush();
        order.verify(outcomes).created(row);
    }
}
