package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.authority.spi.CheckedAuthority;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * The sends of each feedback to its work, as actions in the outbox. A feedback's sends share one lane, so they run one
 * at a time and in order; the newest action of the lane is the feedback's send ({@link FeedbackSend}). Each caller
 * holds the feedback row lock.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackSendQueue {
    /** The attempts of one send. A send whose write may have reached the provider reads back on each. */
    public static final int MAX_ATTEMPTS = 8;

    private static final String PREFIX = "feedback:";

    private final AgentActions actions;
    private final AuthorityGenerations generations;
    private final WorkspaceMemberships memberships;
    private final ObjectMapper mapper;
    private final FeedbackCopyRepository copies;
    private final FeedbackVisibilityService visibility;
    private final ConfigAuditPort audit;

    /** The newest send of a feedback, if it has one. */
    @Transactional
    public Optional<FeedbackSend> current(long workspaceId, UUID feedbackId) {
        return newest(workspaceId, feedbackId);
    }

    private Optional<FeedbackSend> newest(long workspaceId, UUID feedbackId) {
        return actions.newestInLane(workspaceId, lane(feedbackId))
                .map(action -> FeedbackSend.of(feedbackId, workspaceId, action));
    }

    /**
     * Queues a send, or with {@link FeedbackSendState#DONE} only withdraws the sends that have not started. A new send
     * replaces one that has not started. While a send runs, it is refused: the running send decides, as it ends, what
     * follows it ({@link #follow}).
     *
     * @param authority the authority that the send's request was checked under: an admin's decision takes {@link
     *     #asAdmin}, and work that checks no access of its own in this transaction takes {@link #requestedNow}
     */
    @Transactional
    public void queue(Feedback row, FeedbackSendAction action, FeedbackSendState state, CheckedAuthority authority) {
        if (state == FeedbackSendState.DONE) {
            actions.cancelPending(row.getWorkspaceId(), lane(row.getId()));
            return;
        }
        enqueue(
                row,
                action == FeedbackSendAction.POST && row.getComposition().hasNonNull("editOf")
                        ? FeedbackSendAction.EDIT
                        : action,
                authority);
    }

    /**
     * The authority of a workspace admin's decision: it reads the stamp, then checks that the actor is still at least an
     * admin, in the caller's transaction (ADR 0134). The route checked the role earlier, outside this transaction. The
     * send also depends on the feedback's developer, whose AI choice can narrow it.
     *
     * @throws AccessForbiddenException when the actor is no longer an admin of the workspace
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckedAuthority asAdmin(Feedback row, long actorAccountId) {
        long workspaceId = row.getWorkspaceId();
        return generations
                .authorize(
                        workspaceId,
                        List.of(row.getDeveloperId()),
                        () -> memberships
                                .activeRole(workspaceId, actorAccountId)
                                .filter(role -> role.isAtLeast(WorkspaceRole.ADMIN))
                                .isPresent())
                .orElseThrow(() -> new AccessForbiddenException("Only a workspace admin can decide on feedback."));
    }

    /**
     * The authority of a send that its request checks no access for in this transaction, such as a policy's send. It
     * depends on the feedback's developer, whose AI choice can narrow it.
     */
    public CheckedAuthority requestedNow(Feedback row) {
        return generations.requestedNow(row.getWorkspaceId(), List.of(row.getDeveloperId()));
    }

    private void enqueue(Feedback row, FeedbackSendAction action, CheckedAuthority authority) {
        var prior = actions.newestInLane(row.getWorkspaceId(), lane(row.getId()));
        var send = prior.map(p -> FeedbackSend.of(row.getId(), row.getWorkspaceId(), p));
        if (send.filter(s -> s.getState() == FeedbackSendState.SENDING
                        || (action == FeedbackSendAction.RETRACT
                                && s.getAction() == FeedbackSendAction.POST
                                && s.getState() != FeedbackSendState.DONE))
                .isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The existing send must settle first.");
        next(row, action, prior, authority);
    }

    /**
     * Queues the correction that follows a send that ends now; the end of that send calls it, in its transaction. It
     * never keeps the ended send's stamp: an authorized decision, recorded while that send ran, asked for the
     * correction, and a removal goes out whatever happened to the workspace's access since ({@link
     * ActionAttempt.Fence#beginRemoval}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void follow(Feedback row, FeedbackSendAction action) {
        next(row, action, actions.newestInLane(row.getWorkspaceId(), lane(row.getId())), requestedNow(row));
    }

    private void next(Feedback row, FeedbackSendAction action, Optional<LaneAction> prior, CheckedAuthority authority) {
        actions.cancelPending(row.getWorkspaceId(), lane(row.getId()));
        int generation = prior.map(p -> generationOf(p.idempotencyKey()) + 1).orElse(1);
        actions.enqueue(new ActionRequest(
                kindOf(action),
                authority,
                lane(row.getId()),
                lane(row.getId()) + ":" + generation + ":" + action.name(),
                mapper.createObjectNode().put("feedbackId", row.getId().toString()),
                row.getDeveloperId(),
                MAX_ATTEMPTS,
                null,
                null));
    }

    /** The kind of action that carries out a send. */
    public static ActionKind kindOf(FeedbackSendAction action) {
        return switch (action) {
            case POST, READBACK -> ActionKind.DELIVER_FEEDBACK;
            case EDIT -> ActionKind.EDIT_FEEDBACK;
            case RETRACT -> ActionKind.RETRACT_FEEDBACK;
        };
    }

    /** The lane of a feedback's sends. */
    static String lane(UUID feedbackId) {
        return PREFIX + feedbackId;
    }

    /** The key is {@code feedback:<feedback>:<generation>:<action>}. */
    public static FeedbackSendAction actionOf(String idempotencyKey) {
        return FeedbackSendAction.valueOf(idempotencyKey.substring(idempotencyKey.lastIndexOf(':') + 1));
    }

    public static UUID feedbackOf(String idempotencyKey) {
        return UUID.fromString(idempotencyKey.substring(PREFIX.length(), PREFIX.length() + 36));
    }

    private static int generationOf(String idempotencyKey) {
        String rest = idempotencyKey.substring(PREFIX.length() + 37);
        return Integer.parseInt(rest.substring(0, rest.indexOf(':')));
    }

    /**
     * Queues the existing readback of a write whose visibility stayed unknown after its send settled. The claimed
     * attempt still holds the started copy, so the send only reads back and never creates a provider copy; planned
     * siblings are withheld as unconfirmed. The readback is a new send, with its own bounded attempts.
     * A withdrawal still needs that readback: a found copy can then be retracted, while absence releases its fence.
     * A settled retraction counts too: it corrects only posted copies and never reads a started one back.
     * The caller holds the feedback row lock. The configuration audit trail records who asked for the readback,
     * and when, in that same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recheck(Feedback row, CheckedAuthority authority) {
        long workspaceId = row.getWorkspaceId();
        UUID feedbackId = row.getId();
        // A send that ended long ago may be gone (the outbox keeps ended actions for 30 days): the copies decide.
        var send = newest(workspaceId, feedbackId);
        int unknown = (int) copies.lockByFeedbackId(workspaceId, feedbackId).stream()
                .filter(c -> c.getState() == FeedbackCopyState.WRITE_STARTED
                        && c.getWriteStartedAt() != null
                        && c.getPostedAt() == null
                        && c.getExternalRef() == null
                        && c.getReadback() != FeedbackCopyReadback.ABSENT)
                .count();
        if (row.getDestination() != FeedbackDestination.ON_WORK
                || send.filter(s -> s.getState() != FeedbackSendState.DONE).isPresent()
                || unknown == 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No copy of this feedback has unknown visibility to recheck.");
        boolean withdrawn = visibility.withdrawn(workspaceId, feedbackId);
        var prior = send.map(FeedbackSend::getAction).orElse(FeedbackSendAction.POST);
        int attempts = send.map(FeedbackSend::getAttempts).orElse(0);
        enqueue(row, FeedbackSendAction.POST, authority);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.FEEDBACK_DELIVERY_RECHECK,
                feedbackId,
                workspaceId,
                new FeedbackDeliveryRecheckSnapshot(prior, FeedbackSendState.DONE, attempts, unknown, withdrawn),
                new FeedbackDeliveryRecheckSnapshot(
                        FeedbackSendAction.POST, FeedbackSendState.PENDING, 0, unknown, withdrawn)));
    }
}
