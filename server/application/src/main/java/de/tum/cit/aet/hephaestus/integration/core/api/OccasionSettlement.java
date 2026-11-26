package de.tum.cit.aet.hephaestus.integration.core.api;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every transition of an open item event occasion: its state, its role in the window and what happened give its next
 * step. A decided occasion never changes. Each writer of an occasion's state applies this table: the review's own
 * transaction, the listener under the workspace lock, the ledger's pin, and the reaper, whose bulk update the tests
 * hold to the {@link Outcome#EXPIRED} cell.
 *
 * <p>An older occasion coalesces only into a review that committed and used it. Otherwise it ends withheld with its
 * own reason, or it stays open and the ledger offers it again.
 */
public final class OccasionSettlement {
    private OccasionSettlement() {}

    /** The occasion's place in the window that a request names by its newest occasion. */
    public enum Role {
        NEWEST,
        OLDER
    }

    /** What happened to the occasion's window. */
    public enum Outcome {
        /** The window's review committed, with every older occasion still open: they are part of its selection. */
        RECORDED,
        /** The newest occasion's review was already recorded, by another offer or before an upgrade. */
        REPLAYED,
        /** The quiet window is still open. */
        QUIET,
        /** A refusal that can pass. */
        WAIT,
        /** An earlier review of the item still measures a practice that the window selects. */
        HOLD,
        /** A refusal of the whole window, such as no active practice. */
        REFUSED,
        /** A refusal of the newest occasion only, such as a version that no longer matches it. */
        REFUSED_NEWEST,
        /** The newest occasion's own retained record cannot be pinned: the older occasions keep theirs. */
        OCCASION_UNAVAILABLE,
        /** The source confirmed that the item was removed, or the workspace can no longer see it. */
        ITEM_UNAVAILABLE,
        /** A capture fault: the publication fails and is sent again. */
        FAULT,
        /** The item left the workspace index before the window was read. */
        WITHDRAWN,
        /** The pinned version is one that an occasion of the same name already holds. */
        DUPLICATE,
        /** The occasion passed its deadline while still open. */
        EXPIRED
    }

    /** What to do with an occasion. */
    public sealed interface Next {
        record Decide(ItemEventState state, @Nullable ReviewRequestReason reason) implements Next {}

        record Hold(ReviewRequestReason reason) implements Next {}
    }

    /**
     * The next step of an occasion in {@code state} with {@code role}, after {@code outcome}; none when it stays as it
     * is. {@code reason} is the refusal's or the item's reason, for the outcomes that carry one.
     */
    public static Optional<Next> next(
            ItemEventState state, Role role, Outcome outcome, @Nullable ReviewRequestReason reason) {
        if (state.isTerminal()) return Optional.empty();
        boolean newest = role == Role.NEWEST;
        return switch (outcome) {
            case RECORDED ->
                Optional.of(
                        newest
                                ? new Next.Decide(ItemEventState.STARTED, null)
                                : new Next.Decide(ItemEventState.WITHHELD, ReviewRequestReason.COALESCED));
            // The review's own occasion starts from its stored link. An older one never joined it.
            case REPLAYED -> newest ? Optional.of(new Next.Decide(ItemEventState.STARTED, null)) : Optional.empty();
            case QUIET -> Optional.of(new Next.Hold(ReviewRequestReason.QUIET_WINDOW));
            case WAIT -> Optional.of(new Next.Decide(ItemEventState.WAITING, Objects.requireNonNull(reason)));
            case HOLD -> Optional.of(new Next.Hold(ReviewRequestReason.REVIEW_IN_PROGRESS));
            case REFUSED, ITEM_UNAVAILABLE ->
                Optional.of(new Next.Decide(ItemEventState.WITHHELD, Objects.requireNonNull(reason)));
            case REFUSED_NEWEST ->
                newest
                        ? Optional.of(new Next.Decide(ItemEventState.WITHHELD, Objects.requireNonNull(reason)))
                        : Optional.empty();
            case OCCASION_UNAVAILABLE ->
                newest
                        ? Optional.of(new Next.Decide(
                                ItemEventState.WITHHELD, ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE))
                        : Optional.empty();
            case FAULT -> Optional.empty();
            // Index absence does not prove that the work is gone: a workspace that withdraws a repository removes it
            // too.
            case WITHDRAWN ->
                newest
                        ? Optional.of(new Next.Decide(ItemEventState.WITHHELD, ReviewRequestReason.ITEM_NOT_VISIBLE))
                        : Optional.empty();
            case DUPLICATE ->
                Optional.of(new Next.Decide(ItemEventState.WITHHELD, ReviewRequestReason.DUPLICATE_VERSION));
            case EXPIRED ->
                Optional.of(new Next.Decide(ItemEventState.EXPIRED, ReviewRequestReason.PENDING_DEADLINE_EXCEEDED));
        };
    }
}
