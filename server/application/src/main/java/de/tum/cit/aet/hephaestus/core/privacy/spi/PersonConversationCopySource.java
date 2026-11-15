package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.UUID;

/** Exact delivery references owned by the module that copied personal data into a Hephaestus conversation entry. */
public interface PersonConversationCopySource {
    List<ConversationCopy> conversationCopies(PersonScope person);

    record ConversationCopy(long workspaceId, UUID entryId) {}
}
