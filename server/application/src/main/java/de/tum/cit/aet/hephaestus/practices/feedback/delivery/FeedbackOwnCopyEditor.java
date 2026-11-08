package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackCopyReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackOwnCopyEditor {
    private final FeedbackCopyRepository copies;
    private final FeedbackRepository feedback;
    private final List<FeedbackCopyReader> readers;
    private final List<SummaryWriter> summaries;
    private final List<LineNoteWriter> notes;
    private final FeedbackSendTransactions transactions;

    public void edit(FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, String owner) {
        var row = attempt.feedback();
        long workspace = row.getWorkspaceId();
        UUID feedbackId = row.getId();
        UUID previous = UUID.fromString(row.getComposition().path("editOf").asString());
        var previousFeedback =
                feedback.findByIdAndWorkspaceId(previous, workspace).orElseThrow();
        if (previousFeedback.getComposition().path("humanControlled").asBoolean()) return;
        var owned = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace, previous);
        var matching = readers.stream()
                .filter(reader -> reader.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) return;
        var reader = matching.getFirst();
        for (var copy : attempt.copies()) {
            if (copy.getState() != FeedbackCopyState.PLANNED && copy.getState() != FeedbackCopyState.WRITE_STARTED)
                continue;
            var prior = owned.stream()
                    .filter(old -> old.getPosition() == copy.getPosition() && old.getKind() == copy.getKind())
                    .findFirst()
                    .orElseThrow();
            if (prior.getExternalRef() == null || prior.getWriteMarker() == null || prior.getWriteKey() == null)
                continue;
            boolean started = copy.getState() == FeedbackCopyState.WRITE_STARTED;
            var proof = reader.read(
                    target,
                    List.of(new CopyReadRequest(
                            copy.getId(),
                            prior.getExternalRef(),
                            prior.getThreadRef(),
                            Objects.requireNonNull(started ? copy.getWriteMarker() : prior.getWriteMarker()),
                            anchor(prior))));
            if (proof.size() != 1) continue;
            var observed = proof.getFirst();
            if (!observed.copyId().equals(copy.getId())) continue;
            if (started) {
                if (observed.status() == CopyReadback.Status.FOUND) confirmed(copy, prior, owner);
                else if (observed.status() == CopyReadback.Status.GONE)
                    transactions.absent(workspace, feedbackId, owner, copy.getId());
                // The old marker cannot prove whether an ambiguous edit reached the provider. Never write again.
                continue;
            }
            if (observed.status() != CopyReadback.Status.FOUND
                    || observed.resolved()
                    || observed.outdated()
                    || !Objects.equals(observed.anchor(), anchor(prior))) {
                transactions.withholdCopies(
                        workspace, feedbackId, owner, List.of(copy.getId()), FeedbackStopReason.DELIVERY_UNCONFIRMED);
                continue;
            }
            var update = transactions.recipientWrite(row.getDeveloperId(), () -> {
                if (!transactions.fence(workspace, feedbackId, owner, List.of(copy.getId()), List.of())) return null;
                return update(attempt, target, copy, prior);
            });
            if (update == null) continue;
            if (!copy.getId().equals(update.copyId())
                    || (update.externalRef() != null && !prior.getExternalRef().equals(update.externalRef())))
                throw new IllegalArgumentException("A quiet edit must keep its original provider reference.");
            if (update.kind() == CopyUpdateKind.EDITED) confirmed(copy, prior, owner);
            else if (update.kind() == CopyUpdateKind.GONE || update.kind() == CopyUpdateKind.UNSUPPORTED)
                transactions.absent(workspace, feedbackId, owner, copy.getId());
        }
    }

    private CopyUpdateReceipt update(
            FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, FeedbackCopy copy, FeedbackCopy prior) {
        if (copy.getKind() == FeedbackCopyKind.SUMMARY) {
            var matching = summaries.stream()
                    .filter(writer -> writer.kind() == target.integration().kind())
                    .toList();
            if (matching.size() != 1)
                return CopyUpdateReceipt.transientFailure(copy.getId(), "No quiet summary writer is available.");
            return matching.getFirst()
                    .updateSummary(
                            target,
                            new SummaryHandle(Objects.requireNonNull(prior.getExternalRef()), prior.getExternalUrl()),
                            new SummaryContent(
                                    copy.getId(),
                                    Objects.requireNonNull(attempt.feedback().getBody()),
                                    Objects.requireNonNull(copy.getWriteKey()),
                                    copy.getWriteMarker()));
        }
        var matching = notes.stream()
                .filter(writer -> writer.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1)
            return CopyUpdateReceipt.transientFailure(copy.getId(), "No quiet note writer is available.");
        return matching
                .getFirst()
                .editOwn(
                        target,
                        List.of(new LineNoteWriter.OwnedEdit(
                                new CopyReceipt(
                                        copy.getId(),
                                        CopyDisposition.POSTED,
                                        prior.getExternalRef(),
                                        prior.getExternalUrl(),
                                        true,
                                        null,
                                        prior.getThreadRef()),
                                Objects.requireNonNull(prior.getWriteKey()),
                                FeedbackSendService.line(copy))))
                .stream()
                .filter(receipt -> receipt.copyId().equals(copy.getId()))
                .findFirst()
                .orElseGet(() ->
                        CopyUpdateReceipt.transientFailure(copy.getId(), "The quiet edit has no confirmed receipt."));
    }

    private void confirmed(FeedbackCopy copy, FeedbackCopy prior, String owner) {
        transactions.receipt(
                copy.getWorkspaceId(),
                copy.getFeedbackId(),
                owner,
                List.of(new CopyReceipt(
                        copy.getId(),
                        CopyDisposition.PRESERVED_EXISTING,
                        prior.getExternalRef(),
                        prior.getExternalUrl(),
                        true,
                        null,
                        prior.getThreadRef())));
    }

    private static @Nullable FeedbackAnchor anchor(FeedbackCopy copy) {
        if (copy.getPath() == null) return null;
        return new FeedbackAnchor(copy.getPath(), copy.getStartLine(), copy.getEndLine(), copy.getSide());
    }
}
