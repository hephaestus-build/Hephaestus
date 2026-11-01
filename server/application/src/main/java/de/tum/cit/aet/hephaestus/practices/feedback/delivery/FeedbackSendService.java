package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackSendService {
    private static final Logger log = LoggerFactory.getLogger(FeedbackSendService.class);
    private static final int MAX_READBACK_ATTEMPTS = 8;
    private final FeedbackSendTransactions transactions;
    private final FeedbackTargetResolver targets;
    private final List<SummaryWriter> summaries;
    private final List<LineNoteWriter> notes;
    private final PostedCopyCorrector corrector;

    public void send(long workspaceId, UUID feedbackId) {
        String owner = UUID.randomUUID().toString();
        var attempt = transactions.claim(workspaceId, feedbackId, owner);
        if (attempt == null) return;
        try {
            var target = attempt.action() == FeedbackSendAction.RETRACT
                            || attempt.copies().stream().anyMatch(copy -> copy.getWriteStartedAt() != null)
                    ? targets.resolveExisting(attempt.feedback())
                    : targets.resolve(attempt.feedback());
            if (attempt.action() == FeedbackSendAction.RETRACT) {
                corrector.correct(attempt, target, owner);
                return;
            }
            if (target.item().kind().value().equals("scm.pull_request")) {
                packageOnWork(attempt, target, owner);
            } else {
                for (var copy : attempt.copies())
                    if (copy.getKind() == FeedbackCopyKind.SUMMARY) summary(attempt, target, owner, copy);
            }
        } catch (FeedbackTargetResolver.TargetUnavailable refusal) {
            transactions.refuseTarget(workspaceId, feedbackId, owner, refusal.reason());
        } catch (FeedbackSendTransactions.RecipientWriteRefused refusal) {
            transactions.refuseRecipient(workspaceId, feedbackId, owner);
        } catch (RuntimeException failure) {
            transactions.error(workspaceId, feedbackId, owner);
            log.warn("Feedback send did not settle: workspaceId={}, feedbackId={}", workspaceId, feedbackId);
        } finally {
            transactions.finish(workspaceId, feedbackId, owner, attempt.attempts() >= MAX_READBACK_ATTEMPTS);
        }
    }

    private void summary(
            FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, String owner, FeedbackCopy copy) {
        if (copy.getState() != FeedbackCopyState.PLANNED && copy.getState() != FeedbackCopyState.WRITE_STARTED) return;
        var matching = summaries.stream()
                .filter(writer -> writer.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            transactions.withholdCopies(
                    copy.getWorkspaceId(),
                    copy.getFeedbackId(),
                    owner,
                    List.of(copy.getId()),
                    FeedbackStopReason.RECIPIENT_UNAVAILABLE);
            return;
        }
        var writer = matching.getFirst();
        var content = new SummaryContent(
                copy.getId(),
                Objects.requireNonNull(attempt.feedback().getBody()),
                Objects.requireNonNull(copy.getWriteKey()),
                Objects.requireNonNull(copy.getWriteMarker()));
        if (copy.getState() == FeedbackCopyState.WRITE_STARTED) {
            var lookup = writer.findExistingSummary(target, content);
            if (lookup.presence() == LookupPresence.FOUND) {
                var handle = Objects.requireNonNull(lookup.handle());
                transactions.receipt(
                        copy.getWorkspaceId(),
                        copy.getFeedbackId(),
                        owner,
                        List.of(new CopyReceipt(
                                copy.getId(),
                                CopyDisposition.PRESERVED_EXISTING,
                                handle.externalRef(),
                                handle.url(),
                                true,
                                null)));
            } else if (lookup.presence() == LookupPresence.ABSENT)
                transactions.absent(copy.getWorkspaceId(), copy.getFeedbackId(), owner, copy.getId());
            return;
        }
        var handle = transactions.recipientWrite(attempt.feedback().getDeveloperId(), () -> {
            if (!transactions.fence(
                    copy.getWorkspaceId(), copy.getFeedbackId(), owner, List.of(copy.getId()), List.of())) return null;
            return writer.postSummary(target, content);
        });
        if (handle == null) return;
        transactions.receipt(
                copy.getWorkspaceId(),
                copy.getFeedbackId(),
                owner,
                List.of(new CopyReceipt(
                        copy.getId(), CopyDisposition.POSTED, handle.externalRef(), handle.url(), true, null)));
    }

    private void packageOnWork(FeedbackSendTransactions.Attempt attempt, FeedbackTarget target, String owner) {
        long workspaceId = attempt.feedback().getWorkspaceId();
        UUID feedbackId = attempt.feedback().getId();
        var unresolved = copies(attempt, FeedbackCopyState.WRITE_STARTED);
        var planned = copies(attempt, FeedbackCopyState.PLANNED);
        // While a write may have reached the provider, only readback may settle it: a planned sibling is not sent.
        if (!unresolved.isEmpty() && !planned.isEmpty()) {
            transactions.withholdCopies(
                    workspaceId,
                    feedbackId,
                    owner,
                    planned.stream().map(FeedbackCopy::getId).toList(),
                    FeedbackStopReason.DELIVERY_UNCONFIRMED);
            planned = List.of();
        }
        if (unresolved.isEmpty() && planned.isEmpty()) return;
        var matching = notes.stream()
                .filter(writer -> writer.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            transactions.withholdCopies(
                    workspaceId,
                    feedbackId,
                    owner,
                    (unresolved.isEmpty() ? planned : unresolved)
                            .stream().map(FeedbackCopy::getId).toList(),
                    FeedbackStopReason.RECIPIENT_UNAVAILABLE);
            return;
        }
        var writer = matching.getFirst();
        if (!unresolved.isEmpty()) {
            // The submission that may have happened holds every copy written with it, posted ones included.
            var written = new ArrayList<>(copies(attempt, FeedbackCopyState.POSTED));
            written.addAll(unresolved);
            var found = writer.findPosted(target, publication(attempt, written));
            if (found == null) return;
            var ids = unresolved.stream().map(FeedbackCopy::getId).toList();
            transactions.receipt(
                    workspaceId,
                    feedbackId,
                    owner,
                    found.stream()
                            .filter(receipt -> ids.contains(receipt.copyId()))
                            .toList());
            for (var copy : unresolved)
                if (found.stream().noneMatch(receipt -> receipt.copyId().equals(copy.getId())))
                    transactions.absent(copy.getWorkspaceId(), copy.getFeedbackId(), owner, copy.getId());
            return;
        }
        var publication = publication(attempt, planned);
        var result = transactions.recipientWrite(
                attempt.feedback().getDeveloperId(),
                () -> writer.postPackage(
                        target,
                        publication,
                        (attempting, completed) ->
                                transactions.fence(workspaceId, feedbackId, owner, attempting, completed)));
        transactions.receipt(workspaceId, feedbackId, owner, result.receipts());
        transactions.withholdCopies(
                workspaceId, feedbackId, owner, result.withheldCopyIds(), FeedbackStopReason.VOLUME_CAPPED);
    }

    private static List<FeedbackCopy> copies(FeedbackSendTransactions.Attempt attempt, FeedbackCopyState state) {
        return attempt.copies().stream()
                .filter(copy -> copy.getState() == state
                        && (copy.getKind() == FeedbackCopyKind.SUMMARY
                                || copy.getKind() == FeedbackCopyKind.LINE_NOTE
                                || copy.getKind() == FeedbackCopyKind.FILE_NOTE))
                .toList();
    }

    private static OnWorkPackage publication(FeedbackSendTransactions.Attempt attempt, List<FeedbackCopy> copies) {
        var summary = copies.stream()
                .filter(copy -> copy.getKind() == FeedbackCopyKind.SUMMARY)
                .findFirst()
                .map(copy -> new SummaryContent(
                        copy.getId(),
                        Objects.requireNonNull(attempt.feedback().getBody()),
                        Objects.requireNonNull(copy.getWriteKey()),
                        Objects.requireNonNull(copy.getWriteMarker())))
                .orElse(null);
        return new OnWorkPackage(
                summary,
                copies.stream()
                        .filter(copy -> copy.getKind() != FeedbackCopyKind.SUMMARY)
                        .map(FeedbackSendService::line)
                        .toList());
    }

    static LineNote line(FeedbackCopy copy) {
        return new LineNote(
                copy.getId(),
                new FeedbackAnchor(
                        Objects.requireNonNull(copy.getPath()), copy.getStartLine(), copy.getEndLine(), copy.getSide()),
                Objects.requireNonNull(copy.getBody()),
                Objects.requireNonNull(copy.getWriteKey()),
                Objects.requireNonNull(copy.getWriteMarker()));
    }
}
