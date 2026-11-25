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
 * the same monitor) always applies. An answer that widens it (public, a complete listing of readers) applies only if no
 * other answer applied since its provider read started: the read captures {@link #getProofGeneration()} first, and every
 * applied answer moves the generation on. So a slow or overlapping read never undoes a newer answer, whatever the
 * replicas' clocks say. The caller holds the resource's row lock while it applies an answer.
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
        if (generation != proofGeneration) {
            return false;
        }
        move(VisibilityClass.WORKSPACE, ProofReason.PUBLIC_ON_PROVIDER);
        readersSyncedAt = requestedAt;
        return true;
    }

    /** The provider answered without proving the resource public, so a class from a public confirmation no longer holds. */
    public void confirmedNotPublic() {
        move(VisibilityClass.UNPROVEN, ProofReason.AWAITING_READERS);
    }

    /** The provider no longer shows the resource to this workspace, so nobody's access to it is proven. */
    public void unavailable() {
        move(VisibilityClass.UNPROVEN, ProofReason.RESOURCE_UNAVAILABLE);
    }

    /** The monitor now names another resource on the provider, so the old resource's readers prove nothing. */
    public void identityChanged() {
        move(VisibilityClass.UNPROVEN, ProofReason.AWAITING_READERS);
    }

    private void move(VisibilityClass nextClass, ProofReason reason) {
        visibilityClass = nextClass;
        proofReason = reason;
        proofState = reason.state();
        proofGeneration++;
    }
}
