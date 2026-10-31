package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.BaseGitServiceEntity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Represents a Git repository from a provider (e.g., GitHub).
 * <p>
 * Repositories are the organizational unit containing issues, pull requests, labels,
 * and milestones. They may belong to an {@link Organization} or be user-owned.
 * <p>
 * <b>Relationship Summary:</b>
 * <ul>
 *   <li>{@link #organization} – Parent organization (null for user-owned repos)</li>
 *   <li>{@link #issues} – All issues and PRs in this repository</li>
 *   <li>{@link #labels} – Labels available for categorization</li>
 *   <li>{@link #milestones} – Milestones for release planning</li>
 *   <li>{@link #collaborators} – Users with direct repository access</li>
 *   <li>{@link #teamRepoPermissions} – Team-level access permissions</li>
 * </ul>
 * <p>
 * <b>Sync Targets:</b> Repositories become sync targets when added to a scope.
 * The sync engine uses the {@code nameWithOwner} field to identify repositories.
 *
 * @see de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget
 */
@Entity
@Table(
        name = "repository",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_repository_provider_name_with_owner",
                    columnNames = {"provider_id", "name_with_owner"}),
            @UniqueConstraint(
                    name = "uq_repository_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class Repository extends BaseGitServiceEntity {

    @NonNull
    private String name;

    // GitHub limits: owner (39) + "/" (1) + repo name (100) = 140 chars max
    @NonNull
    @Column(length = 150)
    private String nameWithOwner;

    // Whether the repository is private or public.
    private boolean isPrivate;

    // GitHub repository URL: https://github.com/owner/repo (max ~160 base + paths)
    @NonNull
    @Column(length = 512)
    private String htmlUrl;

    @Column(columnDefinition = "TEXT")
    private @Nullable String description;

    @NonNull
    private Instant pushedAt;

    private boolean isArchived;

    // Returns whether or not this repository disabled.
    private boolean isDisabled;

    @NonNull
    @Enumerated(EnumType.STRING)
    private Visibility visibility;

    private @Nullable String defaultBranch;

    /**
     * Timestamp of the last successful sync for this repository from the Git provider.
     * <p>
     * This is ETL infrastructure used by the sync engine to track when this repository
     * was last synchronized via GraphQL. Used to implement sync cooldown logic
     * and detect stale data.
     */
    private Instant lastSyncAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", foreignKey = @ForeignKey(name = "fk_repository_organization"))
    @ToString.Exclude
    private @Nullable Organization organization;

    @OneToMany(mappedBy = "repository", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<Issue> issues = new HashSet<>();

    @OneToMany(mappedBy = "repository", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @BatchSize(size = 50) // Batch fetch labels to avoid Cartesian product in team queries
    @ToString.Exclude
    private Set<Label> labels = new HashSet<>();

    @OneToMany(mappedBy = "repository", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<Milestone> milestones = new HashSet<>();

    @OneToMany(mappedBy = "repository", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<TeamRepositoryPermission> teamRepoPermissions = new HashSet<>();

    @OneToMany(mappedBy = "repository", cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    private Set<RepositoryCollaborator> collaborators = new HashSet<>();

    public enum Visibility {
        PUBLIC,
        PRIVATE,
        INTERNAL,
        UNKNOWN,
    }

    /*
     * Field coverage notes (GitHub API → entity mapping):
     *
     * Supported fields synced from GitHub GraphQL API:
     * - id/databaseId, name, nameWithOwner, url, description
     * - pushedAt, defaultBranchRef, visibility, isArchived, isDisabled, isPrivate
     * - owner (Organization or User) → linked via organization relation
     *
     * Fields available in GraphQL but intentionally NOT synced:
     * - homepageUrl, stargazerCount, forkCount, watchers
     * - hasIssuesEnabled, hasProjectsEnabled, hasWikiEnabled
     * - hasSponsorshipsEnabled
     * - hasVulnerabilityAlertsEnabled (requires admin access)
     * - isFork, isTemplate, isMirror (not needed for ETL consumers)
     * - licenseInfo, primaryLanguage (out of scope for current use cases)
     */
}
