package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Committed before provider I/O. Neither a timeout nor a successful DELETE is a confirmed outcome. */
@Entity
@Table(
        name = "github_access_action",
        indexes = @Index(name = "idx_github_access_action_target_status", columnList = "workspace_id,target_id,status"))
@Getter
@Setter
@NoArgsConstructor
public class GitHubAccessAction {
    public enum Type {
        GRANT,
        REVOKE
    }

    public enum Status {
        PENDING,
        CONFIRMED,
        INVALIDATED,
        MANUAL_RECOVERY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_github_access_action_workspace"))
    private Workspace workspace;

    @ManyToOne(optional = false)
    @JoinColumn(name = "target_id", nullable = false, foreignKey = @ForeignKey(name = "fk_github_access_action_target"))
    private GitHubAccessTarget target;

    @ManyToOne(optional = false)
    @JoinColumn(
            name = "membership_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_github_access_action_membership"))
    private GitHubAccessMembership membership;

    /** Historical intended scope, independent of later labels, credentials or policy edits. */
    @Column(name = "organization_id", nullable = false)
    private long organizationId;

    @Column(name = "scope_id", nullable = false)
    private long scopeId;

    @Column(name = "github_user_id", nullable = false)
    private long githubUserId;

    @Column(name = "configuration_version", nullable = false)
    private long configurationVersion;

    @Column(name = "directory_configuration_version")
    private @Nullable Long directoryConfigurationVersion;

    @Column(name = "directory_capture_started_at")
    private @Nullable Instant directoryCaptureStartedAt;

    @Column(name = "directory_source_version")
    private @Nullable Instant directorySourceVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_state", length = 32)
    private GitHubAccessClient.@Nullable State providerState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_attempt_at")
    private @Nullable Instant lastAttemptAt;

    @Column(name = "confirmed_at")
    private @Nullable Instant confirmedAt;

    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 32)
    private GitHubAccessFailure.@Nullable Reason failureCode;

    @Column(name = "failure_reason", length = 512)
    private @Nullable String failureReason;

    @Column(name = "retry_at")
    private @Nullable Instant retryAt;
}
