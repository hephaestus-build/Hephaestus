package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One try at answering a request version, leased on the database clock. A claim raises the lease epoch and draws a new
 * token, so the holder of an older claim fails every later check. The work deadline is fixed at creation: no reclaim
 * resets it.
 */
@Entity
@PersonOwned
@Table(
        name = "mentor_attempt",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_mentor_attempt_version",
                    columnNames = {"account_id", "request_id", "request_version"}),
            @UniqueConstraint(
                    name = "uk_mentor_attempt_owner",
                    columnNames = {"account_id", "id"})
        },
        indexes = @Index(name = "idx_mentor_attempt_due", columnList = "account_id, work_class, state, due_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorAttempt {
    /** How many claims an attempt gets: a lease that lapses this often ends the attempt. */
    public static final int MAX_CLAIMS = 3;

    /** The work budget of a question's attempt; the request's own deadline caps it. */
    public static final Duration INTERACTIVE_BUDGET = Duration.ofMinutes(10);

    @Id
    private UUID id;

    private long accountId;

    @Column(nullable = false)
    private UUID requestId;

    private int requestVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkClass workClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private @Nullable EndReason endReason;

    private int claims;

    private long leaseEpoch;

    private @Nullable UUID leaseToken;

    private @Nullable Instant leasedUntil;

    @Column(nullable = false)
    private Instant dueAt;

    @Column(nullable = false)
    private Instant deadline;

    /** The result's evidence basis, written by the commit. */
    private @Nullable UUID basisId;

    @Column(nullable = false)
    private Instant createdAt;

    private @Nullable Instant endedAt;

    /** Which capacity an attempt draws on. */
    public enum WorkClass {
        INTERACTIVE,
        CALLBACK,
        BACKGROUND
    }

    public enum State implements Lifecycle<State> {
        QUEUED,
        LEASED,
        ENDED;

        @Override
        public boolean canTransitionTo(State next) {
            return switch (this) {
                case QUEUED -> next != QUEUED;
                case LEASED -> next != QUEUED;
                case ENDED -> false;
            };
        }
    }

    public enum EndReason {
        ANSWERED,
        CONTINUED,
        FENCED,
        VERSION_STALE,
        REQUEST_EXPIRED,
        TIMED_OUT,
        ATTEMPTS_EXHAUSTED,
        AUTHORIZATION_CHANGED,
        BASIS_CHANGED,
        CONSENT_CHANGED,
        TEMPORARY,
        NOT_ADMITTED
    }
}
