package de.tum.cit.aet.hephaestus.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "repository_to_monitor",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_repository_to_monitor_workspace_id",
                        columnNames = {"workspace_id", "id"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
public class RepositoryToMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NonNull
    private String nameWithOwner;

    /**
     * The provider's stable numeric repository id (GitHub {@code repository.id}, GitLab
     * {@code project.id}). Unlike {@link #nameWithOwner} — which the provider rewrites on a
     * rename/transfer and which, once stale, silently severs the per-workspace NATS filter and the
     * name-keyed reconciliation lookup — the native id never changes for the life of the repository.
     * It is the identity of record used to re-key {@code nameWithOwner} after an upstream rename.
     *
     * <p>Nullable: rows created before this column existed (and PAT rows whose repository has not yet
     * been resolved from the provider) carry {@code null} until the next sync that resolves the repo
     * populates it. A workspace has a single SCM connection, so the id is unambiguous without a
     * provider discriminator column.
     */
    @Nullable
    private Long nativeId;

    private Instant repositorySyncedAt;
    private Instant labelsSyncedAt;
    private Instant milestonesSyncedAt;
    private Instant collaboratorsSyncedAt;

    // The time up to which issues have been synced in the recent sync
    private Instant issuesSyncedAt;

    // The time up to which pull requests have been synced in the recent sync
    private Instant pullRequestsSyncedAt;

    // The time up to which discussions have been synced in the recent sync
    private Instant discussionsSyncedAt;

    @Column(length = 2048)
    private @Nullable String recentSyncError;

    @Column(length = 2048)
    private @Nullable String historicalBackfillSyncError;

    public @Nullable String getSyncErrorSummary() {
        if (recentSyncError == null) {
            return historicalBackfillSyncError;
        }
        return historicalBackfillSyncError == null
                ? recentSyncError
                : recentSyncError + "; " + historicalBackfillSyncError;
    }

    // Issue Backfill Tracking

    /**
     * The highest issue number discovered in the repository for backfill tracking.
     * Set when issue backfill starts, used to know when issue backfill is complete.
     */
    private Integer issueBackfillHighWaterMark;

    /**
     * The lowest issue number that was successfully backfilled in the current batch.
     * Issue backfill works backwards from highWaterMark down to 1 (CREATED_AT DESC).
     * When this reaches 0, issue backfill is complete.
     */
    private Integer issueBackfillCheckpoint;

    // Pull Request Backfill Tracking

    /**
     * The highest pull request number discovered in the repository for backfill tracking.
     * Set when pull request backfill starts, used to know when pull request backfill is complete.
     */
    private Integer pullRequestBackfillHighWaterMark;

    /**
     * The lowest pull request number that was successfully backfilled in the current batch.
     * Pull request backfill works backwards from highWaterMark down to 1 (CREATED_AT DESC).
     * When this reaches 0, pull request backfill is complete.
     */
    private Integer pullRequestBackfillCheckpoint;

    /**
     * When the backfill was last run. Used for cooldown between backfill batches.
     */
    private Instant backfillLastRunAt;

    /**
     * Pagination cursor for issue sync. Persisted to allow resumption if sync
     * fails mid-pagination. Cleared when sync completes successfully.
     */
    private @Nullable String issueSyncCursor;

    /**
     * Pagination cursor for pull request sync. Persisted to allow resumption if sync
     * fails mid-pagination. Cleared when sync completes successfully.
     */
    private @Nullable String pullRequestSyncCursor;

    /**
     * Pagination cursor for discussion sync. Persisted to allow resumption if sync
     * fails mid-pagination. Cleared when sync completes successfully.
     */
    private @Nullable String discussionSyncCursor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    @ToString.Exclude
    private @Nullable Workspace workspace;

    // Backfill Status Helper Methods

    /**
     * Checks if issue backfill has been initialized (high water mark set).
     * @return true if issue backfill tracking has been initialized
     */
    public boolean isIssueBackfillInitialized() {
        return issueBackfillHighWaterMark != null;
    }

    /**
     * Checks if pull request backfill has been initialized (high water mark set).
     * @return true if pull request backfill tracking has been initialized
     */
    public boolean isPullRequestBackfillInitialized() {
        return pullRequestBackfillHighWaterMark != null;
    }

    /**
     * Checks if backfill has been initialized for either issues or pull requests.
     * @return true if any backfill tracking has been initialized
     */
    public boolean isBackfillInitialized() {
        return isIssueBackfillInitialized() || isPullRequestBackfillInitialized();
    }

    /**
     * Checks if issue backfill has completed.
     * Complete when checkpoint is 0 or high water mark is 0 (empty).
     * @return true if issue backfill finished or there were no issues to backfill
     */
    public boolean isIssueBackfillComplete() {
        if (!isIssueBackfillInitialized()) {
            return false;
        }
        return issueBackfillHighWaterMark == 0 || (issueBackfillCheckpoint != null && issueBackfillCheckpoint <= 0);
    }

    /**
     * Checks if pull request backfill has completed.
     * Complete when checkpoint is 0 or high water mark is 0 (empty).
     * @return true if pull request backfill finished or there were no pull requests to backfill
     */
    public boolean isPullRequestBackfillComplete() {
        if (!isPullRequestBackfillInitialized()) {
            return false;
        }
        return (pullRequestBackfillHighWaterMark == 0
                || (pullRequestBackfillCheckpoint != null && pullRequestBackfillCheckpoint <= 0));
    }

    /**
     * Checks if all backfill has completed (both issues and pull requests).
     * @return true if both issue and pull request backfill finished
     */
    public boolean isBackfillComplete() {
        return isIssueBackfillComplete() && isPullRequestBackfillComplete();
    }

    /**
     * Checks if backfill is in progress (initialized but not complete).
     * @return true if backfill has started and is not yet complete
     */
    public boolean isBackfillInProgress() {
        return isBackfillInitialized() && !isBackfillComplete();
    }

    /**
     * Returns the number of issues remaining to backfill.
     * @return issues remaining, or 0 if not initialized or complete
     */
    public int getIssueBackfillRemaining() {
        if (!isIssueBackfillInitialized() || issueBackfillHighWaterMark == 0) {
            return 0;
        }
        if (issueBackfillCheckpoint == null) {
            return issueBackfillHighWaterMark;
        }
        return Math.max(0, issueBackfillCheckpoint);
    }

    /**
     * Returns the number of pull requests remaining to backfill.
     * @return pull requests remaining, or 0 if not initialized or complete
     */
    public int getPullRequestBackfillRemaining() {
        if (!isPullRequestBackfillInitialized() || pullRequestBackfillHighWaterMark == 0) {
            return 0;
        }
        if (pullRequestBackfillCheckpoint == null) {
            return pullRequestBackfillHighWaterMark;
        }
        return Math.max(0, pullRequestBackfillCheckpoint);
    }

    /**
     * Returns the total number of items remaining to backfill (issues + pull requests).
     * @return total items remaining
     */
    public int getBackfillRemaining() {
        return getIssueBackfillRemaining() + getPullRequestBackfillRemaining();
    }
}
