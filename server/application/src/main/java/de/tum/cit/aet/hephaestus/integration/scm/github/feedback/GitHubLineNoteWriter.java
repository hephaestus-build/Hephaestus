package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter.Footer;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubSummaryWriter.PrCoordinates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * Stages a package as one pending review with its threads, then submits it once, the only notifying write.
 * A lost response permits readback only. Verify copies at {@link FeedbackTarget#version}; resolved or
 * outdated threads still count.
 */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
public class GitHubLineNoteWriter implements LineNoteWriter {

    private static final int THREADS_PAGE_SIZE = 100;

    private static final int MAX_THREAD_PAGES = 20;

    private final GitHubGraphQlClientProvider gitHubProvider;

    private final GitHubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;
    private final ScmFeedbackFormatter formatter;

    public GitHubLineNoteWriter(
            GitHubGraphQlClientProvider gitHubProvider,
            GitHubPrNodeIdResolver prNodeIdResolver,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets,
            ScmFeedbackFormatter formatter) {
        this.gitHubProvider = gitHubProvider;
        this.prNodeIdResolver = prNodeIdResolver;
        this.egressGuard = egressGuard;
        this.targets = targets;
        this.formatter = formatter;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public CopyWriteResult postPackage(FeedbackTarget target, OnWorkPackage publication, CopyWriteFence fence) {
        if (publication.copyIds().isEmpty()) return CopyWriteResult.of(List.of());
        long workspace = target.item().workspaceId();
        if (gitHubProvider.isRateLimitCritical(workspace) || commitOid(target) == null) return publication.notSent();
        boolean fenced = false;
        try {
            var footer = formatter.footer(target.item());
            var pr = GitHubSummaryWriter.parseSubjectExternalId(targets.subject(target, kind()));
            var copies = indexCopies(footer, pr, publication.notes(), commitOid(target));
            String body = publication.summary() == null
                    ? publication.notes().getFirst().writeMarker()
                    : footer.summary(publication.summary());
            var reviews = reviewLookup(
                    workspace,
                    pr,
                    body,
                    Objects.requireNonNull(commitOid(target)),
                    publication.notes().stream()
                            .map(note -> postedBody(footer, note))
                            .collect(Collectors.toSet()));
            if (reviews.conflict()) return publication.notSent();
            if (reviews.submitted() != null) {
                // A receipt is recorded only on a fenced copy.
                if (!fence.beforeCreate(publication.copyIds(), List.of())) return publication.notSent();
                return CopyWriteResult.of(receipts(publication, reviews.submitted(), copies));
            }
            if (publication.notes().stream().anyMatch(note -> copies.containsKey(note.writeKey()))
                    && reviews.pending() == null) return publication.notSent();
            egressGuard.requireDeliveryAllowed("github.publish-feedback-package");
            // A refused fence started nothing: the copies stay as they were.
            if (!fence.beforeCreate(publication.copyIds(), List.of())) return CopyWriteResult.of(List.of());
            fenced = true;
            String pending = reviews.pending();
            if (pending == null) {
                var response = execute(
                        workspace,
                        "AddPullRequestReviewWithThreads",
                        Map.of(
                                "pullRequestId",
                                prNodeIdResolver.resolve(workspace, pr.owner(), pr.name(), pr.number()),
                                "body",
                                body,
                                "commitOID",
                                Objects.requireNonNull(commitOid(target)),
                                "threads",
                                List.of()));
                pending = response.field("addPullRequestReview.pullRequestReview.id")
                        .getValue();
                if (pending == null
                        || !"PENDING"
                                .equals(response.field("addPullRequestReview.pullRequestReview.state")
                                        .getValue())) return publication.attempted();
            }
            for (var note : publication.notes()) {
                var copy = copies.get(note.writeKey());
                if (copy != null) {
                    if (!copy.verified()) return publication.attempted();
                    continue;
                }
                if (gitHubProvider.isRateLimitCritical(workspace)) return publication.attempted();
                if (!fence.beforeCreate(List.of(), List.of())) return publication.attempted();
                var input = buildThread(note.anchor(), postedBody(footer, note));
                input.put("pullRequestReviewId", pending);
                var created = execute(workspace, "AddFeedbackReviewThread", Map.of("input", input));
                String id =
                        created.field("addPullRequestReviewThread.thread.id").getValue();
                if (id == null) return publication.attempted();
            }
            // Re-read the entire pending review before the single notifying write. Never submit a foreign draft.
            var beforeSubmit = reviewLookup(
                    workspace,
                    pr,
                    body,
                    Objects.requireNonNull(commitOid(target)),
                    publication.notes().stream()
                            .map(note -> postedBody(footer, note))
                            .collect(Collectors.toSet()));
            var complete = indexCopies(footer, pr, publication.notes(), commitOid(target));
            if (beforeSubmit.conflict()
                    || !pending.equals(beforeSubmit.pending())
                    || beforeSubmit.pendingComments() != publication.notes().size()
                    || publication.notes().stream().anyMatch(note -> {
                        var copy = complete.get(note.writeKey());
                        return copy == null || !copy.verified();
                    })
                    || !fence.beforeCreate(List.of(), List.of())) return publication.attempted();
            egressGuard.requireDeliveryAllowed("github.submit-feedback-package");
            execute(workspace, "SubmitFeedbackReview", Map.of("reviewId", pending));
            var found = findPosted(target, publication);
            return found == null ? publication.attempted() : CopyWriteResult.of(found);
        } catch (OutboundEgressSuppressedException suppressed) {
            throw suppressed;
        } catch (RuntimeException unknown) {
            // A timeout can follow submission; only readback may establish success on the next attempt.
            return fenced ? publication.attempted() : publication.notSent();
        }
    }

    @Override
    public @Nullable List<CopyReceipt> findPosted(FeedbackTarget target, OnWorkPackage publication) {
        long workspace = target.item().workspaceId();
        if (gitHubProvider.isRateLimitCritical(workspace) || commitOid(target) == null) return null;
        try {
            var footer = formatter.footer(target.item());
            var pr = GitHubSummaryWriter.parseSubjectExternalId(targets.subject(target, kind()));
            String body = publication.summary() == null
                    ? publication.notes().getFirst().writeMarker()
                    : footer.summary(publication.summary());
            var review = reviewLookup(
                    workspace,
                    pr,
                    body,
                    Objects.requireNonNull(commitOid(target)),
                    publication.notes().stream()
                            .map(note -> postedBody(footer, note))
                            .collect(Collectors.toSet()));
            if (review.conflict()) return null;
            if (review.submitted() == null) return List.of();
            return receipts(
                    publication, review.submitted(), indexCopies(footer, pr, publication.notes(), commitOid(target)));
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private ClientGraphQlResponse execute(long workspace, String document, Map<String, Object> variables) {
        var request = gitHubProvider.forScope(workspace).documentName(document);
        variables.forEach(request::variable);
        var response = request.execute().block(GRAPHQL_TIMEOUT);
        if (response == null || !response.getErrors().isEmpty())
            throw new FeedbackDeliveryException("GitHub did not confirm the review operation");
        gitHubProvider.trackRateLimit(workspace, response);
        return response;
    }

    private ReviewLookup reviewLookup(
            long workspace, PrCoordinates pr, String expectedBody, String commit, Set<String> expectedNotes) {
        int markerStart = expectedBody.lastIndexOf("<!--");
        String marker = markerStart < 0 ? expectedBody : expectedBody.substring(markerStart);
        String after = null;
        String pending = null;
        int pendingComments = 0;
        SummaryHandle submitted = null;
        for (int page = 0; page < MAX_THREAD_PAGES; page++) {
            var variables = new HashMap<String, Object>();
            variables.put("owner", pr.owner());
            variables.put("name", pr.name());
            variables.put("number", pr.number());
            variables.put("first", THREADS_PAGE_SIZE);
            if (after != null) variables.put("after", after);
            var response = execute(workspace, "GetFeedbackReviews", variables);
            Map<String, Object> connection =
                    response.field("repository.pullRequest.reviews").getValue();
            if (connection == null
                    || !(connection.get("nodes") instanceof List<?> nodes)
                    || !(connection.get("pageInfo") instanceof Map<?, ?> info))
                throw new FeedbackDeliveryException("GitHub reviews were not read completely");
            for (var value : nodes) {
                if (!(value instanceof Map<?, ?> review)
                        || !(review.get("body") instanceof String body)
                        || !(review.get("id") instanceof String id)
                        || !(review.get("state") instanceof String state))
                    throw new FeedbackDeliveryException("GitHub review identity is incomplete");
                if (!Boolean.TRUE.equals(review.get("viewerDidAuthor"))) continue;
                boolean same = body.equals(expectedBody)
                        && review.get("commit") instanceof Map<?, ?> revision
                        && commit.equals(revision.get("oid"));
                if (state.equals("PENDING")) {
                    if (!same
                            || pending != null
                            || !(review.get("comments") instanceof Map<?, ?> comments)
                            || !(comments.get("nodes") instanceof List<?> entries)
                            || !(comments.get("pageInfo") instanceof Map<?, ?> pageInfo)
                            || !Boolean.FALSE.equals(pageInfo.get("hasNextPage"))
                            || entries.size() > expectedNotes.size()
                            || entries.stream()
                                    .anyMatch(entry -> !(entry instanceof Map<?, ?> comment)
                                            || !Boolean.TRUE.equals(comment.get("viewerDidAuthor"))
                                            || !expectedNotes.contains(comment.get("body")))
                            || entries.stream()
                                            .map(entry -> ((Map<?, ?>) entry).get("body"))
                                            .distinct()
                                            .count()
                                    != entries.size()) return new ReviewLookup(null, 0, null, true);
                    pending = id;
                    pendingComments = entries.size();
                } else if (same) {
                    if (submitted != null) return new ReviewLookup(null, 0, null, true);
                    submitted = new SummaryHandle(id, review.get("url") instanceof String url ? url : null);
                } else if (body.contains(marker)) return new ReviewLookup(null, 0, null, true);
            }
            if (Boolean.FALSE.equals(info.get("hasNextPage")))
                return new ReviewLookup(pending, pendingComments, submitted, false);
            if (!Boolean.TRUE.equals(info.get("hasNextPage"))
                    || !(info.get("endCursor") instanceof String next)
                    || next.isBlank()
                    || next.equals(after)) break;
            after = next;
        }
        throw new FeedbackDeliveryException("GitHub review pagination did not reach its end");
    }

    private static List<CopyReceipt> receipts(
            OnWorkPackage publication, SummaryHandle review, Map<String, Copy> copies) {
        var receipts = new ArrayList<CopyReceipt>();
        if (publication.summary() != null)
            receipts.add(new CopyReceipt(
                    publication.summary().copyId(),
                    CopyDisposition.PRESERVED_EXISTING,
                    review.externalRef(),
                    review.url(),
                    true,
                    null));
        for (var note : publication.notes()) {
            var copy = copies.get(note.writeKey());
            receipts.add(copy != null && copy.verified() ? copy.preserved(note) : CopyReceipt.attempted(note.copyId()));
        }
        return List.copyOf(receipts);
    }

    /** {@code pendingComments} counts the pending review's own comments, each a distinct expected note. */
    private record ReviewLookup(
            @Nullable String pending,
            int pendingComments,
            @Nullable SummaryHandle submitted,
            boolean conflict) {}

    // Partial pagination cannot prove absence; retain mismatched copies as conflicts.
    private Map<String, Copy> indexCopies(
            Footer footer, PrCoordinates pr, List<LineNote> items, @Nullable String commitOid) {
        if (items.isEmpty()) return Map.of();
        long scopeId = footer.workspaceId();
        Map<String, LineNote> expected = new HashMap<>();
        for (LineNote item : items) {
            expected.putIfAbsent(item.writeKey(), item);
        }
        Map<String, Copy> copies = new LinkedHashMap<>();
        String after = null;
        try {
            for (int page = 1; ; page++) {
                ClientGraphQlResponse response = gitHubProvider
                        .forScope(scopeId)
                        .documentName("GetPullRequestReviewThreads")
                        .variable("owner", pr.owner())
                        .variable("name", pr.name())
                        .variable("number", pr.number())
                        .variable("first", THREADS_PAGE_SIZE)
                        .variable("after", after)
                        .execute()
                        .block(GRAPHQL_TIMEOUT);

                if (response == null
                        || (response.getErrors() != null
                                && !response.getErrors().isEmpty())) {
                    throw new FeedbackDeliveryException("GitHub review-thread lookup returned no answer");
                }
                gitHubProvider.trackRateLimit(scopeId, response);

                Map<String, Object> connection =
                        response.field("repository.pullRequest.reviewThreads").getValue();
                if (connection == null
                        || !(connection.get("nodes") instanceof List<?> threads)
                        || !(connection.get("pageInfo") instanceof Map<?, ?> pageInfo)) {
                    throw new FeedbackDeliveryException("GitHub review-thread lookup was incomplete");
                }
                for (Object thread : threads) {
                    indexThread(footer, thread, expected, commitOid, copies);
                }
                Object hasNextPage = pageInfo.get("hasNextPage");
                if (Boolean.FALSE.equals(hasNextPage)) {
                    return copies;
                }
                if (!Boolean.TRUE.equals(hasNextPage)
                        || page == MAX_THREAD_PAGES
                        || !(pageInfo.get("endCursor") instanceof String next)
                        || next.isBlank()
                        || next.equals(after)) {
                    throw new FeedbackDeliveryException("GitHub review-thread pagination did not reach its end");
                }
                after = next;
            }
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitHub review-thread lookup was inconclusive", e);
        }
    }

    private void indexThread(
            Footer footer,
            @Nullable Object node,
            Map<String, LineNote> expected,
            @Nullable String commitOid,
            Map<String, Copy> copies) {
        if (!(node instanceof Map<?, ?> thread)
                || !(thread.get("id") instanceof String threadId)
                || !(thread.get("comments") instanceof Map<?, ?> connection)
                || !(connection.get("nodes") instanceof List<?> comments)) {
            throw new FeedbackDeliveryException("A GitHub review thread was not read completely");
        }
        if (comments.isEmpty()
                || !(comments.getFirst() instanceof Map<?, ?> firstComment)
                || !(firstComment.get("body") instanceof String body)
                || !(firstComment.get("id") instanceof String commentId)
                || commentId.isBlank()
                || threadId.isBlank()) {
            throw new FeedbackDeliveryException("A GitHub review comment was not read completely");
        }
        String key = ScmFeedbackFormatter.lastDeliveryKey(body);
        if (key == null) {
            return;
        }
        LineNote item = expected.get(key);
        boolean verified = item != null && sameCopy(footer, thread, firstComment, body, item, commitOid);
        String url = firstComment.get("url") instanceof String commentUrl ? commentUrl : null;
        Copy previous = copies.get(key);
        if (verified && (previous == null || !previous.verified())) {
            copies.put(key, new Copy(true, threadId, commentId, url));
        } else if (!verified && previous == null) {
            copies.put(key, new Copy(false, threadId, commentId, url));
        }
    }

    private boolean sameCopy(
            Footer footer,
            Map<?, ?> thread,
            Map<?, ?> comment,
            String body,
            LineNote item,
            @Nullable String commitOid) {
        FeedbackAnchor diff = item.anchor();
        if (diff.endLine() == null)
            return Boolean.TRUE.equals(comment.get("viewerDidAuthor"))
                    && "FILE".equals(thread.get("subjectType"))
                    && body.equals(postedBody(footer, item))
                    && diff.path().equals(comment.get("path"))
                    && comment.get("originalCommit") instanceof Map<?, ?> revision
                    && Objects.equals(commitOid, revision.get("oid"));
        if (diff.side() == null) return false;
        Integer startLine = diff.startLine();
        Integer expectedStart = startLine != null && startLine < diff.endLine() ? startLine : null;
        String expectedSide = diff.side() == CitationSide.OLD ? "LEFT" : "RIGHT";
        return Boolean.TRUE.equals(comment.get("viewerDidAuthor"))
                && body.equals(postedBody(footer, item))
                && expectedSide.equals(thread.get("diffSide"))
                && (expectedStart == null || expectedSide.equals(thread.get("startDiffSide")))
                && diff.path().equals(comment.get("path"))
                && comment.get("originalLine") instanceof Number line
                && line.intValue() == diff.endLine()
                && Objects.equals(expectedStart, intOrNull(comment.get("originalStartLine")))
                && commitOid != null
                && comment.get("originalCommit") instanceof Map<?, ?> commit
                && commitOid.equals(commit.get("oid"));
    }

    private static @Nullable Integer intOrNull(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static @Nullable String commitOid(FeedbackTarget target) {
        try {
            return ScmVersion.reviewedHead(target.version());
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
    }

    private String postedBody(Footer footer, LineNote item) {
        String body = footer.appendLineNotePrompt(PostedText.of(item.body())) + "\n" + item.writeMarker();
        return body + "\n" + ScmFeedbackFormatter.deliveryTag(item.writeKey());
    }

    private record Copy(
            boolean verified,
            String threadId,
            String commentId,
            @Nullable String url) {
        CopyReceipt preserved(LineNote item) {
            return new CopyReceipt(
                    item.copyId(),
                    CopyDisposition.PRESERVED_EXISTING,
                    commentId,
                    url,
                    true,
                    item.anchor().endLine() == null ? CopyForm.FILE_NOTE : CopyForm.LINE_NOTE);
        }
    }

    private static Map<String, Object> buildThread(FeedbackAnchor diff, String body) {
        Map<String, Object> thread = new HashMap<>();
        thread.put("path", diff.path());
        thread.put("body", body);

        if (diff.endLine() == null) {
            thread.put("subjectType", "FILE");
            return thread;
        }
        thread.put("subjectType", "LINE");
        Integer startLine = diff.startLine();
        int endLine = Objects.requireNonNull(diff.endLine(), "A line note requires an end line");
        boolean isMultiLine = startLine != null && startLine < endLine;
        if (isMultiLine) {
            thread.put("startLine", startLine);
            thread.put("line", endLine);
            thread.put("side", diff.side() == CitationSide.OLD ? "LEFT" : "RIGHT");
            thread.put("startSide", diff.side() == CitationSide.OLD ? "LEFT" : "RIGHT");
        } else {
            thread.put("line", endLine);
            thread.put("side", diff.side() == CitationSide.OLD ? "LEFT" : "RIGHT");
        }
        return thread;
    }

    @Override
    public List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> copies) {
        targets.subject(target, kind());
        long workspaceId = target.item().workspaceId();
        List<CopyUpdateReceipt> outcomes = new ArrayList<>();
        for (CopyReceipt copy : copies) {
            String externalRef = copy.externalRef();
            if (externalRef == null || externalRef.isBlank()) {
                outcomes.add(
                        CopyUpdateReceipt.transientFailure(copy.copyId(), "Copy has no recorded provider reference"));
                continue;
            }
            try {
                ClientGraphQlResponse read = gitHubProvider
                        .forScope(workspaceId)
                        .document(
                                "query ReadPracticeCopy($id:ID!){node(id:$id){__typename ... on PullRequestReviewComment{id body viewerDidAuthor} ... on IssueComment{id body viewerDidAuthor}}}")
                        .variable("id", externalRef)
                        .execute()
                        .block(GRAPHQL_TIMEOUT);
                if (read == null || !read.getErrors().isEmpty()) {
                    outcomes.add(CopyUpdateReceipt.transientFailure(copy.copyId(), "Existing copy could not be read"));
                    continue;
                }
                Object node = read.field("node").getValue();
                if (node == null) {
                    outcomes.add(CopyUpdateReceipt.gone(copy.copyId(), "The provider copy no longer exists"));
                    continue;
                }
                if (!(node instanceof Map<?, ?> note)
                        || !externalRef.equals(note.get("id"))
                        || !("IssueComment".equals(note.get("__typename"))
                                || "PullRequestReviewComment".equals(note.get("__typename")))
                        || !(note.get("body") instanceof String original)
                        || !Boolean.TRUE.equals(note.get("viewerDidAuthor"))) {
                    outcomes.add(new CopyUpdateReceipt(
                            copy.copyId(),
                            CopyUpdateKind.UNSUPPORTED,
                            externalRef,
                            copy.externalUrl(),
                            "Existing copy authority could not be verified"));
                    continue;
                }
                egressGuard.requireDeliveryAllowed("github.correct-feedback-copy");
                boolean fileCopy = "IssueComment".equals(note.get("__typename"));
                String resultPath = fileCopy
                        ? "updateIssueComment.issueComment"
                        : "updatePullRequestReviewComment.pullRequestReviewComment";
                ClientGraphQlResponse response = gitHubProvider
                        .forScope(workspaceId)
                        .document(
                                fileCopy
                                        ? "mutation CorrectPracticeFile($id:ID!,$body:String!){updateIssueComment(input:{id:$id,body:$body}){issueComment{id url}}}"
                                        : "mutation CorrectPracticeLine($id:ID!,$body:String!){updatePullRequestReviewComment(input:{pullRequestReviewCommentId:$id,body:$body}){pullRequestReviewComment{id url}}}")
                        .variable("id", externalRef)
                        .variable("body", ScmFeedbackFormatter.withdrawnCopyBody(original))
                        .execute()
                        .block(GRAPHQL_TIMEOUT);
                if (response == null || !response.getErrors().isEmpty()) {
                    outcomes.add(CopyUpdateReceipt.transientFailure(
                            copy.copyId(), "Provider did not confirm the correction"));
                    continue;
                }
                String id = response.field(resultPath + ".id").getValue();
                String url = response.field(resultPath + ".url").getValue();
                outcomes.add(
                        id == null || !id.equals(externalRef)
                                ? CopyUpdateReceipt.transientFailure(
                                        copy.copyId(), "Provider did not confirm the existing copy")
                                : new CopyUpdateReceipt(copy.copyId(), CopyUpdateKind.EDITED, id, url, null));
            } catch (OutboundEgressSuppressedException e) {
                throw e;
            } catch (RuntimeException e) {
                outcomes.add(CopyUpdateReceipt.transientFailure(copy.copyId(), "Provider correction failed"));
            }
        }
        return List.copyOf(outcomes);
    }
}
