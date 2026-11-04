package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkChanged;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackMarkListener {
    private final FeedbackRepository feedback;
    private final FeedbackOutcomes outcomes;
    private final FeedbackBasisRepository basis;
    private final FeedbackCopyRepository copies;
    private final FeedbackSendRepository sends;
    private final FeedbackDecisionRepository decisionHistory;
    private final FeedbackVisibilityService visibility;
    private final FeedbackSendQueue sendQueue;
    private final FeedbackDeliveryPolicy policy;

    @EventListener
    public void marked(ResultMarkChanged mark) {
        if (mark.kind() != ResultMarkKind.INCORRECT) return;
        var ids = basis.findByWorkspaceIdAndResultId(mark.workspaceId(), mark.resultId()).stream()
                .map(FeedbackBasis::getFeedbackId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        for (UUID id : ids) {
            var row = feedback.lockByIdAndWorkspaceId(id, mark.workspaceId())
                    .orElseThrow(() -> new EntityNotFoundException("Feedback", id.toString()));
            var send = sends.lockByFeedbackId(row.getWorkspaceId(), id);
            var children = copies.lockByFeedbackId(row.getWorkspaceId(), id);
            boolean onWork = row.getDestination() == FeedbackDestination.ON_WORK;
            if (!mark.removed()) {
                if (onWork) copies.withholdIncorrect(row.getWorkspaceId(), id);
                else
                    copies.withholdPlanned(row.getWorkspaceId(), id, FeedbackStopReason.RESULT_MARKED_INCORRECT.name());
                var incorrect = copies.incorrectCopyIds(row.getWorkspaceId(), id);
                if (onWork
                        && children.stream()
                                .anyMatch(c ->
                                        c.getState() == FeedbackCopyState.POSTED && incorrect.contains(c.getId()))) {
                    if (send.isEmpty() || send.get().getState() == FeedbackSendState.DONE)
                        sendQueue.queue(row, FeedbackSendAction.RETRACT, FeedbackSendState.PENDING);
                } else if ((!onWork || basis.isIncorrect(row.getWorkspaceId(), id))
                        && children.stream().noneMatch(c -> c.getState() == FeedbackCopyState.WRITE_STARTED))
                    policy.withhold(row, FeedbackStopReason.RESULT_MARKED_INCORRECT);
            } else if (onWork
                    && !visibility.withdrawn(row.getWorkspaceId(), id)
                    && children.stream()
                            .anyMatch(c -> c.getState() == FeedbackCopyState.POSTED
                                    || c.getState() == FeedbackCopyState.RETRACTED)) {
                boolean restoredUnsent = copies.restoreUnsent(row.getWorkspaceId(), id) > 0;
                if ((send.isEmpty() || send.get().getState() == FeedbackSendState.DONE)
                        && policy.check(row, FeedbackCheckStage.EGRESS, true).allowed())
                    // A copy that this mark held back never reached the provider. Its send settles first, and that
                    // settlement then restores any posted sibling.
                    sendQueue.queue(
                            row,
                            restoredUnsent ? FeedbackSendAction.POST : FeedbackSendAction.RETRACT,
                            FeedbackSendState.PENDING);
            } else if (row.getState() == FeedbackState.WITHHELD
                    && row.getStopReason() == FeedbackStopReason.RESULT_MARKED_INCORRECT
                    && !visibility.withdrawn(row.getWorkspaceId(), id)
                    && children.stream()
                            .allMatch(copy -> copy.getPostedAt() == null
                                    && copy.getExternalRef() == null
                                    && copy.getMentorTurnId() == null
                                    && copy.getState() != FeedbackCopyState.WRITE_STARTED
                                    && (copy.getWriteStartedAt() == null
                                            || (row.getDestination() == FeedbackDestination.ON_WORK
                                                    && copy.getReadback() == FeedbackCopyReadback.ABSENT)))) {
                var check = policy.check(row, FeedbackCheckStage.EGRESS, false);
                if (!check.allowed()) {
                    policy.withhold(row, Objects.requireNonNull(check.reason()));
                    continue;
                }
                var last = decisionHistory.findLatestApproval(row.getWorkspaceId(), id);
                FeedbackState state = policy.restoredState(row, last);
                copies.restoreUnsent(row.getWorkspaceId(), id);
                outcomes.update(row, state, null, row.getDeliveredAt());
                if (state == FeedbackState.READY && row.getDestination() == FeedbackDestination.ON_WORK)
                    sendQueue.queue(row, FeedbackSendAction.POST, FeedbackSendState.PENDING);
            }
        }
    }
}
