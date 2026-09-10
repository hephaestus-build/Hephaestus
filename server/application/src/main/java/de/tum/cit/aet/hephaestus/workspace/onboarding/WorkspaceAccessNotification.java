package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Domain outbox: a request commits its notification intent in the same transaction as its decision. */
@Entity
@Table(
        name = "workspace_access_notification",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_workspace_access_notification_request_kind",
                        columnNames = {"workspace_id", "request_id", "kind"}),
        indexes = @Index(name = "idx_workspace_access_notification_due", columnList = "state, next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor
class WorkspaceAccessNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_access_notification_workspace"))
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "request_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_access_notification_request"))
    private WorkspaceAccessRequest request;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private Reason reason;

    @Nullable
    @Column(name = "sent_at")
    private Instant sentAt;

    enum Kind {
        SUBMITTED,
        DECIDED,
        REMINDER,
        EXPIRED
    }

    enum State {
        PENDING,
        SENT,
        FAILED,
        CANCELLED
    }

    enum Reason {
        SILENT_MODE,
        MAIL_NOT_CONFIGURED,
        NO_VERIFIED_CONTACT,
        INVALID_CONTACT,
        DELIVERY_FAILED,
        REQUEST_REPLACED
    }
}
