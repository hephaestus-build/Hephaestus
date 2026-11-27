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

/** One admitted provider event, kept once per binding and provider event id, with what intake did with it. */
@Entity
@PersonOwned
@Table(
        name = "mentor_event",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_mentor_event_key",
                        columnNames = {"binding_id", "native_event_id"}),
        indexes = {
            @Index(name = "idx_mentor_event_account", columnList = "account_id"),
            @Index(name = "idx_mentor_event_source", columnList = "source_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorEvent {
    /** Derived from the binding and the provider event id, so a retry names the same event. */
    @Id
    private UUID id;

    private long accountId;

    private long bindingId;

    @Column(nullable = false)
    private String nativeEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngressKind kind;

    @Column(nullable = false)
    private UUID sourceId;

    private @Nullable UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Disposition disposition;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private @Nullable Reason reason;

    /** The retained provider record, {@code provider-event:<id>}; the event row holds no message text. */
    @Column(nullable = false, length = 64)
    private String sourceRef;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant receivedAt;

    public enum Disposition {
        /** The event opened its request at version 1. */
        OPENED,
        /** A newer revision raised the open request's version. */
        REVISED,
        /** A delete stopped the open request. */
        FENCED,
        /** The event changed no request; the reason says why. */
        RECORDED
    }

    public enum Reason {
        UNAUTHORIZED,
        ALREADY_OPENED,
        NO_REQUEST,
        STALE_REVISION,
        SOURCE_DELETED,
        REQUEST_SETTLED,
        STOP_NOT_HANDLED
    }
}
