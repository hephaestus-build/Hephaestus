package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GithubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link SummaryChannel}. Posts a single PR-level comment via
 * the {@code AddPullRequestComment} GraphQL mutation.
 *
 * <p>{@link SummaryChannel.FeedbackTarget#subjectExternalId} convention for GitHub is
 * {@code "owner/repo#prNumber"} — the channel parses, resolves the PR node ID
 * via {@link GithubPrNodeIdResolver}, then issues the mutation. The returned
 * {@link SummaryChannel.SummaryHandle} carries the comment node ID so
 * the feedback ledger records it ({@code FeedbackPlacement.external_ref}) for edit-in-place on subsequent runs.
 */
@Component
@OutboundEgressGateway
public class GithubSummaryChannel implements SummaryChannel {

    private static final Logger log = LoggerFactory.getLogger(GithubSummaryChannel.class);

    /** GitHub caps a connection page at 100. */
    private static final int EXISTING_SUMMARY_SEARCH_PAGE_SIZE = 100;

    /** Our cap, not GitHub's: an unscanned tail answers {@code UNKNOWN}, so recovery retries rather than reposts. */
    private static final int EXISTING_SUMMARY_SEARCH_PAGE_BUDGET = 3;

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final GithubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;

    public GithubSummaryChannel(
            GitHubGraphQlClientProvider gitHubProvider,
            GithubPrNodeIdResolver prNodeIdResolver,
            OutboundEgressGuard egressGuard) {
        this.gitHubProvider = gitHubProvider;
        this.prNodeIdResolver = prNodeIdResolver;
        this.egressGuard = egressGuard;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public String formatPullRequestSubjectId(String repoFullName, int prNumber) {
        return requireOwnerRepo(repoFullName) + "#" + prNumber;
    }

    @Override
    public String formatIssueSubjectId(String repoFullName, int issueNumber) {
        // GitHub addresses PRs AND issues identically as owner/repo#number, so the channel cannot tell an
        // issue from a PR by a shared "#" subject (unlike GitLab's '!' vs '#'). The internal subject is
        // ours to define, so issues get a distinct "owner/repo/issues/N" form that postSummary routes to
        // the issue node-id resolver — otherwise an issue would hit the PR resolver and fail to deliver.
        return requireOwnerRepo(repoFullName) + "/issues/" + issueNumber;
    }

    private static String requireOwnerRepo(String repoFullName) {
        if (repoFullName == null || repoFullName.isBlank()) {
            throw new IllegalArgumentException("repoFullName is required");
        }
        // Require exactly two NON-BLANK segments — "owner/", "/repo" and "owner/repo/x" must all fail fast
        // rather than yield a malformed "owner/#42" / "owner//issues/42" subject downstream.
        String[] parts = repoFullName.split("/", 3);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("GitHub repoFullName must be 'owner/repo': " + repoFullName);
        }
        return repoFullName;
    }

    @Override
    public SummaryHandle postSummary(FeedbackTarget target, FeedbackContent content) {
        long scopeId = target.ref().workspaceId();
        String subjectNodeId;
        try {
            subjectNodeId = resolveSubject(scopeId, target.subjectExternalId());
        } catch (RuntimeException e) {
            throw new FeedbackNotSentException("GitHub comment not sent: " + e.getMessage(), e);
        }
        SummaryHandle handle = createComment(scopeId, subjectNodeId, content.externalBody());
        log.info(
                "Posted GitHub comment: workspaceId={}, subjectNodeId={}, commentId={}",
                scopeId,
                subjectNodeId,
                handle.externalId());
        return handle;
    }

    private String resolveSubject(long scopeId, String subject) {
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("GitHub rate limit critical for scope " + scopeId);
        }
        if (isIssueSubject(subject)) {
            IssueCoordinates issue = parseIssueSubjectExternalId(subject);
            return prNodeIdResolver.resolveIssue(scopeId, issue.owner(), issue.name(), issue.number());
        }
        PrCoordinates pr = parseSubjectExternalId(subject);
        return prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());
    }

    /**
     * Edit an already-posted summary comment in place via the {@code updateIssueComment} mutation (ADR 0021
     * re-review UX). GitHub addresses both PR-level and issue comments as {@code IssueComment}s, so the same
     * mutation edits either, and the comment's own node id ({@code externalId}) addresses it directly.
     *
     * <p>Mirrors the GitLab channel's typed outcome: rate-limit / transport / unknown errors are
     * {@code TRANSIENT} (keep the prior summary, do not re-post); a confirmed not-found comment is
     * {@code GONE} (re-post); only a blank external id — a data bug — throws.
     */
    @Override
    public UpdateOutcome updateSummary(FeedbackTarget target, String externalId, FeedbackContent content) {
        long scopeId = target.ref().workspaceId();
        if (externalId == null || externalId.isBlank()) {
            throw new FeedbackDeliveryException(
                    "Cannot edit a GitHub comment in place: external comment id is missing");
        }
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            return UpdateOutcome.transientFailure("GitHub rate limit critical for scope " + scopeId);
        }

        ClientGraphQlResponse response;
        try {
            egressGuard.requireDeliveryAllowed("github.update-summary");
            response = gitHubProvider
                    .forScope(scopeId)
                    .documentName("UpdateIssueComment")
                    .variable("id", externalId)
                    .variable("body", content.externalBody())
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (RuntimeException e) {
            return UpdateOutcome.transientFailure("updateIssueComment transport error: " + e.getMessage());
        }

        if (response == null) {
            return UpdateOutcome.transientFailure("Null response from updateIssueComment mutation");
        }
        gitHubProvider.trackRateLimit(scopeId, response);

        // A DELETED comment surfaces as a top-level GraphQL error (the node id resolves to nothing). A NOT_FOUND
        // is GONE (re-post); any other error is TRANSIENT (keep the prior summary, do not double-post).
        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            List<String> errors = response.getErrors().stream()
                    .map(e -> e.getMessage())
                    .filter(Objects::nonNull)
                    .toList();
            return looksGone(errors)
                    ? UpdateOutcome.gone("GitHub updateIssueComment: " + errors)
                    : UpdateOutcome.transientFailure("GitHub updateIssueComment failed: " + errors);
        }

        String commentNodeId =
                response.field("updateIssueComment.issueComment.id").getValue();
        if (commentNodeId == null) {
            // Neither confirmed gone nor returned an id — transient, don't double-post.
            return UpdateOutcome.transientFailure("No comment id in updateIssueComment response");
        }
        log.info("Edited GitHub comment in place: workspaceId={}, commentId={}", scopeId, commentNodeId);
        return UpdateOutcome.edited(new SummaryHandle(
                commentNodeId,
                response.field("updateIssueComment.issueComment.url").getValue()));
    }

    /**
     * Scans this PR/issue's comments for one whose body contains {@code marker}, walking the connection
     * backwards from its newest end — the summary a crashed delivery already posted is the newest comment.
     */
    @Override
    public ExistingSummaryLookup findExistingSummary(FeedbackTarget target, String marker) {
        if (marker == null || marker.isBlank()) {
            return ExistingSummaryLookup.unknown();
        }
        long scopeId = target.ref().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            return ExistingSummaryLookup.unknown();
        }
        String subject = target.subjectExternalId();
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
                    return ExistingSummaryLookup.unknown();
                }
                gitHubProvider.trackRateLimit(scopeId, response);

                GHIssueCommentConnection connection =
                        response.field(commentsPath).toEntity(GHIssueCommentConnection.class);
                if (connection == null) {
                    return ExistingSummaryLookup.unknown();
                }
                if (connection.getNodes() != null) {
                    for (GHIssueComment node : connection.getNodes()) {
                        if (node.getBody() != null && node.getBody().contains(marker) && node.getId() != null) {
                            return ExistingSummaryLookup.found(new SummaryHandle(
                                    node.getId(),
                                    node.getUrl() == null ? null : node.getUrl().toString()));
                        }
                    }
                }

                GHPageInfo pageInfo = connection.getPageInfo();
                boolean hasPreviousPage = pageInfo != null && pageInfo.getHasPreviousPage();
                if (!hasPreviousPage) {
                    return ExistingSummaryLookup.absent();
                }
                cursor = pageInfo.getStartCursor();
                if (cursor == null || cursor.isBlank()) {
                    return ExistingSummaryLookup.unknown();
                }
            } catch (RuntimeException e) {
                log.debug(
                        "Existing-summary dedup lookup failed (treated as unknown, not absent): scopeId={}, error={}",
                        scopeId,
                        e.getMessage());
                return ExistingSummaryLookup.unknown();
            }
        }
        return ExistingSummaryLookup.unknown();
    }

    /** Conservative NOT_FOUND heuristic: GitHub signals a deleted comment via a free-text top-level error. */
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

    /** A GitHub issue subject is the distinct {@code owner/repo/issues/N} form (see formatIssueSubjectId). */
    static boolean isIssueSubject(String subjectExternalId) {
        return subjectExternalId != null && subjectExternalId.matches(".+/issues/\\d+");
    }

    /** Splits {@code "owner/repo/issues/42"} into the components the issue node-id query needs. */
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
            throw new FeedbackDeliveryException("Null response from AddPullRequestComment mutation");
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

    /**
     * Splits {@code "owner/repo#42"} (GitHub PR external-id convention used by the
     * agent layer) into the three components needed by the GraphQL query.
     */
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
                    "Invalid GitHub PR subjectExternalId — number must be integer: " + subjectExternalId, e);
        }
        return new PrCoordinates(parts[0], parts[1], number);
    }

    record PrCoordinates(String owner, String name, int number) {}
}
