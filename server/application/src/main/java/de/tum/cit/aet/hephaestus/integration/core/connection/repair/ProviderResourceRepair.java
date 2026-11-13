package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Pseudonymous security identity; deliberately has no workspace, person, credential or callback route. */
@Entity
@Table(
        name = "provider_resource_repair",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_provider_resource_repair_identity", columnNames = "identity_hash"))
public class ProviderResourceRepair {
    public enum State {
        REGISTERING,
        PENDING,
        CHECKING,
        DISPATCHING,
        AWAITING_CONFIRMATION,
        NEEDS_AUTHORIZATION,
        CONFIRMATION_REQUIRED
    }

    @Id
    private UUID id;

    @Column(name = "identity_hash", nullable = false, length = 64, updatable = false)
    private String identityHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48, updatable = false)
    private IntegrationKind kind;

    @Column(nullable = false, length = 2048, updatable = false)
    private String origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32, updatable = false)
    private ResourceType resourceType;

    @Column(name = "parent_id", nullable = false, length = 256, updatable = false)
    private String parentId;

    @Column(name = "resource_id", nullable = false, length = 256, updatable = false)
    private String resourceId;

    @Column(name = "ownership_hash", length = 96, updatable = false)
    private @Nullable String ownershipHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private State state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_epoch", nullable = false)
    private long attemptEpoch;

    @Column(name = "removal_attempts", nullable = false)
    private long removalAttempts;

    @Version
    private long version;

    protected ProviderResourceRepair() {}

    public ProviderResourceRepair(Resource resource, Instant now) {
        this.id = UUID.randomUUID();
        this.identityHash = resource.identity();
        this.kind = resource.kind();
        this.origin = resource.origin();
        this.resourceType = resource.type();
        this.parentId = resource.parentId();
        this.resourceId = resource.resourceId();
        this.ownershipHash = resource.ownershipHash();
        this.state = resource.type() == ResourceType.REGISTRATION ? State.REGISTERING : State.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
        this.nextAttemptAt = resource.type() == ResourceType.REGISTRATION ? now.plusSeconds(600) : now;
    }

    /** A pre-cutover call could already have reached the provider without a durable dispatch permit. */
    public static ProviderResourceRepair untrackedRemoval(Resource resource, Instant now) {
        var repair = new ProviderResourceRepair(resource, now);
        repair.removalAttempts = 1;
        repair.state = State.AWAITING_CONFIRMATION;
        return repair;
    }

    public void transition(State next, Instant now) {
        boolean allowed = state == next
                || switch (state) {
                    case REGISTERING, CONFIRMATION_REQUIRED -> false;
                    case PENDING, AWAITING_CONFIRMATION, NEEDS_AUTHORIZATION -> next == State.CHECKING;
                    case DISPATCHING ->
                        next == State.CHECKING
                                || next == State.AWAITING_CONFIRMATION
                                || next == State.NEEDS_AUTHORIZATION
                                || next == State.CONFIRMATION_REQUIRED;
                    case CHECKING ->
                        next == State.DISPATCHING
                                || next == State.AWAITING_CONFIRMATION
                                || next == State.NEEDS_AUTHORIZATION
                                || next == State.CONFIRMATION_REQUIRED;
                };
        if (!allowed) throw new IllegalStateException("Illegal provider repair transition: " + state + " -> " + next);
        if (next == State.CHECKING) attemptEpoch++;
        if (next == State.DISPATCHING) removalAttempts++;
        state = next;
        updatedAt = now;
        nextAttemptAt = now.plusSeconds((next == State.DISPATCHING || next == State.CHECKING) ? 600 : 60);
    }

    public UUID getId() {
        return id;
    }

    public String getIdentityHash() {
        return identityHash;
    }

    public IntegrationKind getKind() {
        return kind;
    }

    public State getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getAttemptEpoch() {
        return attemptEpoch;
    }

    public long getRemovalAttempts() {
        return removalAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Resource resource() {
        return new Resource(kind, origin, resourceType, parentId, resourceId, ownershipHash);
    }
}
