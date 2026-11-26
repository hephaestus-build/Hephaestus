package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationHistoryRemoval;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ConversationAccessAdapter implements ConversationAccess {
    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;

    @Override
    public void shareHistory(long workspaceId, long accountId) {
        conversations.shareHistory(ConversationHistoryRemoval.lockKey(workspaceId, accountId));
    }

    @Override
    public List<UUID> treeIds(long workspaceId, long accountId) {
        return conversations.findTreeIds(workspaceId, accountId);
    }

    @Override
    public long accountId(long workspaceId, UUID conversationId) {
        return findAccountId(workspaceId, conversationId)
                .orElseThrow(() -> new EntityNotFoundException("Conversation", conversationId.toString()));
    }

    @Override
    public Optional<Long> findAccountId(long workspaceId, UUID conversationId) {
        return conversations.findAccountIdByIdAndWorkspaceId(conversationId, workspaceId);
    }

    @Override
    public LedgerEntry stimulus(long workspaceId, UUID turnId) {
        return snapshot(entries.findStimulus(workspaceId, turnId));
    }

    @Override
    public Optional<LedgerEntry> digest(long workspaceId, UUID turnId) {
        return entries.findByWorkspaceIdAndTurnIdAndKind(workspaceId, turnId, ConversationEntry.Kind.DIGEST)
                .map(ConversationAccessAdapter::snapshot);
    }

    @Override
    public boolean answered(long workspaceId, UUID turnId) {
        return entries
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, ConversationEntry.Kind.ANSWER)
                .stream()
                .anyMatch(entry -> !entry.isRedacted());
    }

    private static LedgerEntry snapshot(ConversationEntry entry) {
        // A copy: the entity's own node would carry a consumer's change back into the ledger.
        var payload = entry.getPayload();
        return new LedgerEntry(
                entry.getId(), entry.getKind(), entry.isRedacted(), payload == null ? null : payload.deepCopy());
    }
}
