package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Revokes sandbox credentials in the transaction that changes what a workspace's turns may read. */
@Component
public class ConversationSandboxRevocations {
    private final AgentSandboxRepository sandboxes;
    private final TransactionTemplate transactions;
    private final @Nullable AgentSandboxes owners;

    public ConversationSandboxRevocations(
            AgentSandboxRepository sandboxes,
            PlatformTransactionManager transactions,
            @Nullable AgentSandboxes owners) {
        this.sandboxes = sandboxes;
        this.transactions = new TransactionTemplate(transactions);
        this.owners = owners;
    }

    public Map<Long, Set<UUID>> snapshot(Set<Long> workspaceIds) {
        var copies = new HashMap<Long, Set<UUID>>();
        for (long workspace : workspaceIds)
            copies.put(
                    workspace,
                    sandboxes.findConversationCopies(workspace).stream()
                            .map(AgentSandboxRepository.SandboxRow::getId)
                            .collect(Collectors.toUnmodifiableSet()));
        return Map.copyOf(copies);
    }

    public void erase(Map<Long, Set<UUID>> frozenCopies) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Conversation copy erasure runs outside a content transaction");
        for (var workspace : frozenCopies.entrySet()) {
            var copies = new HashSet<>(workspace.getValue());
            sandboxes.findConversationCopies(workspace.getKey()).stream()
                    .map(AgentSandboxRepository.SandboxRow::getId)
                    .forEach(copies::add);
            if (copies.isEmpty()) continue;
            var owner = owners;
            if (owner == null) throw new IllegalStateException("Conversation sandbox deletion is unavailable");
            for (UUID copy : copies) owner.release(workspace.getKey(), copy);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void contextChanged(ConversationLedgerEvents.ContextChanged change) {
        transactions.executeWithoutResult(status -> sandboxes.revokeConversations(change.workspaceId()));
    }
}
