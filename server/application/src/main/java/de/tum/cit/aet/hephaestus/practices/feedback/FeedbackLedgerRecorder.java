package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.provenance.ProvenanceRecord;
import de.tum.cit.aet.hephaestus.core.provenance.ProvenanceSigner;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records new prepared feedback in the ledger, inside the preparing transaction: it signs the words that the
 * preparation fixes, writes the feedback, its result basis and its copies, and counts the state that the feedback
 * starts in.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackLedgerRecorder {
    private final FeedbackRepository feedback;
    private final FeedbackBasisRepository basis;
    private final FeedbackCopyRepository copies;
    private final ProvenanceSigner provenance;
    private final FeedbackOutcomes outcomes;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            Feedback row,
            List<FeedbackBasis> edges,
            List<FeedbackCopy> planned,
            @Nullable String model,
            Instant preparedAt) {
        sign(row, planned, model, preparedAt);
        feedback.saveAndFlush(row);
        basis.saveAll(edges);
        copies.saveAll(planned);
        basis.flush();
        copies.flush();
        outcomes.created(row);
    }

    /**
     * Signs the words that this preparation fixes: the feedback's body and each note's own words. They do not change
     * afterwards, so each record holds for every copy that carries them.
     */
    private void sign(Feedback row, List<FeedbackCopy> planned, @Nullable String model, Instant preparedAt) {
        String body = row.getBody();
        if (body != null)
            row.setProvenance(provenance
                    .sign(ProvenanceRecord.Kind.FEEDBACK, body, model, preparedAt)
                    .jws());
        for (var copy : planned) {
            String words = copy.getBody();
            if (words != null)
                copy.setProvenance(provenance
                        .sign(ProvenanceRecord.Kind.FEEDBACK, words, model, preparedAt)
                        .jws());
        }
    }
}
