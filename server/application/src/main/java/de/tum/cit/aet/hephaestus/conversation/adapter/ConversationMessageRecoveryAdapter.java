package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredMessageDeletions;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Erasure recovery deletes a restored message again as the developer's delete did. */
@Component
class ConversationMessageRecoveryAdapter implements RestoredMessageDeletions {
    private final ConversationEntryService entries;

    ConversationMessageRecoveryAdapter(ConversationEntryService entries) {
        this.entries = entries;
    }

    /** A listed entry that is not the developer's own message stops the recovery: the manifest names it wrongly. */
    @Override
    @Transactional
    public boolean deleteAgain(long workspaceId, UUID entryId) {
        var entry = entries.find(workspaceId, entryId);
        if (entry.isEmpty() || entry.get().isRedacted()) return false;
        entries.deleteMessage(workspaceId, entryId);
        return true;
    }
}
