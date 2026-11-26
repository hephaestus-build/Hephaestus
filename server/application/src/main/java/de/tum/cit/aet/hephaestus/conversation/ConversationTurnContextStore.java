package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OutlinePersonCopyScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationTurnContextStore {
    private final ConversationTurnService turns;
    private final PersonDataCopyFence copies;

    public ConversationTurn require(long workspaceId, UUID turnId) {
        return turns.require(workspaceId, turnId);
    }

    /** Stores the source keys before the turn can read the context that they name. */
    @Transactional
    public void recordOutlineContext(
            long workspaceId, UUID turnId, int epoch, UUID runId, Collection<OutlinePersonCopyScope.Source> sources) {
        copies.holdForCapture();
        turns.held(workspaceId, turnId, epoch, runId).recordOutlineContext(sources);
    }
}
