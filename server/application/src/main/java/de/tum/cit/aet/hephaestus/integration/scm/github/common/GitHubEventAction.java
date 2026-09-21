package de.tum.cit.aet.hephaestus.integration.scm.github.common;

/**
 * Sealed interface hierarchy for type-safe GitHub webhook actions.
 * Each event type has its own enum with only the valid actions for that event.
 */
public sealed interface GitHubEventAction {
    /** The raw action string from the webhook payload. */
    String value();

    // Repository Events

    enum Repository implements GitHubEventAction {
        CREATED,
        DELETED,
        EDITED,
        ARCHIVED,
        UNARCHIVED,
        RENAMED,
        TRANSFERRED,
        PRIVATIZED,
        PUBLICIZED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Repository fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "deleted" -> DELETED;
                case "edited" -> EDITED;
                case "archived" -> ARCHIVED;
                case "unarchived" -> UNARCHIVED;
                case "renamed" -> RENAMED;
                case "transferred" -> TRANSFERRED;
                case "privatized" -> PRIVATIZED;
                case "publicized" -> PUBLICIZED;
                default -> UNKNOWN;
            };
        }
    }

    // Issue Events

    enum Issue implements GitHubEventAction {
        OPENED,
        CLOSED,
        REOPENED,
        EDITED,
        DELETED,
        LABELED,
        UNLABELED,
        ASSIGNED,
        UNASSIGNED,
        MILESTONED,
        DEMILESTONED,
        PINNED,
        UNPINNED,
        LOCKED,
        UNLOCKED,
        TRANSFERRED,
        TYPED,
        UNTYPED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Issue fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "opened" -> OPENED;
                case "closed" -> CLOSED;
                case "reopened" -> REOPENED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                case "labeled" -> LABELED;
                case "unlabeled" -> UNLABELED;
                case "assigned" -> ASSIGNED;
                case "unassigned" -> UNASSIGNED;
                case "milestoned" -> MILESTONED;
                case "demilestoned" -> DEMILESTONED;
                case "pinned" -> PINNED;
                case "unpinned" -> UNPINNED;
                case "locked" -> LOCKED;
                case "unlocked" -> UNLOCKED;
                case "transferred" -> TRANSFERRED;
                case "typed" -> TYPED;
                case "untyped" -> UNTYPED;
                default -> UNKNOWN;
            };
        }
    }

    // Pull Request Events

    enum PullRequest implements GitHubEventAction {
        OPENED,
        CLOSED,
        REOPENED,
        EDITED,
        LABELED,
        UNLABELED,
        ASSIGNED,
        UNASSIGNED,
        MILESTONED,
        DEMILESTONED,
        READY_FOR_REVIEW,
        CONVERTED_TO_DRAFT,
        SYNCHRONIZE,
        REVIEW_REQUESTED,
        REVIEW_REQUEST_REMOVED,
        AUTO_MERGE_ENABLED,
        AUTO_MERGE_DISABLED,
        ENQUEUED,
        DEQUEUED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static PullRequest fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "opened" -> OPENED;
                case "closed" -> CLOSED;
                case "reopened" -> REOPENED;
                case "edited" -> EDITED;
                case "labeled" -> LABELED;
                case "unlabeled" -> UNLABELED;
                case "assigned" -> ASSIGNED;
                case "unassigned" -> UNASSIGNED;
                case "milestoned" -> MILESTONED;
                case "demilestoned" -> DEMILESTONED;
                case "ready_for_review" -> READY_FOR_REVIEW;
                case "converted_to_draft" -> CONVERTED_TO_DRAFT;
                case "synchronize" -> SYNCHRONIZE;
                case "review_requested" -> REVIEW_REQUESTED;
                case "review_request_removed" -> REVIEW_REQUEST_REMOVED;
                case "auto_merge_enabled" -> AUTO_MERGE_ENABLED;
                case "auto_merge_disabled" -> AUTO_MERGE_DISABLED;
                case "enqueued" -> ENQUEUED;
                case "dequeued" -> DEQUEUED;
                default -> UNKNOWN;
            };
        }
    }

    // Pull Request Review Events

    enum PullRequestReview implements GitHubEventAction {
        SUBMITTED,
        EDITED,
        DISMISSED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static PullRequestReview fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "submitted" -> SUBMITTED;
                case "edited" -> EDITED;
                case "dismissed" -> DISMISSED;
                default -> UNKNOWN;
            };
        }
    }

    // Pull Request Review Comment Events

    enum PullRequestReviewComment implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static PullRequestReviewComment fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Pull Request Review Thread Events

    enum PullRequestReviewThread implements GitHubEventAction {
        RESOLVED,
        UNRESOLVED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static PullRequestReviewThread fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "resolved" -> RESOLVED;
                case "unresolved" -> UNRESOLVED;
                default -> UNKNOWN;
            };
        }
    }

    // Issue Comment Events

    enum IssueComment implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static IssueComment fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Label Events

    enum Label implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Label fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Milestone Events

    enum Milestone implements GitHubEventAction {
        CREATED,
        CLOSED,
        OPENED,
        EDITED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Milestone fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "closed" -> CLOSED;
                case "opened" -> OPENED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Installation Events

    enum Installation implements GitHubEventAction {
        CREATED,
        DELETED,
        SUSPEND,
        UNSUSPEND,
        NEW_PERMISSIONS_ACCEPTED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Installation fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "deleted" -> DELETED;
                case "suspend" -> SUSPEND;
                case "unsuspend" -> UNSUSPEND;
                case "new_permissions_accepted" -> NEW_PERMISSIONS_ACCEPTED;
                default -> UNKNOWN;
            };
        }
    }

    // Installation Repositories Events

    enum InstallationRepositories implements GitHubEventAction {
        ADDED,
        REMOVED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static InstallationRepositories fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "added" -> ADDED;
                case "removed" -> REMOVED;
                default -> UNKNOWN;
            };
        }
    }

    // Installation Target Events

    enum InstallationTarget implements GitHubEventAction {
        RENAMED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static InstallationTarget fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "renamed" -> RENAMED;
                default -> UNKNOWN;
            };
        }
    }

    // Organization Events

    enum Organization implements GitHubEventAction {
        MEMBER_ADDED,
        MEMBER_REMOVED,
        RENAMED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Organization fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "member_added" -> MEMBER_ADDED;
                case "member_removed" -> MEMBER_REMOVED;
                case "renamed" -> RENAMED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Team Events

    enum Team implements GitHubEventAction {
        CREATED,
        DELETED,
        EDITED,
        ADDED_TO_REPOSITORY,
        REMOVED_FROM_REPOSITORY,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Team fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "deleted" -> DELETED;
                case "edited" -> EDITED;
                case "added_to_repository" -> ADDED_TO_REPOSITORY;
                case "removed_from_repository" -> REMOVED_FROM_REPOSITORY;
                default -> UNKNOWN;
            };
        }
    }

    // Membership Events (team member changes)

    enum Membership implements GitHubEventAction {
        ADDED,
        REMOVED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Membership fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "added" -> ADDED;
                case "removed" -> REMOVED;
                default -> UNKNOWN;
            };
        }
    }

    // Member Events (repository collaborator changes)

    enum Member implements GitHubEventAction {
        ADDED,
        REMOVED,
        EDITED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Member fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "added" -> ADDED;
                case "removed" -> REMOVED;
                case "edited" -> EDITED;
                default -> UNKNOWN;
            };
        }
    }

    // Sub-Issue Events

    enum SubIssue implements GitHubEventAction {
        SUB_ISSUE_ADDED,
        SUB_ISSUE_REMOVED,
        PARENT_ISSUE_ADDED,
        PARENT_ISSUE_REMOVED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static SubIssue fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "sub_issue_added" -> SUB_ISSUE_ADDED;
                case "sub_issue_removed" -> SUB_ISSUE_REMOVED;
                case "parent_issue_added" -> PARENT_ISSUE_ADDED;
                case "parent_issue_removed" -> PARENT_ISSUE_REMOVED;
                default -> UNKNOWN;
            };
        }

        public boolean isAdded() {
            return this == SUB_ISSUE_ADDED || this == PARENT_ISSUE_ADDED;
        }

        public boolean isRemoved() {
            return this == SUB_ISSUE_REMOVED || this == PARENT_ISSUE_REMOVED;
        }
    }

    // Issue Dependency Events

    /**
     * Actions for the issue_dependencies webhook event.
     * <p>
     * <b>Note:</b> As of January 2026, the issue_dependencies webhook cannot be subscribed to
     * via GitHub App settings. This enum is defined based on documentation and will become
     * active when GitHub enables webhook subscription for this event type.
     *
     * @see GitHubEventType#ISSUE_DEPENDENCIES
     */
    enum IssueDependency implements GitHubEventAction {
        ADDED,
        REMOVED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static IssueDependency fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "added" -> ADDED;
                case "removed" -> REMOVED;
                default -> UNKNOWN;
            };
        }
    }

    // GitHub Projects V2 Events

    /**
     * Actions for the projects_v2 webhook event.
     *
     * @see GitHubEventType#PROJECTS_V2
     */
    enum ProjectV2 implements GitHubEventAction {
        CREATED,
        EDITED,
        CLOSED,
        REOPENED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static ProjectV2 fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "closed" -> CLOSED;
                case "reopened" -> REOPENED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // GitHub Projects V2 Item Events

    /**
     * Actions for the projects_v2_item webhook event.
     *
     * @see GitHubEventType#PROJECTS_V2_ITEM
     */
    enum ProjectV2Item implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        ARCHIVED,
        RESTORED,
        CONVERTED,
        REORDERED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static ProjectV2Item fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                case "archived" -> ARCHIVED;
                case "restored" -> RESTORED;
                case "converted" -> CONVERTED;
                case "reordered" -> REORDERED;
                default -> UNKNOWN;
            };
        }
    }

    // GitHub Projects V2 Status Update Events

    /**
     * Actions for the projects_v2_status_update webhook event.
     *
     * @see GitHubEventType#PROJECTS_V2_STATUS_UPDATE
     */
    enum ProjectV2StatusUpdate implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static ProjectV2StatusUpdate fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Discussion Events

    enum Discussion implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        PINNED,
        UNPINNED,
        LOCKED,
        UNLOCKED,
        TRANSFERRED,
        CATEGORY_CHANGED,
        ANSWERED,
        UNANSWERED,
        LABELED,
        UNLABELED,
        CLOSED,
        REOPENED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Discussion fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                case "pinned" -> PINNED;
                case "unpinned" -> UNPINNED;
                case "locked" -> LOCKED;
                case "unlocked" -> UNLOCKED;
                case "transferred" -> TRANSFERRED;
                case "category_changed" -> CATEGORY_CHANGED;
                case "answered" -> ANSWERED;
                case "unanswered" -> UNANSWERED;
                case "labeled" -> LABELED;
                case "unlabeled" -> UNLABELED;
                case "closed" -> CLOSED;
                case "reopened" -> REOPENED;
                default -> UNKNOWN;
            };
        }
    }

    // Discussion Comment Events

    enum DiscussionComment implements GitHubEventAction {
        CREATED,
        EDITED,
        DELETED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static DiscussionComment fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "created" -> CREATED;
                case "edited" -> EDITED;
                case "deleted" -> DELETED;
                default -> UNKNOWN;
            };
        }
    }

    // Push Events

    enum CheckSuite implements GitHubEventAction {
        COMPLETED,
        REQUESTED,
        REREQUESTED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static CheckSuite fromString(String action) {
            if (action == null || action.isBlank()) return UNKNOWN;
            return switch (action.toLowerCase()) {
                case "completed" -> COMPLETED;
                case "requested" -> REQUESTED;
                case "rerequested" -> REREQUESTED;
                default -> UNKNOWN;
            };
        }
    }

    /** A commit status event has no action: the status itself is the event. */
    enum Status implements GitHubEventAction {
        REPORTED;

        @Override
        public String value() {
            return name().toLowerCase();
        }
    }

    /**
     * Push events don't have a traditional "action" field. The event itself is the action.
     * We use a synthetic PUSHED action to conform to the GitHubWebhookEvent interface.
     */
    enum Push implements GitHubEventAction {
        PUSHED,
        UNKNOWN;

        @Override
        public String value() {
            return name().toLowerCase();
        }

        public static Push fromString(String action) {
            // Push events don't have an action field, so any value maps to PUSHED
            return PUSHED;
        }
    }
}
