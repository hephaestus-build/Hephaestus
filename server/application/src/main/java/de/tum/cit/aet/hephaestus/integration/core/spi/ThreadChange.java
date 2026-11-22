package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What a provider did with an ask to resolve or reopen the thread of an owned note. */
public record ThreadChange(
        UUID copyId, Outcome outcome, @Nullable String reason) {
    public enum Outcome {
        /** The thread is in the asked state: changed now, or already so. */
        DONE,
        /** The thread or its note no longer exists. */
        GONE,
        /** The thread no longer starts with the owned note, or its author is not the writing account. */
        NOT_OWNED,
        /** A person replied after the owned note. Hephaestus does not resolve a conversation that a person continues. */
        REPLIED,
        /** Another account resolved the thread: a person owns its state now. */
        TAKEN,
        /** Silent Mode or a critical rate limit holds outbound writes: nothing was written. */
        DEFERRED,
        /** The provider did not answer or did not confirm; nothing proves the state. */
        TRANSIENT
    }

    public static ThreadChange done(UUID copyId) {
        return new ThreadChange(copyId, Outcome.DONE, null);
    }

    public static ThreadChange of(UUID copyId, Outcome outcome, String reason) {
        return new ThreadChange(copyId, outcome, reason);
    }
}
