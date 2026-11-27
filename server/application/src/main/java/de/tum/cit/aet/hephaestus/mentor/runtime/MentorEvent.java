package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import de.tum.cit.aet.hephaestus.mentor.spi.IngressKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One admitted mailbox event, with what intake did with it. A provider event is kept once per binding and provider
 * event id, and names its source. A sealed review's event is kept once per review, and names the review, its job and
 * its admission instead. A CHECK keeps each kind to its own columns.
 */
@Entity
@PersonOwned
@Table(
        name = "mentor_event",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_mentor_event_key",
                    columnNames = {"binding_id", "native_event_id"}),
            @UniqueConstraint(
                    name = "uk_mentor_event_review",
                    columnNames = {"review_id", "workspace_id"})
        },
        indexes = {
            @Index(name = "idx_mentor_event_source", columnList = "account_id, source_id"),
            @Index(name = "idx_mentor_event_request", columnList = "account_id, request_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorEvent {
    /** Derived from the binding and the provider event id, so a retry names the same event. */
    @Id
    private UUID id;

    private long accountId;

    private @Nullable Long bindingId;

    private @Nullable String nativeEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngressKind kind;

    private @Nullable UUID sourceId;

    private @Nullable UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Disposition disposition;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private @Nullable Reason reason;

    /** The retained provider record, {@code provider-event:<id>}; the event row holds no message text. */
    @Column(length = 64)
    private @Nullable String sourceRef;

    /** A sealed review's workspace, review, job and admission digest; null for a provider event. */
    private @Nullable Long workspaceId;

    private @Nullable UUID reviewId;

    private @Nullable UUID jobId;

    @Column(length = 64)
    private @Nullable String admissionDigest;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant receivedAt;

    public enum Disposition {
        /** A sealed review waits for the mentor's review handler. */
        PENDING,
        /** The event opened its request at version 1. */
        OPENED,
        /** A newer revision raised the open request's version. */
        REVISED,
        /** A delete or a Stop fenced the request's delivery, and stopped the request if it was live. */
        FENCED,
        /** The event changed no request; the reason says why. */
        RECORDED
    }

    public enum Reason {
        UNAUTHORIZED,
        /** The actor's account is not the one that owns the message. */
        AUTHOR_MISMATCH,
        ALREADY_OPENED,
        NO_REQUEST,
        STALE_REVISION,
        SOURCE_DELETED,
        REQUEST_SETTLED,
        /** A Stop by a person who does not own the message. */
        NOT_OWNER
    }
}
