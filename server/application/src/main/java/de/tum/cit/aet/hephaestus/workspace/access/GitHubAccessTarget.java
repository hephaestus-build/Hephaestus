package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** One independently reconciled membership scope. Ending configuration never erases pending revocations. */
@Entity
@Table(
        name = "github_access_target",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_github_access_target_id_workspace",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(name = "uq_github_access_target_connection", columnNames = "connection_id"),
            @UniqueConstraint(name = "uq_github_access_target_handoff", columnNames = "handoff_hash")
        })
@Getter
@Setter
@NoArgsConstructor
public class GitHubAccessTarget {
    public enum Status {
        DRAFT,
        ACTIVE,
        ENDING,
        ENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_github_access_target_workspace"))
    private Workspace workspace;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    /** Unverified setup inputs disclose no private GitHub inventory before organization-owner approval. */
    @Column(name = "requested_organization", nullable = false, length = 100)
    private String requestedOrganization;

    @Column(name = "requested_team", length = 100)
    private @Nullable String requestedTeam;

    @Column(name = "pending_installation_id", nullable = false)
    private long pendingInstallationId;

    /** Assigned only through the owner handoff; immutable after authority is acquired. */
    @Column(name = "organization_id")
    private @Nullable Long organizationId;

    /** Zero is organization-wide; a positive value is a native team ID. */
    @Column(name = "scope_id")
    private @Nullable Long scopeId;

    @Column(name = "organization_login", length = 100)
    private @Nullable String organizationLogin;

    @Column(name = "scope_name", length = 255)
    private @Nullable String scopeName;

    @Column(name = "registration_id", nullable = false, length = 64)
    private String registrationId;

    @Column(nullable = false, length = 512)
    private String issuer;

    @Column(name = "directory_provider_id", nullable = false)
    private Long directoryProviderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private boolean paused;

    @Column(name = "authority_held", nullable = false)
    private boolean authorityHeld;

    @Column(name = "configuration_version", nullable = false)
    private long configurationVersion = 1;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_group_ids", columnDefinition = "jsonb", nullable = false)
    private Set<String> draftGroupIds = Set.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_group_ids", columnDefinition = "jsonb", nullable = false)
    private Set<String> approvedGroupIds = Set.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "owner_authorization", columnDefinition = "jsonb")
    private GitHubAccessEvidence.@Nullable Authorization authorization;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private GitHubAccessEvidence.@Nullable Preview preview;

    @Column(name = "approved_by_account_id")
    private @Nullable Long approvedByAccountId;

    @Column(name = "approved_at")
    private @Nullable Instant approvedAt;

    @Column(name = "handoff_issued_by_account_id")
    private @Nullable Long handoffIssuedByAccountId;

    @Column(name = "handoff_hash", length = 64)
    private @Nullable String handoffHash;

    @Column(name = "handoff_expires_at")
    private @Nullable Instant handoffExpiresAt;

    @Column(name = "last_attempt_at")
    private @Nullable Instant lastAttemptAt;

    @Column(name = "last_confirmed_at")
    private @Nullable Instant lastConfirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 32)
    private GitHubAccessFailure.@Nullable Reason failureCode;

    @Column(name = "failure_reason", length = 512)
    private @Nullable String failureReason;

    @Column(name = "retry_at")
    private @Nullable Instant retryAt;
}
