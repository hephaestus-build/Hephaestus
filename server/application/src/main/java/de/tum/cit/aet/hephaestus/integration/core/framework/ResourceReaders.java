package de.tum.cit.aet.hephaestus.integration.core.framework;

import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofState;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * Who on the provider can see a monitored resource, as far as Hephaestus has proven it, and how far the proof got.
 *
 * <p>An answer that narrows who can see the resource (not public, gone from the provider, a different resource behind
 * the same monitor, a stopped listing) always applies. An answer that widens it (public, a complete listing of readers)
 * applies only if no other answer applied since its provider read started: the read captures
 * {@link #getProofGeneration()} first, and every applied answer moves the generation on. So a slow or overlapping read
 * never undoes a newer answer, whatever the replicas' clocks say. The caller holds the resource's row lock while it
 * applies an answer.
 *
 * <p>Readers read the class only through {@link #trustedClass(Instant, Duration)}, so a proof that no provider answer
 * renewed for too long stops counting.
 */
@Embeddable
@Getter
@ToString
public class ResourceReaders {

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_class", nullable = false, length = 16)
    @ColumnDefault("'UNPROVEN'")
    private VisibilityClass visibilityClass = VisibilityClass.UNPROVEN;

    /** When the provider read that last proved this resource's readers was requested. */
    @Column(name = "readers_synced_at")
    private @Nullable Instant readersSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "proof_state", nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private ProofState proofState = ProofState.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "proof_reason", nullable = false, length = 32)
    @ColumnDefault("'AWAITING_READERS'")
    private ProofReason proofReason = ProofReason.AWAITING_READERS;

    /** Moves on with every applied answer, so a widening answer can tell whether anything applied since its read. */
    @Column(name = "proof_generation", nullable = false)
    @ColumnDefault("0")
    private long proofGeneration;

    /**
     * When the class or the reason last changed. Operators read how long a proof has waited from it; no decision reads
     * it, so it takes the applying replica's clock.
     */
    @Column(name = "proof_changed_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant proofChangedAt = Instant.now();

    /**
     * The class to act on at {@code now}: the stored class while its proof is current, otherwise
     * {@link VisibilityClass#UNPROVEN}.
     */
    public VisibilityClass trustedClass(Instant now, Duration maxAge) {
        // A stamp from a replica whose clock runs ahead counts once it is no longer in the future.
        boolean current = proofState == ProofState.PROVEN
                && readersSyncedAt != null
                && readersSyncedAt.isAfter(now.minus(maxAge))
                && !readersSyncedAt.isAfter(now);
        return current ? visibilityClass : VisibilityClass.UNPROVEN;
    }

    /**
     * The provider confirmed, in a read that started at {@code generation} and was requested at {@code requestedAt},
     * that everything Hephaestus reads in the resource is public. The proof's age counts from the request.
     *
     * @return whether the answer applied; it does not if another answer applied since the read started
     */
    public boolean confirmedPublic(long generation, Instant requestedAt) {
        return widen(generation, requestedAt, VisibilityClass.WORKSPACE, ProofReason.PUBLIC_ON_PROVIDER);
    }

    /**
     * The provider listed, in a read that started at {@code generation} and was requested at {@code requestedAt}, every
     * reader of the resource. The caller stores that listing in the same transaction, and only if this returns true.
     *
     * @return whether the answer applied; it does not if another answer applied since the read started
     */
    public boolean readersListed(long generation, Instant requestedAt) {
        return widen(generation, requestedAt, VisibilityClass.RESTRICTED, ProofReason.READERS_LISTED);
    }

    /**
     * The provider answered without proving the resource public. A class from a public confirmation no longer holds,
     * and a resource the provider stopped showing waits for its readers again. A listing of readers does not depend on
     * the public answer, so it keeps its class and reason. The generation moves on in every case, so a public answer
     * from a read that overlapped this one does not apply.
     */
    public void confirmedNotPublic() {
        boolean listingStands = visibilityClass == VisibilityClass.RESTRICTED
                || (visibilityClass == VisibilityClass.UNPROVEN && proofReason != ProofReason.RESOURCE_UNAVAILABLE);
        if (listingStands) {
            proofGeneration++;
            return;
        }
        move(VisibilityClass.UNPROVEN, ProofReason.AWAITING_READERS);
    }

    /**
     * The listing of readers stopped before its end ({@link ProofReason#RATE_LIMITED} or
     * {@link ProofReason#LISTING_INCOMPLETE}). Nothing it read counts, and an earlier listing stops counting too: the
     * class stays, so operators see which proof waits, but the proof is pending until a complete listing.
     */
    public void listingStopped(ProofReason reason) {
        if (reason != ProofReason.RATE_LIMITED && reason != ProofReason.LISTING_INCOMPLETE) {
            throw new IllegalArgumentException("A stopped listing waits for a rate limit or a full answer: " + reason);
        }
        move(
                visibilityClass == VisibilityClass.RESTRICTED ? VisibilityClass.RESTRICTED : VisibilityClass.UNPROVEN,
                reason);
    }

    /** The provider cannot list every reader of the resource, so its readers are never proven while that holds. */
    public void readersNotListable() {
        move(VisibilityClass.UNPROVEN, ProofReason.READERS_NOT_LISTABLE);
    }

    /** The provider no longer shows the resource to this workspace, so nobody's access to it is proven. */
    public void unavailable() {
        move(VisibilityClass.UNPROVEN, ProofReason.RESOURCE_UNAVAILABLE);
    }

    /** The monitor now names another resource on the provider, so the old resource's readers prove nothing. */
    public void identityChanged() {
        move(VisibilityClass.UNPROVEN, ProofReason.AWAITING_READERS);
    }

    private boolean widen(long generation, Instant requestedAt, VisibilityClass nextClass, ProofReason reason) {
        if (generation != proofGeneration) {
            return false;
        }
        move(nextClass, reason);
        readersSyncedAt = requestedAt;
        return true;
    }

    private void move(VisibilityClass nextClass, ProofReason reason) {
        if (nextClass != visibilityClass || reason != proofReason) {
            proofChangedAt = Instant.now();
        }
        visibilityClass = nextClass;
        proofReason = reason;
        proofState = reason.state();
        proofGeneration++;
    }
}
