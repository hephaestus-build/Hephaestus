package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
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
     * Whether the public page lists contributors who have no account that chose to show. Off by default: the page
     * then counts them without naming them (GDPR Art. 25(2), data protection by default).
     */
    @Column(name = "public_activity_contributors_without_account", nullable = false)
    @ColumnDefault("false")
    private boolean publicActivityContributorsWithoutAccount;

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
    @Setter(AccessLevel.NONE)
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

    /** The one way to build a workspace: active, and with public activity off until an admin turns it on. */
    public static Workspace create(String slug, String displayName, String accountLogin, AccountType accountType) {
        var workspace = new Workspace();
        workspace.workspaceSlug = slug;
        workspace.displayName = displayName;
        workspace.publicActivityEnabled = false;
        workspace.accountLogin = accountLogin;
        workspace.accountType = accountType;
        return workspace;
    }

    /**
     * Suspends an active workspace.
     *
     * @return whether the status changed
     * @throws WorkspaceLifecycleViolationException if the workspace is purged
     */
    public boolean suspend() {
        return moveTo(WorkspaceStatus.SUSPENDED, "suspend");
    }

    /**
     * Makes a suspended workspace active again.
     *
     * @return whether the status changed
     * @throws WorkspaceLifecycleViolationException if the workspace is purged
     */
    public boolean resume() {
        return moveTo(WorkspaceStatus.ACTIVE, "resume");
    }

    /** Only {@link WorkspaceLifecycleService#purgeWorkspace}, which first runs every purge contributor, purges. */
    boolean markPurged() {
        return moveTo(WorkspaceStatus.PURGED, "purge");
    }

    private boolean moveTo(WorkspaceStatus next, String action) {
        if (status == next) return false;
        if (!status.canTransitionTo(next)) {
            // PURGED is the only state without exits.
            throw new WorkspaceLifecycleViolationException(
                    "The workspace “" + workspaceSlug + "” is purged. You cannot " + action + " it.");
        }
        status = next;
        return true;
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
     * Workspace lifecycle states. A suspended workspace runs no sync and processes no webhook. A purged workspace has lost
     * its data and stays purged: the {@code guard_workspace} trigger refuses any change of its status as well.
     */
    public enum WorkspaceStatus implements Lifecycle<WorkspaceStatus> {
        /** Workspace is fully operational */
        ACTIVE,
        /** Workspace is temporarily disabled (billing, maintenance, etc.) */
        SUSPENDED,
        /** Workspace is soft-deleted and pending permanent removal */
        PURGED;

        private static final Map<WorkspaceStatus, Set<WorkspaceStatus>> LEGAL = Map.of(
                ACTIVE, Set.of(SUSPENDED, PURGED),
                SUSPENDED, Set.of(ACTIVE, PURGED),
                PURGED, Set.of());

        @Override
        public boolean canTransitionTo(WorkspaceStatus next) {
            return Objects.requireNonNull(LEGAL.get(this)).contains(next);
        }
    }
}
