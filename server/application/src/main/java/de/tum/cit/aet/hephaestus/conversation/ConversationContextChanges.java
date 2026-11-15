package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.AiChoiceChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells every server, through the ledger notifications, that what a Hephaestus turn of the workspace may read changed, so
 * that it revokes the workspace's conversation sandboxes. Each listener runs in the transaction of the change, so servers hear
 * of it once it commits. An AI-choice change tells every workspace of the account. A person erasure tells them from its own step ({@code ConversationToolResultCopyPersonDataStore}).
 */
@Component
public class ConversationContextChanges {

    private final ApplicationEventPublisher events;
    private final ConversationRepository conversations;

    public ConversationContextChanges(ApplicationEventPublisher events, ConversationRepository conversations) {
        this.events = events;
        this.conversations = conversations;
    }

    /** The choice is the account's, so every workspace it is a member of is told. */
    @EventListener
    public void aiChoiceChanged(AiChoiceChangedEvent change) {
        conversations.findMemberWorkspaceIds(change.accountId()).forEach(this::changed);
    }

    @EventListener
    public void visibilityChanged(AgentContextChangedEvent change) {
        changed(change.workspaceId());
    }

    @EventListener
    public void monitoringChanged(RepositoryMonitoringChangedEvent change) {
        changed(change.workspaceId());
    }

    @EventListener
    public void mirrorErased(ScmMirrorErasedEvent change) {
        changed(change.workspaceId());
    }

    public void changed(long workspaceId) {
        events.publishEvent(new ConversationLedgerEvents.ContextChanged(workspaceId));
    }
}
