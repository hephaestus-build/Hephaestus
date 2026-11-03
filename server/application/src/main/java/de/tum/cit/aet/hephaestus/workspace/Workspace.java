package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewSettings;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlug;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

// Integration credentials and provider configuration belong to connection rows, outside the tenant entity.
@Entity
// Sync timestamps must not restore administrative fields from an earlier managed snapshot.
@DynamicUpdate
@Table(name = "workspace")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Workspace {

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean adminPasskeyRequired;

    @Column(name = "practice_catalog_installed_at")
    private @Nullable Instant practiceCatalogInstalledAt;

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

    @Column(name = "slug", unique = true, nullable = false, length = 64)
    @NotBlank(message = "Workspace slug is required")
    @WorkspaceSlug
    private String workspaceSlug;

    /** Human-readable name shown in UI (e.g., "TUM Applied Software Engineering") */
    @Column(name = "display_name", nullable = false, length = 120)
    @NotBlank(message = "Display name is required")
    private String displayName;

    /**
     * When {@code true}, the dedicated public activity page can publish public repository activity.
     * Defaults to {@code false} for privacy.
     */
    @Column(name = "public_activity_enabled", nullable = false)
    @NotNull(message = "Public activity setting is required")
    @ColumnDefault("false")
    private Boolean publicActivityEnabled = false;

    @Column(name = "public_activity_search_engines", nullable = false)
    @ColumnDefault("false")
    private boolean publicActivitySearchEngines;

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

    @OneToMany(
            mappedBy = "workspace",
            cascade = {CascadeType.PERSIST, CascadeType.REMOVE},
            orphanRemoval = true)
    @ToString.Exclude
    private Set<RepositoryToMonitor> repositoriesToMonitor = new HashSet<>();

    // Git Provider Account

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

    @Column(name = "organization_id", unique = true)
    private @Nullable Long organizationId;

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

    // Only instance admins set the cap on instance-funded spend; null is uncapped.
    @Column(name = "monthly_llm_budget_usd", precision = 10, scale = 2)
    private @Nullable BigDecimal monthlyLlmBudgetUsd;

    // Workspace admins control own-provider spend; its cap is separate from the instance-funded cap.
    @Column(name = "monthly_byo_llm_budget_usd", precision = 10, scale = 2)
    private @Nullable BigDecimal monthlyByoLlmBudgetUsd;

    @Embedded
    @Valid
    private PracticeReviewSettings reviewSettings = new PracticeReviewSettings();

    /**
     * Null-safe accessor: Hibernate sets the embeddable to {@code null} when every column is null.
     */
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
     *
     * <pre>
     *   ACTIVE ──suspend──► SUSPENDED ──resume──► ACTIVE
     *      │                    │
     *      └──────purge────────►│
     *                           └──────purge─────► PURGED (terminal)
     * </pre>
     *
     * <h2>Behavior by State</h2>
     *
     * <ul>
     *   <li><b>ACTIVE:</b> Normal operation - syncs run, webhooks processed, full access
     *   <li><b>SUSPENDED:</b> Read-only mode - no syncs, webhooks queued, limited access
     *   <li><b>PURGED:</b> Soft-deleted - excluded from queries, pending hard delete
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
