package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Human access belongs to an account; SCM membership rows retain provider activity and league state. */
@Entity
@Table(
        name = "workspace_account_membership",
        indexes = @Index(name = "idx_workspace_account_membership_account", columnList = "account_id"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_workspace_account_membership",
                        columnNames = {"workspace_id", "account_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceAccountMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_account_membership_workspace"))
    private Workspace workspace;

    /** Auth owns accounts; the migration supplies the cross-module sfk foreign key. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private WorkspaceRole role = WorkspaceRole.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source = Source.MANUAL;

    /** Immutable directory subject, never an email or display name; present only for managed access. */
    @Column(name = "directory_subject", length = 512)
    private @org.jspecify.annotations.Nullable String directorySubject;

    /** A removal is retained as a suspension so synchronization cannot undo a manual decision. */
    @Column(name = "suspended", nullable = false)
    private boolean suspended;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Absolute authorization boundary, independent of background expiry or provider delivery. */
    @Nullable
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Nullable
    @Column(name = "access_request_id")
    private Long accessRequestId;

    public boolean isActive() {
        return isActiveAt(Instant.now());
    }

    public boolean isActiveAt(Instant now) {
        return !suspended && (expiresAt == null || expiresAt.isAfter(now));
    }

    public enum Source {
        MANUAL,
        SCM,
        DIRECTORY,
        REQUEST,
        MIGRATED
    }
}
