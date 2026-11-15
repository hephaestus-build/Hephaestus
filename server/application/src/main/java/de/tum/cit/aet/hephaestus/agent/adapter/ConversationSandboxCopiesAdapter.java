package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.sandbox.claim.ConversationSandboxRevocations;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationSandboxCopies;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Supplies conversation-copy erasure through the sandbox owner's lifecycle. */
@Component
public class ConversationSandboxCopiesAdapter implements ConversationSandboxCopies {
    private final ConversationSandboxRevocations revocations;

    public ConversationSandboxCopiesAdapter(ConversationSandboxRevocations revocations) {
        this.revocations = revocations;
    }

    @Override
    public Map<Long, Set<UUID>> snapshot(Set<Long> workspaceIds) {
        return revocations.snapshot(workspaceIds);
    }

    @Override
    public void erase(Map<Long, Set<UUID>> frozenCopies) {
        revocations.erase(frozenCopies);
    }
}
