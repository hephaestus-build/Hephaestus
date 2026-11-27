package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import de.tum.cit.aet.hephaestus.mentor.spi.Transport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What a person asked, at most one per source. An edit of the question raises the version. The audience and the scope
 * stamp are fixed at admission: a later attempt rechecks the stamp, it never captures a new one.
 */
@Entity
@PersonOwned
@Table(
        name = "mentor_request",
        uniqueConstraints = {
            // One request per source; a source has one account, so the pair is unique where the source is.
            @UniqueConstraint(name = "uk_mentor_request_source", columnNames = {"account_id", "source_id"}),
            @UniqueConstraint(name = "uk_mentor_request_owner", columnNames = {"account_id", "id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorRequest {
    @Id
    private UUID id;

    private long accountId;

    @Column(nullable = false)
    private UUID sourceId;

    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Transport audienceTransport;

    private @Nullable Long audienceConnectionId;

    @Column(nullable = false)
    private String audienceContainerId;

    private @Nullable String audienceThreadRoot;

    private long scopeAccountGeneration;

    private long scopeReaderEpoch;

    private long scopeConsentEpoch;

    /** Which {@code AuthorityScope} the stamp holds; the scope columns below are set for it alone. */
    @Column(nullable = false, length = 16)
    private String scopeAuthority;

    private @Nullable Long scopeWorkspaceId;

    private @Nullable Long scopeWorkspaceGeneration;

    private @Nullable Long scopeBindingId;

    private @Nullable Long scopeBindingGeneration;

    private @Nullable String scopeNativeActorId;

    @Column(nullable = false)
    private Instant openedAt;

    @Column(nullable = false)
    private Instant deadline;

    public enum State implements Lifecycle<State> {
        OPEN,
        RUNNING,
        ANSWERED,
        CONTINUED,
        FAILED,
        STOPPED,
        SUPERSEDED,
        EXPIRED;

        /** Whether the request has ended; an edit of a settled request changes nothing. */
        public boolean settled() {
            return this != OPEN && this != RUNNING;
        }

        @Override
        public boolean canTransitionTo(State next) {
            return switch (this) {
                case OPEN -> next != OPEN && next != ANSWERED && next != CONTINUED && next != FAILED;
                case RUNNING -> next.settled();
                default -> false;
            };
        }
    }
}
