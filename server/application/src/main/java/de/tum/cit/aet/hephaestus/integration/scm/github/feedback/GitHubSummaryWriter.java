package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ProviderMentions;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@OutboundEgressGateway
public class GitHubSummaryWriter implements SummaryWriter {

    private static final Logger log = LoggerFactory.getLogger(GitHubSummaryWriter.class);

    private static final int EXISTING_SUMMARY_SEARCH_PAGE_SIZE = 100;

    // An unscanned tail is UNKNOWN; recovery must read again rather than repost.
    private static final int EXISTING_SUMMARY_SEARCH_PAGE_BUDGET = 3;

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final GitHubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;

    public GitHubSummaryWriter(
            GitHubGraphQlClientProvider gitHubProvider,
            GitHubPrNodeIdResolver prNodeIdResolver,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets) {
        this.gitHubProvider = gitHubProvider;
        this.prNodeIdResolver = prNodeIdResolver;
        this.egressGuard = egressGuard;
        this.targets = targets;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public SummaryHandle postSummary(FeedbackTarget target, SummaryContent content) {
        long scopeId = target.integration().workspaceId();
        String subjectNodeId;
        try {
            subjectNodeId = resolveSubject(scopeId, targets.subject(target, IntegrationKind.GITHUB));
        } catch (RuntimeException e) {
            throw new FeedbackNotSentException("GitHub comment not sent: " + e.getMessage(), e);
        }
        SummaryHandle handle = createComment(scopeId, subjectNodeId, ProviderMentions.escape(content.externalBody()));
        log.info(
                "Posted GitHub comment: workspaceId={}, subjectNodeId={}, commentId={}",
                scopeId,
                subjectNodeId,
                handle.externalRef());
        return handle;
    }

    private String resolveSubject(long scopeId, String subject) {
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("The GitHub rate limit is critical for scope " + scopeId);
        }
        if (isIssueSubject(subject)) {
            IssueCoordinates issue = parseIssueSubjectExternalId(subject);
            return prNodeIdResolver.resolveIssue(scopeId, issue.owner(), issue.name(), issue.number());
        }
        PrCoordinates pr = parseSubjectExternalId(subject);
        return prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());
    }

    /**
     * Edit an already-posted summary comment in place via the {@code updateIssueComment} mutation
     * (ADR 0021 re-review UX). GitHub addresses both PR-level and issue comments as {@code
     * IssueComment}s, so the same mutation edits either, and the comment's own node id ({@code
     * externalId}) addresses it directly.
     *
     * <p>Mirrors the GitLab channel's typed outcome: rate-limit / transport / unknown errors are
     * {@code TRANSIENT} (keep the prior summary, do not re-post); a confirmed not-found comment is
     * {@code GONE} (re-post); only a blank external id — a data bug — throws.
     */
    @Override
    public CopyUpdateReceipt updateSummary(FeedbackTarget target, SummaryHandle existing, SummaryContent content) {
        String externalId = existing.externalRef();
        targets.subject(target, kind());
        long scopeId = target.integration().workspaceId();
        if (externalId == null || externalId.isBlank()) {
            throw new FeedbackDeliveryException(
                    "Cannot edit a GitHub comment in place. The external comment id is missing.");
        }
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            return CopyUpdateReceipt.transientFailure(
                    content.copyId(), "The GitHub rate limit is critical for scope " + scopeId);
        }

        String body = ProviderMentions.escape(content.externalBody());
        ClientGraphQlResponse response;
        try {
            egressGuard.requireDeliveryAllowed("github.update-summary");
            response = gitHubProvider
                    .forScope(scopeId)
                    .documentName("UpdateIssueComment")
                    .variable("id", externalId)
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (RuntimeException e) {
            return CopyUpdateReceipt.transientFailure(
                    content.copyId(), "updateIssueComment transport error: " + e.getMessage());
        }

        if (response == null) {
            return CopyUpdateReceipt.transientFailure(
                    content.copyId(), "The updateIssueComment mutation returned no response");
        }
        gitHubProvider.trackRateLimit(scopeId, response);

        // A DELETED comment surfaces as a top-level GraphQL error (the node id resolves to
        // nothing). A NOT_FOUND
        // is GONE (re-post); any other error is TRANSIENT (keep the prior summary, do not
        // double-post).
        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            List<String> errors = response.getErrors().stream()
                    .map(e -> e.getMessage())
                    .filter(Objects::nonNull)
                    .toList();
            return looksGone(errors)
                    ? CopyUpdateReceipt.gone(content.copyId(), "GitHub updateIssueComment: " + errors)
                    : CopyUpdateReceipt.transientFailure(
                            content.copyId(), "GitHub updateIssueComment failed: " + errors);
        }

        String commentNodeId =
                response.field("updateIssueComment.issueComment.id").getValue();
        if (commentNodeId == null) {
            // Neither confirmed gone nor returned an id — transient, don't double-post.
            return CopyUpdateReceipt.transientFailure(content.copyId(), "No comment id in updateIssueComment response");
        }
        log.info("Edited GitHub comment in place: workspaceId={}, commentId={}", scopeId, commentNodeId);
        return CopyUpdateReceipt.edited(
                content.copyId(),
                new SummaryHandle(
                        commentNodeId,
                        response.field("updateIssueComment.issueComment.url").getValue()));
    }

    /** Search newest-first; a marker without the viewer's exact posted copy cannot establish success or absence. */
    @Override
    public SummaryLookup findExistingSummary(FeedbackTarget target, SummaryContent expected) {
        String marker = expected.writeMarker();
        if (marker == null || marker.isBlank()) {
            return SummaryLookup.unknown();
        }
        String expectedBody = ProviderMentions.escape(expected.externalBody());
        long scopeId = target.integration().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            return SummaryLookup.unknown();
        }
        String subject = targets.subject(target, IntegrationKind.GITHUB);
        String documentName;
        String owner;
        String name;
        int number;
        String commentsPath;
        if (isIssueSubject(subject)) {
            IssueCoordinates issue = parseIssueSubjectExternalId(subject);
            documentName = "GetIssueCommentsNewest";
            owner = issue.owner();
            name = issue.name();
            number = issue.number();
            commentsPath = "repository.issue.comments";
        } else {
            PrCoordinates pr = parseSubjectExternalId(subject);
            documentName = "GetPullRequestCommentsNewest";
            owner = pr.owner();
            name = pr.name();
            number = pr.number();
            commentsPath = "repository.pullRequest.comments";
        }

        String cursor = null;
        boolean conflict = false;
        for (int page = 0; page < EXISTING_SUMMARY_SEARCH_PAGE_BUDGET; page++) {
            try {
                ClientGraphQlResponse response = gitHubProvider
                        .forScope(scopeId)
                        .documentName(documentName)
                        .variable("owner", owner)
                        .variable("name", name)
                        .variable("number", number)
                        .variable("last", EXISTING_SUMMARY_SEARCH_PAGE_SIZE)
                        .variable("before", cursor)
                        .execute()
                        .block(GRAPHQL_TIMEOUT);
                if (response == null
                        || (response.getErrors() != null
                                && !response.getErrors().isEmpty())) {
                    return SummaryLookup.unknown();
                }
                gitHubProvider.trackRateLimit(scopeId, response);

                Map<String, Object> connection = response.field(commentsPath).getValue();
                if (connection == null
                        || !(connection.get("nodes") instanceof List<?> nodes)
                        || !(connection.get("pageInfo") instanceof Map<?, ?> pageInfo)) {
                    return SummaryLookup.unknown();
                }
                for (Object node : nodes) {
                    if (!(node instanceof Map<?, ?> comment) || !(comment.get("body") instanceof String body)) {
                        conflict = true;
                        continue;
                    }
                    if (!body.contains(marker)) continue;
                    if (Boolean.TRUE.equals(comment.get("viewerDidAuthor"))
                            && body.equals(expectedBody)
                            && comment.get("id") instanceof String id
                            && !id.isBlank()) {
                        return SummaryLookup.found(
                                new SummaryHandle(id, comment.get("url") instanceof String url ? url : null));
                    }
                    conflict = true;
                }

                Object hasPreviousPage = pageInfo.get("hasPreviousPage");
                if (Boolean.FALSE.equals(hasPreviousPage)) {
                    return conflict ? SummaryLookup.unknown() : SummaryLookup.absent();
                }
                if (!Boolean.TRUE.equals(hasPreviousPage)
                        || !(pageInfo.get("startCursor") instanceof String next)
                        || next.isBlank()
                        || next.equals(cursor)) {
                    return SummaryLookup.unknown();
                }
                cursor = next;
            } catch (RuntimeException e) {
                log.debug(
                        "Existing-summary dedup lookup failed (treated as unknown, not absent): scopeId={}, error={}",
                        scopeId,
                        e.getClass().getSimpleName());
                return SummaryLookup.unknown();
            }
        }
        return SummaryLookup.unknown();
    }

    // GitHub reports deleted comments through free-text top-level errors.
    static boolean looksGone(List<String> errors) {
        return errors.stream()
                .filter(Objects::nonNull)
                .map(e -> e.toLowerCase(Locale.ROOT))
                .anyMatch(e -> e.contains("not found")
                        || e.contains("does not exist")
                        || e.contains("could not be found")
                        || e.contains("couldn't be found")
                        || e.contains("could not resolve"));
    }

    static boolean isIssueSubject(String subjectExternalId) {
        return subjectExternalId != null && subjectExternalId.matches(".+/issues/\\d+");
    }

    static IssueCoordinates parseIssueSubjectExternalId(String subjectExternalId) {
        if (subjectExternalId == null || !subjectExternalId.matches(".+/issues/\\d+")) {
            throw new FeedbackDeliveryException(
                    "Invalid GitHub issue subjectExternalId (expected owner/repo/issues/number): " + subjectExternalId);
        }
        int marker = subjectExternalId.lastIndexOf("/issues/");
        String repoFullName = subjectExternalId.substring(0, marker);
        int number = Integer.parseInt(subjectExternalId.substring(marker + "/issues/".length()));
        String[] parts = repoFullName.split("/", 2);
        if (parts.length != 2) {
            throw new FeedbackDeliveryException(
                    "Invalid GitHub issue subjectExternalId (expected owner/repo/issues/number): " + subjectExternalId);
        }
        return new IssueCoordinates(parts[0], parts[1], number);
    }

    record IssueCoordinates(String owner, String name, int number) {}

    private SummaryHandle createComment(long scopeId, String subjectId, String body) {
        egressGuard.requireDeliveryAllowed("github.post-summary");
        ClientGraphQlResponse response = gitHubProvider
                .forScope(scopeId)
                .documentName("AddPullRequestComment")
                .variable("subjectId", subjectId)
                .variable("body", body)
                .execute()
                .block(GRAPHQL_TIMEOUT);

        if (response == null) {
            throw new FeedbackDeliveryException("The AddPullRequestComment mutation returned no response");
        }
        gitHubProvider.trackRateLimit(scopeId, response);

        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            throw new FeedbackDeliveryException("GitHub addComment failed: " + response.getErrors());
        }

        String commentNodeId = response.field("addComment.commentEdge.node.id").getValue();
        if (commentNodeId == null) {
            throw new FeedbackDeliveryException("No comment ID in AddPullRequestComment response");
        }
        return new SummaryHandle(
                commentNodeId, response.field("addComment.commentEdge.node.url").getValue());
    }

    static PrCoordinates parseSubjectExternalId(String subjectExternalId) {
        if (subjectExternalId == null || subjectExternalId.isBlank()) {
            throw new FeedbackDeliveryException("subjectExternalId is required for GitHub PR feedback");
        }
        int hashIdx = subjectExternalId.lastIndexOf('#');
        if (hashIdx <= 0 || hashIdx == subjectExternalId.length() - 1) {
            throw new FeedbackDeliveryException(
                    "Invalid GitHub PR subjectExternalId (expected owner/repo#number): " + subjectExternalId);
        }
        String repoFullName = subjectExternalId.substring(0, hashIdx);
        String numberPart = subjectExternalId.substring(hashIdx + 1);
        String[] parts = repoFullName.split("/", 2);
        if (parts.length != 2) {
            throw new FeedbackDeliveryException(
                    "Invalid GitHub PR subjectExternalId (expected owner/repo#number): " + subjectExternalId);
        }
        int number;
        try {
            number = Integer.parseInt(numberPart);
        } catch (NumberFormatException e) {
            throw new FeedbackDeliveryException(
                    "Invalid GitHub PR subjectExternalId. The number must be an integer: " + subjectExternalId, e);
        }
        return new PrCoordinates(parts[0], parts[1], number);
    }

    record PrCoordinates(String owner, String name, int number) {}
}
