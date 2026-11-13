package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Transient identifying scope; completed audit receipts contain no subject identity or content. */
@Entity
@Table(name = "person_data_request")
@Getter
@Setter
public class PersonDataRequest {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "administrator_account_id")
    private @Nullable Long administratorAccountId;

    /** Changes only through {@link #startErasure}, {@link #fail} and {@link #complete}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Setter(AccessLevel.NONE)
    private State state = State.PREVIEW;

    @Column(name = "scope_json", columnDefinition = "text")
    private @Nullable String scopeJson;

    @Column(name = "selections_json", columnDefinition = "text")
    private @Nullable String selectionsJson;

    @Column(name = "counts_json", nullable = false, columnDefinition = "text")
    private String countsJson = "{}";

    @Column(name = "completed_json", nullable = false, columnDefinition = "text")
    private String completedJson = "{}";

    @Column(name = "failure_code", length = 48)
    private @Nullable String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plusSeconds(172800);

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Version
    private long version;

    /** Admits the erasure of a preview, or resumes a failed one. */
    public void startErasure(@Nullable Long administratorAccountId) {
        moveTo(State.ERASING);
        this.administratorAccountId = administratorAccountId;
        this.failureCode = null;
    }

    /** Stops the erasure until an administrator resumes it. */
    public void fail(String failureCode) {
        moveTo(State.FAILED);
        this.failureCode = failureCode;
    }

    /** Ends the erasure. The receipt keeps no identifying scope. */
    public void complete(Instant completedAt) {
        moveTo(State.COMPLETE);
        this.completedAt = completedAt;
        this.scopeJson = null;
        this.selectionsJson = null;
        this.failureCode = null;
    }

    private void moveTo(State next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("A person data request cannot move from " + state + " to " + next);
        }
        state = next;
    }

    /**
     * A preview expires by its {@code expiresAt}, not by a state. The trigger {@code guard_person_data_request}
     * keeps a completed request complete in the database too.
     */
    public enum State implements Lifecycle<State> {
        PREVIEW,
        ERASING,
        FAILED,
        COMPLETE;

        private static final Map<State, Set<State>> LEGAL = Map.of(
                PREVIEW, Set.of(ERASING),
                ERASING, Set.of(FAILED, COMPLETE),
                FAILED, Set.of(ERASING),
                COMPLETE, Set.of());

        @Override
        public boolean canTransitionTo(State next) {
            return Objects.requireNonNull(LEGAL.get(this)).contains(next);
        }
    }
}
