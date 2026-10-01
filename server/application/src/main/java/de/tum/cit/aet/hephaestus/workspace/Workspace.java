package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewSettings;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Root tenant entity in Hephaestus's multi-tenant architecture.
 *
 * <p>A Workspace represents an isolated tenant boundary, scoping all domain data
 * (repositories, users, issues, PRs, teams) to a single git provider organization or user account.
 * All workspace-scoped queries are filtered by {@code workspace_id} to enforce tenant isolation.
 *
 * <h2>Multi-Tenancy Model</h2>
 * <ul>
 *   <li><b>Isolation:</b> Each workspace operates independently with its own data, settings, and members</li>
 *   <li><b>Identity:</b> Uniquely identified by {@link #workspaceSlug} (URL-safe) and internal {@link #id}</li>
 *   <li><b>Ownership:</b> Linked to a git provider account via {@link #accountLogin}</li>
 * </ul>
 *
 * <h2>Authentication &amp; Provider Identity</h2>
 * Authentication credentials, provider classification, and integration-specific
 * configuration (GitHub App installation id, GitLab group id / webhook id, Slack
 * channel + token, …) are <em>not</em> stored on this entity. They live on per-kind
 * {@link de.tum.cit.aet.hephaestus.integration.core.connection.Connection Connection} rows
 * owned by the {@link de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService
 * ConnectionService}. Use {@code ConnectionService.findActiveProviderKind(workspaceId)}
 * to classify a workspace at runtime and the typed accessors
 * ({@code findActiveGitHubAppConfig}, {@code findActiveGitLabConfig},
 * {@code findSlackConfig}, {@code findActiveBearerToken}) to read
 * per-Connection state.
 *
 * <h2>Lifecycle States</h2>
 * Workspaces follow a defined lifecycle managed by {@link WorkspaceLifecycleService}:
 * <pre>
 *   ACTIVE ──suspend──► SUSPENDED ──resume──► ACTIVE
 *      │                    │
 *      └──────purge────────►│
 *                           └──────purge─────► PURGED (terminal)
 * </pre>
 *
 * <h2>Key Relationships</h2>
 * <ul>
 *   <li>{@link #repositoriesToMonitor} – Repositories tracked for webhook events</li>
 *   <li>{@link #organization} – Optional link to synced GitHub Organization entity</li>
 *   <li>{@link WorkspaceMembership} – User memberships with roles (OWNER/ADMIN/MEMBER)</li>
 * </ul>
 *
 * @see WorkspaceContext
 * @see WorkspaceMembership
 * @see WorkspaceLifecycleService
 * @see de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService
 */
@Entity
@Table(name = "workspace")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Sync Timestamps - Track last successful sync for each data domain
    // Used by sync services to determine incremental fetch windows

    /** Last sync time for organization users (collaborators with repo access) */
    private @Nullable Instant usersSyncedAt;

    /** Last sync time for organization teams structure */
    private @Nullable Instant teamsSyncedAt;

    /** Last sync time for team membership assignments */
    private @Nullable Instant membersSyncedAt;

    /** Last sync time for sub-issue relationships (parent-child) via GraphQL */
    private @Nullable Instant subIssuesSyncedAt;

    /** Last sync time for organization issue types via GraphQL */
    private @Nullable Instant issueTypesSyncedAt;

    /** Last sync time for issue dependencies (blocked_by/blocking) via GraphQL */
    private @Nullable Instant issueDependenciesSyncedAt;

    // Identity & Display

    /**
     * URL-safe unique identifier for the workspace.
     * Used in URLs: {@code /api/workspaces/{slug}/...}
     * <p>
     * Format: lowercase alphanumeric with hyphens, 3-51 chars, must start with alphanumeric.
     * Immutable after creation (renames create redirect entries in {@link WorkspaceSlugHistory}).
     */
    @Column(name = "slug", unique = true, nullable = false, length = 64)
    @NotBlank(message = "Workspace slug is required")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,50}$")
    private String workspaceSlug;

    /** Human-readable name shown in UI (e.g., "TUM Applied Software Engineering") */
    @Column(name = "display_name", nullable = false, length = 120)
    @NotBlank(message = "Display name is required")
    private String displayName;

    /**
     * When {@code true}, unauthenticated users can read the workspace's public endpoints.
     * Defaults to {@code false} for privacy.
     */
    @Column(name = "is_publicly_viewable", nullable = false)
    @NotNull(message = "Public viewable flag is required")
    private Boolean isPubliclyViewable = false;

    /**
     * Current lifecycle state of the workspace.
     *
     * @see WorkspaceStatus for valid states and transitions
     * @see WorkspaceLifecycleService for state transition operations
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @NotNull(message = "Status is required")
    @NonNull
    private WorkspaceStatus status = WorkspaceStatus.ACTIVE;

    // Repository Monitoring

    /**
     * Repositories this workspace monitors for webhook events.
     * <p>
     * Note: EAGER fetch is intentional - workspace queries typically need repository list,
     * and the set is bounded (typically &lt;100 repos per workspace).
     */
    @OneToMany(
            mappedBy = "workspace",
            fetch = FetchType.EAGER,
            cascade = {CascadeType.PERSIST, CascadeType.REMOVE},
            orphanRemoval = true)
    @ToString.Exclude
    private Set<RepositoryToMonitor> repositoriesToMonitor = new HashSet<>();

    // Git Provider Account

    /** Git provider account login (GitHub org login or GitLab group path, e.g., "ls1intum" or "org/team") */
    @Column(name = "account_login", nullable = false, length = 120)
    @NotBlank(message = "Account login is required")
    private String accountLogin;

    /** Whether the git provider account is an organization/group or individual user */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10)
    @NotNull(message = "Account type is required")
    private AccountType accountType;

    /** Repository selection mode: ALL repositories or SELECTED subset */
    @Enumerated(EnumType.STRING)
    @Column(name = "repository_selection")
    private RepositorySelection repositorySelection;

    /**
     * Synced organization entity — GitHub organization or GitLab group
     * (populated after initial organization sync).
     * Null for user-type workspaces or before first sync completes.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", unique = true, foreignKey = @ForeignKey(name = "fk_workspace_organization"))
    @ToString.Exclude
    private @Nullable Organization organization;

    // Audit Timestamps

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Practice reviews

    @Embedded
    @Valid
    private WorkspaceFeatures features = new WorkspaceFeatures();

    // AI configuration

    /**
     * Monthly cap in USD on this workspace's INSTANCE-funded spend — the shared models the host pays
     * for. {@code null} = uncapped; reaching it pauses work on shared models until the calendar month
     * (UTC) rolls over. Set exclusively by instance admins, since it is the host's cost backstop.
     */
    @Column(name = "monthly_llm_budget_usd", precision = 10, scale = 2)
    private @Nullable BigDecimal monthlyLlmBudgetUsd;

    /**
     * Monthly cap in USD on this workspace's OWN-provider (BYO) spend, set by the workspace's own
     * admins. {@code null} = uncapped. The two caps are different people's money and are
     * <b>never summed</b> — see {@code docs/contributor/llm-cost-vocabulary.md}, rule 2.
     *
     * <p>The API and the UI say "ownProvider"; the field keeps the released column's "byo" spelling.
     */
    @Column(name = "monthly_byo_llm_budget_usd", precision = 10, scale = 2)
    private @Nullable BigDecimal monthlyByoLlmBudgetUsd;

    /**
     * Per-workspace practice-review trigger/delivery overrides; read via {@link #getReviewSettings()}.
     *
     * @see PracticeReviewSettings
     */
    @Embedded
    @Valid
    private PracticeReviewSettings reviewSettings = new PracticeReviewSettings();

    /** Null-safe accessor: Hibernate sets the embeddable to {@code null} when every column is null. */
    public PracticeReviewSettings getReviewSettings() {
        if (reviewSettings == null) {
            reviewSettings = new PracticeReviewSettings();
        }
        return reviewSettings;
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // Nested Enums

    /**
     * Workspace lifecycle states with defined transition rules.
     *
     * <h2>State Machine</h2>
     * <pre>
     *   ACTIVE ──suspend──► SUSPENDED ──resume──► ACTIVE
     *      │                    │
     *      └──────purge────────►│
     *                           └──────purge─────► PURGED (terminal)
     * </pre>
     *
     * <h2>Behavior by State</h2>
     * <ul>
     *   <li><b>ACTIVE:</b> Normal operation - syncs run, webhooks processed, full access</li>
     *   <li><b>SUSPENDED:</b> Read-only mode - no syncs, webhooks queued, limited access</li>
     *   <li><b>PURGED:</b> Soft-deleted - excluded from queries, pending hard delete</li>
     * </ul>
     *
     * @see WorkspaceLifecycleService for state transition operations
     */
    public enum WorkspaceStatus {
        /** Workspace is fully operational */
        ACTIVE,
        /** Workspace is temporarily disabled (billing, maintenance, etc.) */
        SUSPENDED,
        /** Workspace is soft-deleted and pending permanent removal */
        PURGED,
    }
}
