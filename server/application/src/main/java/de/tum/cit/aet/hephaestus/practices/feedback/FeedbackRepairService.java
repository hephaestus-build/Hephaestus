package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackEditBudgetSpentException;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Binds each quiet edit to the immutable receipts of this item's owned copies. */
@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@Transactional
public class FeedbackRepairService implements FeedbackRepair {
    private static final int MAX_EDITS = 3;
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackPreparationStore preparation;
    private final FeedbackVisibilityService visibility;
    private final FeedbackSendQueue queue;
    private final ObjectMapper mapper;
    private final FeedbackRepairApproval approvals;
    private final FeedbackDeliveryPolicy policy;
    private final ReviewRepository reviews;
    private final ResultRepository results;

    @Override
    public FeedbackRepairStatus readback(long workspaceId, UUID reviewId, UUID targetId) {
        return readback(workspaceId, reviewId, targetId, true);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackRepairStatus readbackStatus(long workspaceId, UUID reviewId, UUID targetId) {
        return readback(workspaceId, reviewId, targetId, false);
    }

    private FeedbackRepairStatus readback(long workspaceId, UUID reviewId, UUID targetId, boolean request) {
        var target = (request
                        ? feedback.lockByIdAndWorkspaceId(targetId, workspaceId)
                        : feedback.findByIdAndWorkspaceId(targetId, workspaceId))
                .orElseThrow();
        if (target.getDestination() != FeedbackDestination.ON_WORK || target.getDeliveredAt() == null)
            throw refused("Only a posted on-work copy can be read for a quiet edit.");
        var review = reviews.findByIdAndWorkspaceId(reviewId, workspaceId).orElseThrow();
        var admitted = results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(workspaceId, reviewId);
        if (!Objects.equals(target.getItemId(), review.getItemId())
                || admitted.isEmpty()
                || admitted.stream()
                        .anyMatch(result -> !Objects.equals(result.getDeveloperId(), target.getDeveloperId())))
            throw refused("Only this composition's own feedback on this work can be read.");
        var state = target.getComposition().path("readback");
        List<CopyReadback> observed = state.isArray()
                ? mapper.convertValue(
                        state, mapper.getTypeFactory().constructCollectionType(List.class, CopyReadback.class))
                : List.of();
        if (!observed.isEmpty()
                && (!request || observed.stream().noneMatch(copy -> copy.status() == CopyReadback.Status.UNAVAILABLE)))
            return new FeedbackRepairStatus(targetId, FeedbackRepairStatus.State.READBACK, 0, observed);
        if (request) {
            var send = queue.current(workspaceId, targetId);
            if (send.isEmpty() || send.get().getState() == FeedbackSendState.DONE) {
                var next = (ObjectNode) target.getComposition().deepCopy();
                next.remove("readback");
                target.setComposition(next);
                feedback.saveAndFlush(target);
                queue.queue(target, FeedbackSendAction.READBACK, FeedbackSendState.PENDING);
            }
        }
        return new FeedbackRepairStatus(targetId, FeedbackRepairStatus.State.WAITING, 0, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackRepairStatus status(long workspaceId, UUID reviewId) {
        return readStatus(workspaceId, reviewId);
    }

    private FeedbackRepairStatus readStatus(long workspaceId, UUID reviewId) {
        var rows = feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(workspaceId, reviewId).stream()
                .filter(row -> row.getDestination() == FeedbackDestination.ON_WORK)
                .toList();
        if (rows.isEmpty()) return new FeedbackRepairStatus(null, FeedbackRepairStatus.State.WAITING, 0, List.of());
        var root = rows.stream()
                .filter(piece -> !piece.getComposition().hasNonNull("editOf")
                        || piece.getComposition()
                                .path("root")
                                .asString("")
                                .equals(piece.getId().toString()))
                .findFirst()
                .orElse(rows.getFirst());
        String latest =
                root.getComposition().path("latest").asString(root.getId().toString());
        var row = rows.stream()
                .filter(piece -> piece.getId().toString().equals(latest))
                .findFirst()
                .orElse(root);
        int edits = rows.stream()
                .mapToInt(piece -> piece.getComposition().path("edits").asInt(0))
                .max()
                .orElse(0);
        var state = row.getComposition();
        var observed = state.path("readback");
        List<CopyReadback> readback = observed.isArray()
                ? mapper.convertValue(
                        observed, mapper.getTypeFactory().constructCollectionType(List.class, CopyReadback.class))
                : List.of();
        var stage = row.getState() == FeedbackState.AWAITING_APPROVAL
                ? FeedbackRepairStatus.State.AWAITING_APPROVAL
                : state.hasNonNull("stop")
                                || row.getState() == FeedbackState.WITHHELD
                                || row.getState() == FeedbackState.REJECTED
                        ? FeedbackRepairStatus.State.STOPPED
                        : readback.isEmpty() ? FeedbackRepairStatus.State.WAITING : FeedbackRepairStatus.State.READBACK;
        return new FeedbackRepairStatus(row.getId(), stage, edits, readback);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackDraft check(FeedbackDraft proposal, UUID targetId, String draftId) {
        var checked = revision(proposal, targetId, draftId);
        preparation.checkRepair(proposal.workspaceId(), proposal.reviewId(), checked);
        return checked;
    }

    @Override
    public PreparedPackage prepare(FeedbackDraft proposal, UUID targetId, String draftId) {
        UUID rootId = FeedbackIds.onWork(proposal.workspaceId(), proposal.reviewId(), proposal.developerId());
        feedback.lockAllByIdInAndWorkspaceId(
                List.of(rootId, targetId, FeedbackIds.correction(rootId, draftId)).stream()
                        .distinct()
                        .toList(),
                proposal.workspaceId());
        var draft = revision(proposal, targetId, draftId);
        var existing = feedback.findByIdAndWorkspaceId(draft.id(), draft.workspaceId());
        if (existing.isPresent()) return preparation.prepareRepair(draft.workspaceId(), draft.reviewId(), draft);
        var root = feedback.findByIdAndWorkspaceId(rootId, draft.workspaceId());
        int edits = root.map(row -> row.getComposition().path("edits").asInt(0)).orElse(0);
        var receipt = preparation.prepareRepair(draft.workspaceId(), draft.reviewId(), draft);
        var row =
                feedback.lockByIdAndWorkspaceId(draft.id(), draft.workspaceId()).orElseThrow();
        var state = (ObjectNode) row.getComposition().deepCopy();
        state.put("root", rootId.toString()).put("editOf", targetId.toString()).put("draftId", draftId);
        state.put("edits", edits + 1).put("latest", draft.id().toString());
        var previous =
                feedback.findByIdAndWorkspaceId(targetId, draft.workspaceId()).orElseThrow();
        if (approvals.sameWords(previous, row)) {
            String approvedId = previous.getComposition().path("approvalFrom").asString(targetId.toString());
            state.put("approvalFrom", approvedId);
        }
        row.setComposition(state);
        feedback.saveAndFlush(row);
        feedback.recordReplacement(draft.workspaceId(), draft.id(), targetId);
        if (root.isPresent()) {
            var rootState = (ObjectNode) root.get().getComposition().deepCopy();
            rootState.put("edits", edits + 1).put("latest", draft.id().toString());
            feedback.updateComposition(draft.workspaceId(), rootId, rootState);
        }
        policy.prepared(row);
        if (row.getState() == FeedbackState.READY) queue.queue(row, FeedbackSendAction.EDIT, FeedbackSendState.PENDING);
        return receipt;
    }

    private FeedbackDraft revision(FeedbackDraft proposal, UUID targetId, String draftId) {
        if (proposal.destination() != FeedbackDestination.ON_WORK
                || proposal.state() != FeedbackState.READY
                || proposal.itemId() == null
                || proposal.copies().isEmpty())
            throw refused("A quiet edit needs a checked on-work package with its current result basis.");
        var target = feedback.findByIdAndWorkspaceId(targetId, proposal.workspaceId())
                .orElseThrow();
        if (target.getDestination() != FeedbackDestination.ON_WORK
                || !Objects.equals(target.getItemId(), proposal.itemId())
                || target.getDeveloperId() != proposal.developerId()
                || visibility.withdrawn(proposal.workspaceId(), targetId)
                || target.getComposition().path("humanControlled").asBoolean())
            throw refused("The listed feedback is not an editable copy on this work.");
        UUID rootId = FeedbackIds.onWork(proposal.workspaceId(), proposal.reviewId(), proposal.developerId());
        var root = feedback.findByIdAndWorkspaceId(rootId, proposal.workspaceId());
        UUID revisionId = root.isEmpty()
                        || root.get()
                                .getComposition()
                                .path("draftId")
                                .asString("")
                                .equals(draftId)
                ? rootId
                : FeedbackIds.correction(rootId, draftId);
        boolean replay = feedback.findByIdAndWorkspaceId(revisionId, proposal.workspaceId())
                .isPresent();
        if (!replay
                && root.map(row -> row.getComposition().path("edits").asInt(0)).orElse(0) >= MAX_EDITS)
            throw new FeedbackEditBudgetSpentException();
        if (!replay && root.isPresent()) {
            var current = readStatus(proposal.workspaceId(), proposal.reviewId());
            if (!targetId.equals(current.feedbackId()) || current.state() != FeedbackRepairStatus.State.READBACK)
                throw refused(
                        "The accepted edit must settle first. Correct only this composition's latest owned copies.");
        }
        var owned = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(proposal.workspaceId(), targetId);
        var observed = target.getComposition().path("readback");
        if (!replay && (!observed.isArray() || observed.size() != owned.size()))
            throw refused("Provider readback is unavailable. Read back this copy before editing it.");
        for (var read : replay ? List.<JsonNode>of() : observed) {
            var fact = mapper.treeToValue(read, CopyReadback.class);
            if (fact.status() != CopyReadback.Status.FOUND || fact.resolved() || fact.outdated())
                throw refused(
                        "A person's state or unavailable provider proof prevents this edit. Leave the copy as it is.");
        }
        if (owned.size() != proposal.copies().size())
            throw refused("A quiet edit cannot add or remove provider comments. Correct the existing entries only.");
        var revised = proposal.copies().stream()
                .map(copy -> {
                    var prior = owned.stream()
                            .filter(old -> old.getPosition() == copy.position() && old.getKind() == copy.kind())
                            .findFirst()
                            .orElseThrow(() -> refused("A quiet edit must retain each existing comment's form."));
                    if (!replay
                            && (prior.getState() != FeedbackCopyState.POSTED
                                    || prior.getExternalRef() == null
                                    || copies.hasNewerSummaryReceipt(
                                            proposal.workspaceId(), targetId, prior.getExternalRef())
                                    || !Objects.equals(prior.getPath(), copy.path())
                                    || !Objects.equals(prior.getStartLine(), copy.startLine())
                                    || !Objects.equals(prior.getEndLine(), copy.endLine())
                                    || prior.getSide() != copy.side()))
                        throw refused(
                                "A quiet edit cannot move this provider comment. Keep its current position or stop and report.");
                    UUID id = FeedbackIds.copy(revisionId, copy.kind(), copy.position());
                    return new FeedbackCopyInput(
                            id,
                            copy.kind(),
                            copy.position(),
                            copy.body(),
                            copy.path(),
                            copy.startLine(),
                            copy.endLine(),
                            copy.side(),
                            id.toString(),
                            FeedbackIds.writeMarker(id));
                })
                .toList();
        return new FeedbackDraft(
                revisionId,
                proposal.workspaceId(),
                proposal.reviewId(),
                proposal.itemId(),
                proposal.developerId(),
                proposal.destination(),
                proposal.state(),
                proposal.stopReason(),
                proposal.body(),
                proposal.basis(),
                revised,
                proposal.checks(),
                proposal.composition(),
                proposal.page());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean pending(long workspaceId, UUID reviewId) {
        var state = readStatus(workspaceId, reviewId);
        return state.feedbackId() != null && state.state() != FeedbackRepairStatus.State.STOPPED;
    }

    @Override
    public void stop(long workspaceId, UUID reviewId, String reason) {
        var ids = feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(workspaceId, reviewId).stream()
                .filter(row -> row.getDestination() == FeedbackDestination.ON_WORK)
                .map(Feedback::getId)
                .toList();
        if (ids.isEmpty()) return;
        for (var locked : feedback.lockAllByIdInAndWorkspaceId(ids, workspaceId)) {
            var state = (ObjectNode) locked.getComposition().deepCopy();
            state.put("stop", reason);
            feedback.updateComposition(workspaceId, locked.getId(), state);
        }
    }

    private static IllegalArgumentException refused(String reason) {
        return new IllegalArgumentException(reason);
    }
}
