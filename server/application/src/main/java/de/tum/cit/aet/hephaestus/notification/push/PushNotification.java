package de.tum.cit.aet.hephaestus.notification.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * One push notification for one device about one workspace: a durable outbox row that records the Expo
 * ticket and, later, its receipt. The unique window key coalesces a burst — a backfill preparing many
 * pieces of feedback at once — into one notification per device, workspace and hour.
 */
@Entity
@Table(
        name = "push_notification",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_push_notification_window",
                        columnNames = {"push_device_id", "workspace_id", "kind", "window_start"}),
        indexes = {
            @Index(name = "ix_push_notification_state", columnList = "state, next_attempt_at"),
            @Index(name = "ix_push_notification_workspace", columnList = "workspace_id, created_at"),
        })
@Getter
@Setter
@NoArgsConstructor
public class PushNotification {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Version
    private long version;

    /** Registration used for the send; a late receipt must not remove a newer registration. */
    private @Nullable Long sentDeviceVersion;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** The synced developer the feedback waits for; membership is checked against it before sending. */
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "push_device_id", nullable = false, columnDefinition = "uuid")
    private UUID pushDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** Expo's ticket id, once the push service accepted the message. */
    @Column(name = "ticket_id", length = 64)
    private @Nullable String ticketId;

    @Column(name = "failure_reason", length = 64)
    private @Nullable String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private @Nullable Instant sentAt;

    @Column(name = "settled_at")
    private @Nullable Instant settledAt;

    public enum Kind {
        /** Practice feedback waits on the developer's practice pages. */
        PRACTICE_FEEDBACK,
    }

    public enum State {
        /** Queued, or released for another attempt at {@code next_attempt_at}. */
        PENDING,
        /** Claimed by a sender until {@code next_attempt_at}; a crashed sender's claim lapses back. */
        SENDING,
        /** Accepted by the push service; the receipt is not in yet. */
        SENT,
        /** The receipt says the push service handed it to Apple or Google. */
        DELIVERED,
        /** Not sent: the session ended, consent lapsed, membership ended, or push was turned off. */
        SUPPRESSED,
        /** Rejected by the push service, or retries ran out. */
        FAILED,
    }
}
