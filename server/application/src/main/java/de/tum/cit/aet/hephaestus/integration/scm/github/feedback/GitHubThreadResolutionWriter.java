package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadResolutionWriter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Resolves and reopens the GitHub review thread that an owned line note starts. */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
public class GitHubThreadResolutionWriter implements ThreadResolutionWriter {

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;

    GitHubThreadResolutionWriter(
            GitHubGraphQlClientProvider gitHubProvider,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets) {
        this.gitHubProvider = gitHubProvider;
        this.egressGuard = egressGuard;
        this.targets = targets;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    // An installation token's viewer is the app's bot, whose id a webhook names as the sender of the bot's own changes.
    @Override
    public @Nullable String actor(FeedbackTarget target) {
        try {
            var response = gitHubProvider
                    .forScope(target.item().workspaceId())
                    .document("query FeedbackThreadActor{viewer{databaseId}}")
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (response == null || !response.getErrors().isEmpty()) return null;
            Object id = response.field("viewer.databaseId").getValue();
            return id instanceof Number number && number.longValue() > 0 ? Long.toString(number.longValue()) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Override
    public ThreadChange resolveThread(FeedbackTarget target, CopyReceipt copy, boolean resolved, String actor) {
        targets.subject(target, kind());
        long workspaceId = target.item().workspaceId();
        if (gitHubProvider.isRateLimitCritical(workspaceId))
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "The GitHub rate limit is critical");
        String thread = copy.threadRef();
        String note = copy.externalRef();
        if (thread == null || thread.isBlank() || note == null || note.isBlank())
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.NOT_OWNED, "Copy has no provider thread");
        try {
            var read = gitHubProvider
                    .forScope(workspaceId)
                    .document(
                            "query ReadFeedbackThread($id:ID!){node(id:$id){__typename ... on PullRequestReviewThread{id isResolved resolvedBy{databaseId} comments(first:100){totalCount nodes{id viewerDidAuthor}}}}}")
                    .variable("id", thread)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (read == null || !read.getErrors().isEmpty())
                return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.TRANSIENT, "The thread could not be read");
            Object node = read.field("node").getValue();
            if (node == null)
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.GONE, "The provider thread no longer exists");
            if (!(node instanceof Map<?, ?> found)
                    || !thread.equals(found.get("id"))
                    || !(found.get("isResolved") instanceof Boolean current)
                    || !startsWithOwn(found, note))
                return ThreadChange.of(
                        copy.copyId(),
                        ThreadChange.Outcome.NOT_OWNED,
                        "The thread no longer starts with the owned note");
            // A bot is no GitHub User, so a resolve by the app's bot names no resolver; a person always has one.
            boolean resolvedByOther = current
                    && found.get("resolvedBy") instanceof Map<?, ?> resolver
                    && !actor.equals(String.valueOf(resolver.get("databaseId")));
            if (resolvedByOther)
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TAKEN, "Another account resolved the thread");
            if (current == resolved) return ThreadChange.done(copy.copyId());
            if (resolved && repliedByOther(found))
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.REPLIED, "A person replied after the owned note");
            if (!egressGuard.deliveryAllowed(
                    resolved ? "github.resolve-feedback-thread" : "github.reopen-feedback-thread"))
                return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "Silent Mode holds the change");
            String field = resolved ? "resolveReviewThread" : "unresolveReviewThread";
            var written = gitHubProvider
                    .forScope(workspaceId)
                    .document(
                            resolved
                                    ? "mutation ResolveFeedbackThread($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{id isResolved}}}"
                                    : "mutation ReopenFeedbackThread($id:ID!){unresolveReviewThread(input:{threadId:$id}){thread{id isResolved}}}")
                    .variable("id", thread)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (written == null || !written.getErrors().isEmpty())
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider did not confirm the thread state");
            String id = written.field(field + ".thread.id").getValue();
            Boolean state = written.field(field + ".thread.isResolved").getValue();
            return thread.equals(id) && Objects.equals(state, resolved)
                    ? ThreadChange.done(copy.copyId())
                    : ThreadChange.of(
                            copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider did not confirm the thread state");
        } catch (OutboundEgressSuppressedException silenced) {
            // Silent Mode engaged between the check and the write: nothing was written.
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "Silent Mode holds the change");
        } catch (RuntimeException e) {
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider thread change failed");
        }
    }

    /**
     * Whether a comment after the owned note is not the token account's: a person replied, also when a later comment is
     * the account's own. A thread longer than one read counts as replied, because the read cannot prove otherwise.
     */
    private static boolean repliedByOther(Map<?, ?> thread) {
        if (!(thread.get("comments") instanceof Map<?, ?> comments)
                || !(comments.get("nodes") instanceof List<?> nodes)
                || !(comments.get("totalCount") instanceof Number total)
                || total.intValue() != nodes.size()) return true;
        for (int index = 1; index < nodes.size(); index++)
            if (!(nodes.get(index) instanceof Map<?, ?> comment)
                    || !Boolean.TRUE.equals(comment.get("viewerDidAuthor"))) return true;
        return false;
    }

    private static boolean startsWithOwn(Map<?, ?> thread, String note) {
        return thread.get("comments") instanceof Map<?, ?> comments
                && comments.get("nodes") instanceof List<?> nodes
                && !nodes.isEmpty()
                && nodes.getFirst() instanceof Map<?, ?> first
                && note.equals(first.get("id"))
                && Boolean.TRUE.equals(first.get("viewerDidAuthor"));
    }
}
