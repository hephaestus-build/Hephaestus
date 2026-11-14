package de.tum.cit.aet.hephaestus.conversation.spi;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Removes physical conversation copies before the frozen workspaces' person-data erasure steps acknowledge them. */
public interface ConversationSandboxCopies {
    /** Freezes physical copy identities with the selected workspace, before any source or runtime row is removed. */
    Map<Long, Set<UUID>> snapshot(Set<Long> workspaceIds);

    /** Removes frozen copies and any current copies in those workspaces, outside a content transaction. */
    void erase(Map<Long, Set<UUID>> frozenCopies);
}
