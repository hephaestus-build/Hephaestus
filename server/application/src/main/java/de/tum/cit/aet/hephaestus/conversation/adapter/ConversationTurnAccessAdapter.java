package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationScopeFence.Scope;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
class ConversationTurnAccessAdapter implements ConversationTurnAccess {
    private final ConversationTurnRepository turns;
    private final ConversationTurnService service;

    @Override
    public Optional<TurnState> find(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .map(turn -> new TurnState(
                        turn.getId(),
                        turn.getConversationId(),
                        turn.getAttempt(),
                        turn.getStatus(),
                        turn.getSurface(),
                        turn.getCreatedAt(),
                        copy(turn.getAdmission())));
    }

    @Override
    public Optional<Long> findAccountId(long workspaceId, UUID turnId) {
        return turns.findAccountIdByIdAndWorkspaceId(turnId, workspaceId);
    }

    @Override
    public Optional<RunAttempt> runAttempt(long workspaceId, UUID runId, int epoch) {
        return turns.findRunAttempt(workspaceId, runId, epoch)
                .map(held -> new RunAttempt(
                        held.getId(),
                        held.getConversationId(),
                        held.getAccountId(),
                        ConversationTurn.Status.valueOf(held.getStatus()),
                        held.getStartedAt(),
                        held.getDeadline()));
    }

    @Override
    public Optional<Scope> runningScope(long workspaceId, UUID turnId, int epoch, UUID runId) {
        return turns.findRunningAttempt(workspaceId, turnId, epoch, runId)
                .map(attempt -> new Scope(workspaceId, attempt.getAccountId(), attempt.getScopeEpoch()));
    }

    @Override
    public boolean hasLiveLease(long workspaceId, UUID turnId, int epoch, UUID runId) {
        return turns.hasLiveLease(turnId, workspaceId, epoch, runId);
    }

    @Override
    public boolean countContextLoad(long workspaceId, UUID turnId, int epoch, UUID runId, int max) {
        return turns.countToolsLoad(turnId, workspaceId, epoch, runId, max) != 0;
    }

    @Override
    public long countQueuedQuestions() {
        return turns.countByStatusAndSurfaceNot(ConversationTurn.Status.QUEUED, ConversationTurn.Surface.SYSTEM);
    }

    @Override
    public void modelCallAdmitted(long workspaceId, UUID turnId) {
        service.modelCallAdmitted(workspaceId, turnId);
    }

    // A copy: the entity's own node would carry a consumer's change back into the ledger.
    private static @Nullable JsonNode copy(@Nullable JsonNode node) {
        return node == null ? null : node.deepCopy();
    }
}
