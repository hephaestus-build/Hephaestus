package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads, as the person a claim is for, which answer of the turn showed each piece of feedback. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@OwnerScope.StoredOwnerOperation
public class ConversationReceiptAnswers {
    private final ConversationTurnService turns;
    private final OwnerScope owners;

    /**
     * The answer entry that showed each piece of feedback, by feedback id ({@link ConversationTurnService#answer}), or
     * empty when the turn cannot be read as {@code personAccountId}, the account that the claimed feedback stores.
     */
    @Transactional(readOnly = true)
    public Optional<Map<UUID, UUID>> shownIn(long workspaceId, long personAccountId, UUID turnId) {
        owners.ofJob(personAccountId);
        if (!turns.readable(workspaceId, turnId)) return Optional.empty();
        return Optional.of(turns.answer(workspaceId, turnId).shownIn());
    }
}
