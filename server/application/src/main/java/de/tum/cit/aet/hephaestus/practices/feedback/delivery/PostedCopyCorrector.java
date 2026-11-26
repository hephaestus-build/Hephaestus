package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyReadback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PostedCopyCorrector {
    private final FeedbackCopyRepository copies;
    private final FeedbackVisibilityService visibility;
    private final List<SummaryWriter> summaries;
    private final List<LineNoteWriter> notes;
    private final FeedbackSendTransactions transactions;

    /**
     * Brings each posted copy to what its marks and the piece's withdrawal now ask. Other copies stay untouched. Every
     * removal goes first, so a restore that the send boundary refuses leaves no removal of the same attempt undone. A
     * removal that the provider has not confirmed yet keeps every restore for a later attempt: the attempt then reads
     * the removal back, and no refused restore can end the action before the removal settles.
     */
    public void correct(FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, ActionAttempt owner) {
        if (correct(attempt, target, owner, true) != Pass.SETTLED) return;
        correct(attempt, target, owner, false);
    }

    private enum Pass {
        /** Every copy of the pass is settled. */
        SETTLED,
        /** A copy waits for the provider to confirm its correction. */
        UNCONFIRMED,
        /** The attempt no longer holds its action, or the send boundary refused. */
        STOPPED
    }

    private Pass correct(
            FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, ActionAttempt owner, boolean removals) {
        var feedback = attempt.feedback();
        long workspaceId = feedback.getWorkspaceId();
        UUID feedbackId = feedback.getId();
        var pass = Pass.SETTLED;
        for (var copy : attempt.copies()) {
            if (copy.getState() != FeedbackCopyState.POSTED && copy.getState() != FeedbackCopyState.RETRACTED) continue;
            // Read for each copy: a mark can change while this send writes the copies before it.
            boolean remove = visibility.withdrawn(workspaceId, feedbackId)
                    || copies.incorrectCopyIds(workspaceId, feedbackId).contains(copy.getId());
            if (remove == (copy.getState() == FeedbackCopyState.RETRACTED)) continue;
            if (remove != removals) continue;
            // The admission is the last step before the provider call: nothing after it waits for a lock.
            var admission = transactions.admitCorrection(workspaceId, feedbackId, owner, remove);
            if (admission == FeedbackSendTransactions.CorrectionAdmission.REFUSED) return Pass.STOPPED;
            if (admission == FeedbackSendTransactions.CorrectionAdmission.NOT_DUE) continue;
            if (copy.getExternalRef() == null
                    || (copy.getKind() == FeedbackCopyKind.SUMMARY && copy.getWriteKey() == null)) {
                transactions.correctionError(
                        workspaceId,
                        feedbackId,
                        owner,
                        copy.getId(),
                        null,
                        "The historical receipt cannot identify a safe provider update.");
                continue;
            }
            boolean confirmed = copy.getKind() == FeedbackCopyKind.SUMMARY
                    ? correctSummary(feedback, copy, target, remove, owner)
                    : correctNote(copy, target, remove, owner);
            if (!confirmed) pass = Pass.UNCONFIRMED;
        }
        return pass;
    }

    /** @return false while the provider has not confirmed the correction, so the attempt reads it back later */
    private boolean correctSummary(
            Feedback feedback, FeedbackCopy copy, FeedbackTarget target, boolean remove, ActionAttempt owner) {
        if (copies.hasNewerSummaryReceipt(
                copy.getWorkspaceId(), copy.getFeedbackId(), Objects.requireNonNull(copy.getExternalRef()))) {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    null,
                    "A newer piece of feedback owns this provider reference.");
            return true;
        }
        var matching = summaries.stream()
                .filter(w -> w.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            unsupported(copy, owner);
            return true;
        }
        String body = remove ? "This feedback was withdrawn." : Objects.requireNonNull(feedback.getBody());
        var result = matching.getFirst()
                .updateSummary(
                        target,
                        new SummaryHandle(Objects.requireNonNull(copy.getExternalRef()), copy.getExternalUrl()),
                        new SummaryContent(
                                copy.getId(),
                                body,
                                Objects.requireNonNull(copy.getWriteKey()),
                                copy.getWriteMarker(),
                                null,
                                // A withdrawal's words are not generated, so they carry no record.
                                remove ? null : feedback.getProvenance()));
        return apply(copy, result, remove, owner);
    }

    /** @return false while the provider has not confirmed the correction, as {@link #correctSummary} */
    private boolean correctNote(FeedbackCopy copy, FeedbackTarget target, boolean remove, ActionAttempt owner) {
        if (copies.hasNewerSummaryReceipt(
                copy.getWorkspaceId(), copy.getFeedbackId(), Objects.requireNonNull(copy.getExternalRef()))) {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    null,
                    "A newer piece of feedback owns this provider reference.");
            return true;
        }
        var matching = notes.stream()
                .filter(w -> w.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            unsupported(copy, owner);
            return true;
        }
        if (!remove && (copy.getWriteKey() == null || copy.getWriteMarker() == null || copy.getBody() == null)) {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    null,
                    "The historical receipt cannot identify a safe provider update.");
            return true;
        }
        var receipt = new CopyReceipt(
                copy.getId(), CopyDisposition.POSTED, copy.getExternalRef(), copy.getExternalUrl(), true, null);
        var writer = matching.getFirst();
        var updates = remove
                ? writer.retract(target, List.of(receipt))
                : writer.restore(
                        target, List.of(new LineNoteWriter.RetractedNote(receipt, FeedbackSendService.line(copy))));
        var update =
                updates.stream().filter(r -> r.copyId().equals(copy.getId())).findFirst();
        if (update.isPresent()) return apply(copy, update.get(), remove, owner);
        transactions.correctionError(
                copy.getWorkspaceId(),
                copy.getFeedbackId(),
                owner,
                copy.getId(),
                FeedbackCopyReadback.TRANSIENT,
                " Correction receipt is unavailable.");
        return false;
    }

    /** @return false when the provider left the correction unconfirmed */
    private boolean apply(FeedbackCopy copy, CopyUpdateReceipt receipt, boolean remove, ActionAttempt owner) {
        if (!copy.getId().equals(receipt.copyId())
                || (receipt.externalRef() != null && !Objects.equals(copy.getExternalRef(), receipt.externalRef())))
            throw new IllegalArgumentException("Correction must address the original copy.");
        if (receipt.kind() == CopyUpdateKind.EDITED || receipt.kind() == CopyUpdateKind.GONE) {
            transactions.correction(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    remove || receipt.kind() == CopyUpdateKind.GONE
                            ? FeedbackCopyState.RETRACTED.name()
                            : FeedbackCopyState.POSTED.name());
            if (!remove && receipt.kind() == CopyUpdateKind.GONE)
                transactions.correctionError(
                        copy.getWorkspaceId(),
                        copy.getFeedbackId(),
                        owner,
                        copy.getId(),
                        null,
                        "The provider copy no longer exists, so it stays retracted.");
        } else if (receipt.kind() == CopyUpdateKind.UNSUPPORTED) unsupported(copy, owner);
        else {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    FeedbackCopyReadback.TRANSIENT,
                    " Provider correction is not confirmed.");
            return false;
        }
        return true;
    }

    private void unsupported(FeedbackCopy copy, ActionAttempt owner) {
        transactions.correctionError(
                copy.getWorkspaceId(),
                copy.getFeedbackId(),
                owner,
                copy.getId(),
                null,
                "This provider cannot correct the posted copy.");
    }
}
