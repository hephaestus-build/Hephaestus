package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.util.Collections;
import java.util.List;

/**
 * Result of a GitLab group project sync operation.
 * <p>
 * Provides structured feedback about the sync outcome, including partial success
 * scenarios where some projects were synced but others failed.
 *
 * @param status              the overall sync outcome
 * @param synced              successfully synced repositories (unmodifiable)
 * @param pagesCompleted      number of pagination pages successfully fetched
 * @param projectsSkipped     number of individual projects that failed to process
 * @param projectsRedacted    number of projects returned as null by GitLab due to access restrictions
 * @param projectsReconciled  number of direct projects recovered by the reconciliation pass, which
 *                            {@code includeSubgroups: true} can drop
 *                            (<a href="https://gitlab.com/gitlab-org/gitlab/-/issues/33419">GitLab #33419</a>)
 */
public record GitLabSyncResult(
        Status status,
        List<Repository> synced,
        int pagesCompleted,
        int projectsSkipped,
        int projectsRedacted,
        int projectsReconciled) {
    public enum Status {
        /** All projects synced successfully. */
        COMPLETED,
        /** Sync finished but some projects or pages failed. */
        COMPLETED_WITH_ERRORS,
        /** Sync aborted due to critical rate limit exhaustion. */
        ABORTED_RATE_LIMIT,
        /** Sync aborted due to an unrecoverable error. */
        ABORTED_ERROR,
    }

    /** Successful sync with no errors. */
    public static GitLabSyncResult completed(
            List<Repository> synced, int pagesCompleted, int projectsRedacted, int projectsReconciled) {
        return new GitLabSyncResult(
                Status.COMPLETED,
                Collections.unmodifiableList(synced),
                pagesCompleted,
                0,
                projectsRedacted,
                projectsReconciled);
    }

    /** Sync finished but some projects or pages had errors. */
    public static GitLabSyncResult withErrors(
            List<Repository> synced,
            int pagesCompleted,
            int projectsSkipped,
            int projectsRedacted,
            int projectsReconciled) {
        return new GitLabSyncResult(
                Status.COMPLETED_WITH_ERRORS,
                Collections.unmodifiableList(synced),
                pagesCompleted,
                projectsSkipped,
                projectsRedacted,
                projectsReconciled);
    }

    /** Sync aborted (returns whatever was synced before the abort). */
    public static GitLabSyncResult aborted(
            Status status, List<Repository> syncedSoFar, int pagesCompleted, int projectsSkipped) {
        return new GitLabSyncResult(
                status, Collections.unmodifiableList(syncedSoFar), pagesCompleted, projectsSkipped, 0, 0);
    }
}
