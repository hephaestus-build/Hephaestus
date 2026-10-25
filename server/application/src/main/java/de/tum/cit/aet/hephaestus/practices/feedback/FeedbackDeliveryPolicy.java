package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendStateMachine;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackChecks;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
@ConditionalOnCaptureRole
public class FeedbackDeliveryPolicy {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackGate gate;
    private final FeedbackSendQueue sendQueue;

    public FeedbackCheck check(Feedback row, FeedbackCheckStage stage, boolean approval) {
        return record(row, gate.evaluate(row, stage, approval));
    }

    /**
     * {@link #check} for the pieces of one workspace, decided by one gate read and keyed by feedback id. A piece that
     * needs its approval checked reads its latest decision on its own.
     */
    public Map<UUID, FeedbackCheck> checkAll(
            long workspaceId, List<Feedback> rows, FeedbackCheckStage stage, boolean approval) {
        var checks = gate.evaluateAll(workspaceId, rows, stage, approval);
        rows.forEach(row -> {
            var check = checks.get(row.getId());
            if (check != null) record(row, check);
        });
        return checks;
    }

    private FeedbackCheck record(Feedback row, FeedbackCheck check) {
        Map<FeedbackCheckStage, FeedbackChecks> checks = new EnumMap<>(FeedbackCheckStage.class);
        checks.putAll(row.getChecks());
        checks.put(check.stage(), new FeedbackChecks(List.of(check), check.checkedAt()));
        feedback.updateChecks(row.getWorkspaceId(), row.getId(), checks);
        row.setChecks(Map.copyOf(checks));
        return check;
    }

    public void withhold(Feedback row, FeedbackStopReason reason) {
        copies.withholdPlanned(row.getWorkspaceId(), row.getId(), reason.name());
        var children = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(row.getWorkspaceId(), row.getId());
        var projection = FeedbackSendStateMachine.project(row, children, reason);
        feedback.updateDelivery(
                row.getWorkspaceId(),
                row.getId(),
                projection.state(),
                projection.stopReason(),
                projection.deliveredAt());
    }

    public void prepared(Feedback row) {
        if (row.getState() == FeedbackState.READY || row.getState() == FeedbackState.AWAITING_APPROVAL) {
            var check = check(row, FeedbackCheckStage.COMPOSITION, false);
            if (!check.allowed()) {
                withhold(row, Objects.requireNonNull(check.reason()));
            } else if (row.getState() == FeedbackState.AWAITING_APPROVAL || gate.requiresApproval(row)) {
                feedback.updateDelivery(row.getWorkspaceId(), row.getId(), FeedbackState.AWAITING_APPROVAL, null, null);
            } else if (row.getDestination() == FeedbackDestination.ON_WORK) {
                var automatic = check(row, FeedbackCheckStage.AUTOMATIC, true);
                if (automatic.allowed()) sendQueue.queue(row, FeedbackSendAction.POST, FeedbackSendState.PENDING);
                else withhold(row, Objects.requireNonNull(automatic.reason()));
            }
        }
    }

    public FeedbackState restoredState(Feedback row, Optional<FeedbackDecision> last) {
        return last.filter(d -> d.getAction() == FeedbackDecisionAction.REJECT).isPresent()
                ? FeedbackState.REJECTED
                : gate.requiresApproval(row)
                                && !gate.evaluate(row, FeedbackCheckStage.APPROVED, true)
                                        .allowed()
                        ? FeedbackState.AWAITING_APPROVAL
                        : FeedbackState.READY;
    }
}
