package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ConversationTurnAccessAdapter implements ConversationTurnAccess {
    private final ConversationTurnRepository turns;

    @Override
    public Optional<TurnState> find(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .map(turn -> new TurnState(
                        turn.getId(),
                        turn.getConversationId(),
                        turn.getStatus(),
                        turn.getSurface(),
                        turn.getCreatedAt()));
    }
}
