package de.tum.cit.aet.hephaestus.conversation.spi;

import java.util.Optional;
import java.util.UUID;

public interface ConversationMessageQuery {
    Optional<String> textForMessage(long workspaceId, long accountId, UUID messageId);
}
