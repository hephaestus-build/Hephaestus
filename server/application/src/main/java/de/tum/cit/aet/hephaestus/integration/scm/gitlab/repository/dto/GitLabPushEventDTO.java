package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitLab push webhook events.
 * <p>
 * Contains the project metadata embedded in every push event, which is used
 * to upsert the repository entity when a push arrives for an unknown project.
 * Also contains commit data for creating Commit entities from webhook payloads.
 *
 * @see <a href="https://docs.gitlab.com/ee/user/project/integrations/webhook_events.html#push-events">
 *      GitLab Push Events</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabPushEventDTO(
        @JsonProperty("object_kind") @Nullable String objectKind,
        @JsonProperty("ref") @Nullable String ref,
        @JsonProperty("before") @Nullable String before,
        @JsonProperty("after") @Nullable String after,
        @JsonProperty("checkout_sha") @Nullable String checkoutSha,
        @JsonProperty("project_id") @Nullable Long projectId,
        @JsonProperty("project") @Nullable ProjectInfo project,
        @JsonProperty("total_commits_count") int totalCommitsCount,
        @JsonProperty("commits") @Nullable List<CommitInfo> commits) {
    /**
     * Commit data embedded in the push event payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitInfo(
            @JsonProperty("id") String id,
            @JsonProperty("message") @Nullable String message,
            @JsonProperty("title") @Nullable String title,
            @JsonProperty("timestamp") @Nullable String timestamp,
            @JsonProperty("url") @Nullable String url,
            @JsonProperty("author") @Nullable AuthorInfo author,
            @JsonProperty("added") @Nullable List<String> added,
            @JsonProperty("modified") @Nullable List<String> modified,
            @JsonProperty("removed") @Nullable List<String> removed) {
        /**
         * Returns the total number of changed files.
         */
        public int changedFilesCount() {
            int count = 0;
            if (added != null) count += added.size();
            if (modified != null) count += modified.size();
            if (removed != null) count += removed.size();
            return count;
        }
    }

    /**
     * Author data embedded in each commit.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorInfo(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("email") @Nullable String email) {}

    /**
     * Embedded project metadata from the push event payload.
     * <p>
     * GitLab includes comprehensive project info in every push event,
     * allowing us to upsert the repository without a separate API call.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectInfo(
            @JsonProperty("id") @Nullable Long id,
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("description") @Nullable String description,
            @JsonProperty("web_url") @Nullable String webUrl,
            @JsonProperty("namespace") @Nullable String namespace,
            @JsonProperty("path_with_namespace") @Nullable String pathWithNamespace,
            @JsonProperty("default_branch") @Nullable String defaultBranch,
            @JsonProperty("visibility_level") int visibilityLevel) {}

    /**
     * Returns whether this push is to the default branch.
     */
    public boolean isDefaultBranch() {
        if (project == null || project.defaultBranch() == null || ref == null) {
            return false;
        }
        return ref.equals("refs/heads/" + project.defaultBranch());
    }

    /** The zero SHA used by Git to represent a null/deleted ref. */
    private static final String NULL_SHA = "0000000000000000000000000000000000000000";

    /**
     * Returns whether this is a branch deletion event.
     * <p>
     * Git signals a ref deletion by setting the {@code after} SHA to all zeros.
     */
    public boolean isBranchDeletion() {
        return NULL_SHA.equals(after);
    }
}
