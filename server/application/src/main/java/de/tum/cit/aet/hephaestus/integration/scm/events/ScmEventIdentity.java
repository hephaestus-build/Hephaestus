package de.tum.cit.aet.hephaestus.integration.scm.events;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The identity of one provider event on an item: its source key. The occasion and the activity entry of an event share
 * it, and the webhook, a late or repeated delivery and the sync derive the same key for the same change. Thus each of
 * them records the change once (sync-lifecycle.md § A redelivered or older payload changes nothing).
 */
public final class ScmEventIdentity {

    private ScmEventIdentity() {}

    /** A change of state, such as ready, draft or reopened, at the moment the provider dates it. */
    public static @Nullable String transition(long itemId, String name, @Nullable Instant at) {
        return at == null ? null : itemId + ":" + name + ":" + at;
    }

    /**
     * A push: the move from one head commit to the next. A force-push back to an earlier head is a new move; a
     * redelivery of the same push is the same one.
     */
    public static String push(long itemId, @Nullable String before, String after) {
        return itemId + ":push:" + (before == null ? "" : before) + ".." + after;
    }

    /**
     * Checks of a head that settled with one set of runs. A re-run ends at another time, so it is a new settling, also
     * when its results match an earlier one (red, green, red). A repeated, reordered or lagging read of runs that already
     * ended is the same settling.
     */
    public static String checks(long itemId, String sha, String runsDigest) {
        return itemId + ":checks:" + sha + ":" + runsDigest;
    }

    /**
     * An edit of a comment: the moment the provider dates it and what it says. Two edits in one second are two edits;
     * a redelivery of one is the same edit.
     */
    public static @Nullable String edit(long itemId, @Nullable Instant at, @Nullable String body) {
        return at == null ? null : itemId + ":edited:" + at + ":" + Integer.toHexString(Objects.hashCode(body));
    }

    /**
     * The identity of {@code event}. A close, a merge, an edit, a dismissal or a resolution is identified by what the
     * event stores, which every path stores alike; any other event carries the identity its producer gave it, or none.
     */
    public static @Nullable String of(ScmDomainEvent.ContextualEvent event) {
        return switch (event) {
            case ScmDomainEvent.PullRequestMerged merged ->
                transition(
                        merged.pullRequest().id(),
                        "merged",
                        merged.pullRequest().mergedAt());
            case ScmDomainEvent.PullRequestClosed closed ->
                closed.wasMerged()
                        ? transition(
                                closed.pullRequest().id(),
                                "merged",
                                closed.pullRequest().mergedAt())
                        : transition(
                                closed.pullRequest().id(),
                                "closed",
                                closed.pullRequest().closedAt());
            case ScmDomainEvent.IssueClosed closed ->
                transition(closed.issue().id(), "closed", closed.issue().closedAt());
            case ScmDomainEvent.CommentUpdated updated ->
                edit(
                        updated.comment().id(),
                        updated.comment().updatedAt(),
                        updated.comment().body());
            case ScmDomainEvent.ReviewCommentEdited edited ->
                edit(
                        edited.comment().id(),
                        edited.comment().updatedAt(),
                        edited.comment().body());
            case ScmDomainEvent.DiscussionCommentEdited edited ->
                edit(
                        edited.comment().id(),
                        edited.comment().updatedAt(),
                        edited.comment().body());
            // A dismissal is the moment the review stores for it. GitLab reuses one review per approver, so a second
            // withdrawal of a renewed approval is a second dismissal.
            case ScmDomainEvent.ReviewDismissed dismissed ->
                transition(
                        dismissed.review().id(), "dismissed", dismissed.review().updatedAt());
            case ScmDomainEvent.ReviewThreadResolved resolved ->
                transition(resolved.thread().id(), "resolved", resolved.thread().resolvedAt());
            default -> event.context().sourceKey();
        };
    }
}
