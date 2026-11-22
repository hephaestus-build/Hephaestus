package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;

/** The account whose conversation a request reads or changes. */
final class CurrentConversationAccount {

    private CurrentConversationAccount() {}

    /** The signed-in account, or the viewed account of a read-only user view. */
    static long of(CurrentDeveloperLookup developer) {
        return developer.currentDeveloperId().orElseThrow(() -> new EntityNotFoundException("Account", "current"));
    }
}
