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
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ProviderMentions;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubSummaryWriter.PrCoordinates;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * One review mutation posts all threads atomically. A lost response leaves every thread unconfirmed.
 * Verify copies at {@link FeedbackTarget#version}; resolved or outdated threads still count.
 */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
public class GitHubLineNoteWriter implements LineNoteWriter {

    private static final Logger log = LoggerFactory.getLogger(GitHubLineNoteWriter.class);

    private static final int THREADS_PAGE_SIZE = 100;

    private static final int MAX_THREAD_PAGES = 20;

    private final GitHubGraphQlClientProvider gitHubProvider;

    private final GitHubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;
    private final GitHubSummaryWriter summaries;

    public GitHubLineNoteWriter(
            GitHubGraphQlClientProvider gitHubProvider,
            GitHubPrNodeIdResolver prNodeIdResolver,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets,
            GitHubSummaryWriter summaries) {
        this.gitHubProvider = gitHubProvider;
        this.prNodeIdResolver = prNodeIdResolver;
        this.egressGuard = egressGuard;
        this.targets = targets;
        this.summaries = summaries;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public CopyWriteResult postImmutablePackage(
            FeedbackTarget target, List<LineNote> feedbackItems, CopyWriteFence fence) {
        if (feedbackItems.isEmpty()) {
            return CopyWriteResult.of(List.of());
        }
        // Each body is escaped before any note is written, so a body that cannot be escaped stops the whole package.
        feedbackItems.forEach(note -> ProviderMentions.escape(note.body()));
        long scopeId = target.integration().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            log.warn(
                    "GitHub rate limit critical — not requesting {} pieces of inline feedback: workspaceId={}",
                    feedbackItems.size(),
                    scopeId);
            return CopyWriteResult.of(notSent(feedbackItems));
        }
        PrCoordinates pr = GitHubSummaryWriter.parseSubjectExternalId(targets.subject(target, IntegrationKind.GITHUB));
        String commitOid = commitOid(target);
        if (commitOid == null) {
            return CopyWriteResult.of(notSent(feedbackItems));
        }
        var lines = feedbackItems.stream()
                .filter(note -> note.anchor().endLine() != null)
                .toList();
        Map<String, Copy> copies = lines.isEmpty() ? Map.of() : indexCopies(scopeId, pr, lines, commitOid);
        Map<UUID, SummaryLookup> fileCopies = new HashMap<>();
        for (LineNote note : feedbackItems)
            if (note.anchor().endLine() == null)
                fileCopies.put(
                        note.copyId(), summaries.findExistingSummary(target, fileContent(target, note, commitOid)));

        List<CopyReceipt> receipts = new ArrayList<>(feedbackItems.size());
        List<LineNote> candidates = new ArrayList<>(feedbackItems.size());
        Set<String> processedKeys = new HashSet<>();
        for (LineNote item : feedbackItems) {
            String key = item.writeKey();
            if (key != null && !processedKeys.add(key)) {
                continue;
            }
            FeedbackAnchor diff = item.anchor();
            if (item.body().isBlank() || (diff.endLine() != null && diff.side() == null)) {
                receipts.add(CopyReceipt.notSent(item.copyId()));
                continue;
            }
            var fileCopy = fileCopies.get(item.copyId());
            if (fileCopy != null && fileCopy.presence() != LookupPresence.ABSENT) {
                var handle = fileCopy.handle();
                receipts.add(
                        fileCopy.presence() == LookupPresence.FOUND && handle != null
                                ? new CopyReceipt(
                                        item.copyId(),
                                        CopyDisposition.PRESERVED_EXISTING,
                                        handle.externalRef(),
                                        handle.url(),
                                        null,
                                        CopyForm.FILE_NOTE)
                                : CopyReceipt.notSent(item.copyId()));
                continue;
            }
            Copy copy = copies.get(key);
            if (copy != null) {
                // A copy that does not verify is not proof of absence either: nothing is created
                // next to it.
                receipts.add(copy.verified() ? copy.preserved(item) : CopyReceipt.notSent(item.copyId()));
                continue;
            }
            candidates.add(item);
        }
        if (candidates.isEmpty()) {
            return CopyWriteResult.of(receipts);
        }

        if (candidates.stream().anyMatch(note -> note.anchor().endLine() == null))
            return postMixed(target, pr, commitOid, candidates, fence, receipts);
        String prNodeId = prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());
        try {
            egressGuard.requireDeliveryAllowed("github.post-inline-feedback");
        } catch (OutboundEgressSuppressedException e) {
            receipts.addAll(notSent(candidates));
            return CopyWriteResult.withheld(receipts, deliveryKeys(candidates));
        }
        if (!fence.beforeCreate(candidates.stream().map(LineNote::copyId).toList(), List.copyOf(receipts))) {
            receipts.addAll(notSent(candidates));
            return CopyWriteResult.of(receipts);
        }
        receipts.addAll(postBatch(scopeId, prNodeId, commitOid, candidates));
        return CopyWriteResult.of(receipts);
    }

    private SummaryContent fileContent(FeedbackTarget target, LineNote note, String commit) {
        var pr = GitHubSummaryWriter.parseSubjectExternalId(targets.subject(target, kind()));
        String origin = targets.serverUrl(target, kind());
        String anchoredCommit = note.anchor().side() == CitationSide.OLD
                ? Objects.requireNonNull(target.version().baseVersion())
                : commit;
        String encodedPath = Arrays.stream(note.anchor().path().split("/", -1))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        String body = "**[View file]("
                + origin.replaceAll("/+$", "")
                + "/"
                + pr.owner()
                + "/"
                + pr.name()
                + "/blob/"
                + anchoredCommit
                + "/"
                + encodedPath
                + ")**\n\n"
                + postedBody(note);
        return new SummaryContent(
                note.copyId(), body, note.writeKey(), ScmFeedbackFormatter.deliveryTag(note.writeKey()));
    }

    private CopyWriteResult postMixed(
            FeedbackTarget target,
            PrCoordinates pr,
            String commit,
            List<LineNote> notes,
            CopyWriteFence fence,
            List<CopyReceipt> completed) {
        long workspace = target.item().workspaceId();
        for (int index = 0; index < notes.size(); index++) {
            var note = notes.get(index);
            SummaryContent content;
            String nodeId;
            try {
                content = note.anchor().endLine() == null ? fileContent(target, note, commit) : null;
                nodeId = content == null
                        ? prNodeIdResolver.resolve(workspace, pr.owner(), pr.name(), pr.number())
                        : null;
            } catch (RuntimeException beforeWrite) {
                completed.add(CopyReceipt.notSent(note.copyId()));
                continue;
            }
            try {
                egressGuard.requireDeliveryAllowed("github.post-feedback-copy");
            } catch (OutboundEgressSuppressedException e) {
                var rest = notes.subList(index, notes.size());
                completed.addAll(notSent(rest));
                return CopyWriteResult.withheld(completed, deliveryKeys(rest));
            }
            if (!fence.beforeCreate(List.of(note.copyId()), List.copyOf(completed))) {
                completed.addAll(notSent(notes.subList(index, notes.size())));
                break;
            }
            CopyReceipt receipt;
            try {
                if (content != null) {
                    var handle = summaries.postSummary(target, content);
                    receipt = new CopyReceipt(
                            note.copyId(),
                            CopyDisposition.POSTED,
                            handle.externalRef(),
                            handle.url(),
                            true,
                            CopyForm.FILE_NOTE);
                } else {
                    receipt = postBatch(workspace, Objects.requireNonNull(nodeId), commit, List.of(note))
                            .getFirst();
                }
            } catch (FeedbackNotSentException beforeWrite) {
                receipt = CopyReceipt.notSent(note.copyId());
            } catch (RuntimeException uncertain) {
                receipt = CopyReceipt.attempted(note.copyId());
            }
            completed.add(receipt);
            if (receipt.unconfirmed()) {
                completed.addAll(notSent(notes.subList(index + 1, notes.size())));
                break;
            }
        }
        return CopyWriteResult.of(completed);
    }

    @Override
    public @Nullable List<CopyReceipt> findPosted(FeedbackTarget target, List<LineNote> feedbackItems) {
        if (feedbackItems.isEmpty()) {
            return List.of();
        }
        long scopeId = target.integration().workspaceId();
        if (commitOid(target) == null
                || gitHubProvider.isRateLimitCritical(scopeId)
                || feedbackItems.stream().anyMatch(item -> item.writeKey() == null)) {
            return null;
        }
        try {
            PrCoordinates pr =
                    GitHubSummaryWriter.parseSubjectExternalId(targets.subject(target, IntegrationKind.GITHUB));
            List<CopyReceipt> files = new ArrayList<>();
            var lines = feedbackItems.stream()
                    .filter(note -> note.anchor().endLine() != null)
                    .toList();
            for (LineNote note : feedbackItems)
                if (note.anchor().endLine() == null) {
                    var lookup = summaries.findExistingSummary(
                            target, fileContent(target, note, Objects.requireNonNull(commitOid(target))));
                    if (lookup.presence() == LookupPresence.UNKNOWN) return null;
                    var handle = lookup.handle();
                    if (lookup.presence() == LookupPresence.FOUND && handle != null)
                        files.add(new CopyReceipt(
                                note.copyId(),
                                CopyDisposition.PRESERVED_EXISTING,
                                handle.externalRef(),
                                handle.url(),
                                null,
                                CopyForm.FILE_NOTE));
                }
            Map<String, Copy> copies = lines.isEmpty() ? Map.of() : indexCopies(scopeId, pr, lines, commitOid(target));
            List<CopyReceipt> found = new ArrayList<>(files);
            for (LineNote item : feedbackItems) {
                Copy copy = copies.get(item.writeKey());
                if (copy != null && copy.verified()) {
                    found.add(copy.preserved(item));
                }
            }
            return found;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Only a returned comment id acknowledges creation; errors or missing ids can follow a provider write.
    private List<CopyReceipt> postBatch(
            long scopeId, String prNodeId, @Nullable String commitOid, List<LineNote> candidates) {
        List<Map<String, Object>> threads = new ArrayList<>(candidates.size());
        for (LineNote item : candidates) {
            threads.add(buildThread(item.anchor(), postedBody(item)));
        }
        try {
            ClientGraphQlResponse response = gitHubProvider
                    .forScope(scopeId)
                    .documentName("AddPullRequestReviewWithThreads")
                    .variable("pullRequestId", prNodeId)
                    .variable("event", "COMMENT")
                    .variable("commitOID", commitOid)
                    .variable("threads", threads)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (response == null) {
                log.warn("GitHub review mutation returned no response: workspaceId={}", scopeId);
                return attempted(candidates);
            }
            gitHubProvider.trackRateLimit(scopeId, response);
            if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                log.warn(
                        "GitHub review mutation returned errors: workspaceId={}, errors={}, threadCount={}",
                        scopeId,
                        response.getErrors(),
                        threads.size());
                return attempted(candidates);
            }
            List<Map<String, Object>> comments = response.field("addPullRequestReview.pullRequestReview.comments.nodes")
                    .getValue();
            log.info(
                    "Posted {} GitHub inline threads as single review: workspaceId={}, prNodeId={}",
                    threads.size(),
                    scopeId,
                    prNodeId);
            return postedReceipts(comments, candidates);
        } catch (Exception e) {
            log.warn(
                    "GitHub review mutation outcome unknown: workspaceId={}, threadCount={}",
                    scopeId,
                    threads.size(),
                    e);
            return attempted(candidates);
        }
    }

    // Match receipts by correlation tag; multiple feedback copies can share coordinates.
    private static List<CopyReceipt> postedReceipts(
            @Nullable List<Map<String, Object>> comments, List<LineNote> candidates) {
        Map<String, SummaryHandle> commentByCk = new HashMap<>();
        if (comments != null) {
            for (Map<String, Object> comment : comments) {
                String id = (String) comment.get("id");
                String body = (String) comment.get("body");
                String ck = body == null ? null : ScmFeedbackFormatter.lastDeliveryKey(body);
                if (id != null && !id.isBlank() && ck != null) {
                    commentByCk.putIfAbsent(ck, new SummaryHandle(id, (String) comment.get("url")));
                }
            }
        }
        List<CopyReceipt> receipts = new ArrayList<>(candidates.size());
        for (LineNote item : candidates) {
            SummaryHandle comment = item.writeKey() == null ? null : commentByCk.get(item.writeKey());
            receipts.add(
                    comment == null
                            ? CopyReceipt.attempted(item.copyId())
                            : new CopyReceipt(
                                    item.copyId(),
                                    CopyDisposition.POSTED,
                                    comment.externalRef(),
                                    comment.url(),
                                    true,
                                    CopyForm.LINE_NOTE));
        }
        return receipts;
    }

    // Partial pagination cannot prove absence; retain mismatched copies as conflicts.
    private Map<String, Copy> indexCopies(
            long scopeId, PrCoordinates pr, List<LineNote> items, @Nullable String commitOid) {
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
                    indexThread(thread, expected, commitOid, copies);
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

    private static void indexThread(
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
        boolean verified = item != null && sameCopy(thread, firstComment, body, item, commitOid);
        String url = firstComment.get("url") instanceof String commentUrl ? commentUrl : null;
        Copy previous = copies.get(key);
        if (verified && (previous == null || !previous.verified())) {
            copies.put(key, new Copy(true, threadId, commentId, url));
        } else if (!verified && previous == null) {
            copies.put(key, new Copy(false, threadId, commentId, url));
        }
    }

    private static boolean sameCopy(
            Map<?, ?> thread, Map<?, ?> comment, String body, LineNote item, @Nullable String commitOid) {
        FeedbackAnchor diff = item.anchor();
        if (diff.endLine() == null || diff.side() == null) return false;
        Integer startLine = diff.startLine();
        Integer expectedStart = startLine != null && startLine < diff.endLine() ? startLine : null;
        String expectedSide = diff.side() == CitationSide.OLD ? "LEFT" : "RIGHT";
        return Boolean.TRUE.equals(comment.get("viewerDidAuthor"))
                && body.equals(postedBody(item))
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

    private static List<CopyReceipt> notSent(List<LineNote> items) {
        return items.stream().map(item -> CopyReceipt.notSent(item.copyId())).toList();
    }

    private static List<CopyReceipt> attempted(List<LineNote> items) {
        return items.stream().map(item -> CopyReceipt.attempted(item.copyId())).toList();
    }

    private static List<UUID> deliveryKeys(List<LineNote> items) {
        return items.stream().map(LineNote::copyId).toList();
    }

    private static String postedBody(LineNote item) {
        String body = ProviderMentions.escape(item.body()) + "\n\n" + item.writeMarker();
        return body + "\n" + ScmFeedbackFormatter.deliveryTag(item.writeKey());
    }

    private record Copy(
            boolean verified,
            String threadId,
            String commentId,
            @Nullable String url) {
        CopyReceipt preserved(LineNote item) {
            return new CopyReceipt(
                    item.copyId(), CopyDisposition.PRESERVED_EXISTING, commentId, url, null, CopyForm.LINE_NOTE);
        }
    }

    private static Map<String, Object> buildThread(FeedbackAnchor diff, String body) {
        Map<String, Object> thread = new HashMap<>();
        thread.put("path", diff.path());
        thread.put("body", body);

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
