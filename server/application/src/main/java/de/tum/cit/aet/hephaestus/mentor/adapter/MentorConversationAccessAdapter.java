package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorConversationAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MentorConversationAccessAdapter implements MentorConversationAccess {
    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;

    @Override
    public long accountId(long workspaceId, UUID conversationId) {
        return findAccountId(workspaceId, conversationId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
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
        return entries.findByWorkspaceIdAndTurnIdAndKind(workspaceId, turnId, MentorEntry.Kind.DIGEST)
                .map(MentorConversationAccessAdapter::snapshot);
    }

    @Override
    public boolean answered(long workspaceId, UUID turnId) {
        return entries
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, MentorEntry.Kind.HEPH)
                .stream()
                .anyMatch(entry -> !entry.isRedacted());
    }

    private static LedgerEntry snapshot(MentorEntry entry) {
        // A copy: the entity's own node would carry a consumer's change back into the ledger.
        var payload = entry.getPayload();
        return new LedgerEntry(
                entry.getId(), entry.getKind(), entry.isRedacted(), payload == null ? null : payload.deepCopy());
    }
}
