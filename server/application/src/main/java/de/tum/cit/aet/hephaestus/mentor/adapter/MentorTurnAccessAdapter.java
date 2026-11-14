package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MentorTurnAccessAdapter implements MentorTurnAccess {
    private final MentorTurnRepository turns;

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
