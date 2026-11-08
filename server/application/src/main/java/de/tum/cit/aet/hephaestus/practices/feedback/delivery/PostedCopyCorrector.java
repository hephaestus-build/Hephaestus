package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

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
import org.jspecify.annotations.Nullable;
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

    /** Brings each posted copy to what its marks and the piece's withdrawal now ask. Other copies stay untouched. */
    public void correct(FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, String owner) {
        var feedback = attempt.feedback();
        long workspaceId = feedback.getWorkspaceId();
        UUID feedbackId = feedback.getId();
        @Nullable Boolean restoreAllowed = null;
        for (var copy : attempt.copies()) {
            if (copy.getState() != FeedbackCopyState.POSTED && copy.getState() != FeedbackCopyState.RETRACTED) continue;
            // Read for each copy: a mark can change while this send writes the copies before it.
            boolean remove = visibility.withdrawn(workspaceId, feedbackId)
                    || copies.incorrectCopyIds(workspaceId, feedbackId).contains(copy.getId());
            if (remove == (copy.getState() == FeedbackCopyState.RETRACTED)) continue;
            if (!transactions.holdsLease(workspaceId, feedbackId, owner)) return;
            if (!remove) {
                if (restoreAllowed == null) restoreAllowed = transactions.mayRestore(workspaceId, feedbackId, owner);
                if (!restoreAllowed) continue;
            }
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
            if (copy.getKind() == FeedbackCopyKind.SUMMARY) correctSummary(feedback, copy, target, remove, owner);
            else correctNote(feedback, copy, target, remove, owner);
        }
    }

    private void correctSummary(
            Feedback feedback, FeedbackCopy copy, FeedbackTarget target, boolean remove, String owner) {
        if (copies.hasNewerSummaryReceipt(
                copy.getWorkspaceId(), copy.getFeedbackId(), Objects.requireNonNull(copy.getExternalRef()))) {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    null,
                    "A newer piece of feedback owns this provider reference.");
            return;
        }
        var matching = summaries.stream()
                .filter(w -> w.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            unsupported(copy, owner);
            return;
        }
        String body = remove ? "This feedback was withdrawn." : Objects.requireNonNull(feedback.getBody());
        var result = transactions.recipientWrite(
                feedback.getDeveloperId(),
                () -> matching.getFirst()
                        .updateSummary(
                                target,
                                new SummaryHandle(Objects.requireNonNull(copy.getExternalRef()), copy.getExternalUrl()),
                                new SummaryContent(
                                        copy.getId(),
                                        body,
                                        Objects.requireNonNull(copy.getWriteKey()),
                                        copy.getWriteMarker())));
        apply(copy, result, remove, owner);
    }

    private void correctNote(
            Feedback feedback, FeedbackCopy copy, FeedbackTarget target, boolean remove, String owner) {
        if (copies.hasNewerSummaryReceipt(
                copy.getWorkspaceId(), copy.getFeedbackId(), Objects.requireNonNull(copy.getExternalRef()))) {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    null,
                    "A newer piece of feedback owns this provider reference.");
            return;
        }
        var matching = notes.stream()
                .filter(w -> w.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            unsupported(copy, owner);
            return;
        }
        if (!remove && (copy.getWriteKey() == null || copy.getWriteMarker() == null || copy.getBody() == null)) {
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    null,
                    "The historical receipt cannot identify a safe provider update.");
            return;
        }
        var receipt = new CopyReceipt(
                copy.getId(), CopyDisposition.POSTED, copy.getExternalRef(), copy.getExternalUrl(), true, null);
        var writer = matching.getFirst();
        var updates = transactions.recipientWrite(
                feedback.getDeveloperId(),
                () -> remove
                        ? writer.retract(target, List.of(receipt))
                        : writer.restore(
                                target,
                                List.of(new LineNoteWriter.RetractedNote(receipt, FeedbackSendService.line(copy)))));
        var update =
                updates.stream().filter(r -> r.copyId().equals(copy.getId())).findFirst();
        if (update.isPresent()) apply(copy, update.get(), remove, owner);
        else
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    FeedbackCopyReadback.TRANSIENT,
                    " Correction receipt is unavailable.");
    }

    private void apply(FeedbackCopy copy, CopyUpdateReceipt receipt, boolean remove, String owner) {
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
        else
            transactions.correctionError(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    copy.getId(),
                    FeedbackCopyReadback.TRANSIENT,
                    " Provider correction is not confirmed.");
    }

    private void unsupported(FeedbackCopy copy, String owner) {
        transactions.correctionError(
                copy.getWorkspaceId(),
                copy.getFeedbackId(),
                owner,
                copy.getId(),
                null,
                "This provider cannot correct the posted copy.");
    }
}
