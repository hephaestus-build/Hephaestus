package de.tum.cit.aet.hephaestus.integration.scm.events;

import java.time.Instant;
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
     * The identity of {@code event}. A close or a merge is the stored moment of it, which every path stores alike; any
     * other event carries the identity its producer gave it, or none.
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
            default -> event.context().sourceKey();
        };
    }
}
