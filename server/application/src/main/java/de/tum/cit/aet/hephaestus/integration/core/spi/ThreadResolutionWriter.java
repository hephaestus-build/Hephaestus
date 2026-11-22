package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

/** Resolves and reopens the provider thread that an owned note on the work starts. */
public interface ThreadResolutionWriter {
    IntegrationKind kind();

    /**
     * The provider account this writer acts as, by its provider id, such as the bot of a GitHub App. A thread event with
     * this actor can be Hephaestus's own. Null when the provider cannot say now.
     */
    @Nullable
    String actor(FeedbackTarget target);

    /**
     * Resolves or reopens the thread of one owned note, as {@code actor}, the account that {@link #actor} named. The
     * thread must still start with that note. A resolve needs the owned note to be the newest one: a person's later
     * reply refuses it. A thread that another account resolved is {@link ThreadChange.Outcome#TAKEN}: Hephaestus neither
     * reopens it nor counts it as its own. A thread already in the asked state otherwise is
     * {@link ThreadChange.Outcome#DONE}, so a repeated attempt is safe.
     */
    ThreadChange resolveThread(FeedbackTarget target, CopyReceipt copy, boolean resolved, String actor);

    /**
     * Whether Hephaestus may reopen a thread that it resolved here. Only when the provider can tell the thread event of
     * Hephaestus's own reopen from a person's: otherwise its own write would hand the thread to people.
     */
    default boolean reopensThreads() {
        return true;
    }
}
