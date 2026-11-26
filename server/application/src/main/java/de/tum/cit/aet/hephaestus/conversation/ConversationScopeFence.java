package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Orders the handoff of live conversation data against a removal from its owner's history. */
@Component
@RequiredArgsConstructor
@OwnerScope.StoredOwnerOperation
public class ConversationScopeFence {
    private final ConversationRepository conversations;
    private final OwnerScope owners;

    public record Scope(long workspaceId, long accountId, long epoch) {}

    /**
     * Holds the shared history lock through the epoch check and one transport handoff.
     * Removal takes the same lock exclusively before it changes the epoch.
     */
    @Transactional(readOnly = true)
    public boolean admit(Scope scope, BooleanSupplier current, Runnable handoff) {
        owners.ofJob(scope.accountId());
        share(scope.workspaceId(), scope.accountId());
        if (conversations.readScopeEpoch(scope.workspaceId(), scope.accountId()).orElse(0L) != scope.epoch()
                || !current.getAsBoolean()) return false;
        handoff.run();
        return true;
    }

    /** For a gateway claim or write that already has a transaction. */
    public void share(long workspaceId, long accountId) {
        conversations.shareHistory(key(workspaceId, accountId));
    }

    void exclude(long workspaceId, long accountId) {
        conversations.lockScope(key(workspaceId, accountId));
    }

    private static String key(long workspaceId, long accountId) {
        return "conversation-history:" + workspaceId + ":" + accountId;
    }
}
