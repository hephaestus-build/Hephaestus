package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubSummaryChannel.PrCoordinates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link InlineFeedbackChannel}. Posts the new threads of a package as one
 * {@code addPullRequestReview} mutation: one notification per review, all-or-nothing for the batch.
 *
 * <p>Each thread's first comment carries the package marker and a hidden correlation tag with its delivery key.
 * Before any create, the pull request's review threads are read completely and the copies of the package are
 * indexed by key; an item with a copy is never posted again, and no copy is ever minimized or recreated, outdated
 * or resolved alike. The whole batch is fenced before the one request, so a lost response leaves every thread in it
 * unconfirmed rather than absent.
 *
 * <p>The review is anchored to {@link SummaryChannel.FeedbackTarget#reviewedRevision}, and a copy is verified at
 * its original commit and lines, so an outdated or resolved thread still counts as the copy it is.
 */
@Component
@OutboundEgressGateway
public class GitHubInlineFeedbackChannel implements InlineFeedbackChannel {

    private static final Logger log = LoggerFactory.getLogger(GitHubInlineFeedbackChannel.class);

    /** GitHub caps {@code reviewThreads(first:)} at 100; we page through with the connection cursor. */
    private static final int THREADS_PAGE_SIZE = 100;

    /** Hard cap on pagination; a scan that reaches it with more pages proves nothing. */
    private static final int MAX_THREAD_PAGES = 20;

    /**
     * Hidden per-delivery correlation tag embedded in a thread body so a copy can be matched back to the feedback
     * that produced it. The key is alnum/dash/underscore/colon, so no escaping is needed.
     */
    private static final Pattern CK_TAG = Pattern.compile("<!-- hephaestus-diff-note-ck=([A-Za-z0-9_:-]+) -->");

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final GitHubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;

    public GitHubInlineFeedbackChannel(
            GitHubGraphQlClientProvider gitHubProvider,
            GitHubPrNodeIdResolver prNodeIdResolver,
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
    public InlineResult postImmutablePackage(
            SummaryChannel.FeedbackTarget target,
            List<InlineFeedback> feedbackItems,
            Readback readback,
            WriteFence fence) {
        if (feedbackItems.isEmpty()) {
            return InlineResult.of(List.of());
        }
        long scopeId = target.ref().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            log.warn(
                    "GitHub rate limit critical — not requesting {} pieces of inline feedback: workspaceId={}",
                    feedbackItems.size(),
                    scopeId);
            return InlineResult.of(notSent(feedbackItems));
        }
        PrCoordinates pr = GitHubSummaryChannel.parseSubjectExternalId(target.subjectExternalId());
        String commitOid = commitOid(target);
        if (commitOid == null) {
            throw new FeedbackDeliveryException("Inline feedback has no reviewed revision");
        }
        Map<String, Copy> copies = indexCopies(scopeId, pr, feedbackItems, readback, commitOid);

        List<DeliveredSignal> signals = new ArrayList<>(feedbackItems.size());
        List<InlineFeedback> candidates = new ArrayList<>(feedbackItems.size());
        Set<String> processedKeys = new HashSet<>();
        for (InlineFeedback item : feedbackItems) {
            String key = item.deliveryKey();
            if (key != null && !processedKeys.add(key)) {
                continue; // one copy per delivery key
            }
            if (!(item.anchor() instanceof FeedbackAnchor.DiffAnchor diff)
                    || item.body().isBlank()
                    || key == null) {
                signals.add(DeliveredSignal.notSent(key, item.anchor()));
                continue;
            }
            Copy copy = copies.get(key);
            if (copy != null) {
                // A copy that does not verify is not proof of absence either: nothing is created next to it.
                signals.add(copy.verified() ? copy.preserved(item) : DeliveredSignal.notSent(key, diff));
                continue;
            }
            candidates.add(item);
        }
        if (candidates.isEmpty()) {
            return InlineResult.of(signals);
        }

        String prNodeId = prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());
        try {
            egressGuard.requireDeliveryAllowed("github.post-inline-feedback");
        } catch (OutboundEgressSuppressedException e) {
            signals.addAll(notSent(candidates));
            return InlineResult.suppressed(signals, deliveryKeys(candidates));
        }
        if (!fence.beforeCreate(List.copyOf(candidates), List.copyOf(signals))) {
            signals.addAll(notSent(candidates));
            return InlineResult.of(signals);
        }
        signals.addAll(postBatch(scopeId, prNodeId, commitOid, candidates, readback));
        return InlineResult.of(signals);
    }

    @Override
    public @Nullable List<DeliveredSignal> findPosted(
            SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems, Readback readback) {
        if (feedbackItems.isEmpty()) {
            return List.of();
        }
        long scopeId = target.ref().workspaceId();
        if (commitOid(target) == null
                || gitHubProvider.isRateLimitCritical(scopeId)
                || feedbackItems.stream().anyMatch(item -> item.deliveryKey() == null)) {
            return null;
        }
        try {
            PrCoordinates pr = GitHubSummaryChannel.parseSubjectExternalId(target.subjectExternalId());
            Map<String, Copy> copies = indexCopies(scopeId, pr, feedbackItems, readback, commitOid(target));
            List<DeliveredSignal> found = new ArrayList<>();
            for (InlineFeedback item : feedbackItems) {
                Copy copy = copies.get(item.deliveryKey());
                if (copy != null && copy.verified()) {
                    found.add(copy.preserved(item));
                }
            }
            return found;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The one review mutation for every candidate. Only a returned comment id acknowledges a thread; any other
     * outcome — no response, errors, an exception or a missing id — may still have created it.
     */
    private List<DeliveredSignal> postBatch(
            long scopeId,
            String prNodeId,
            @Nullable String commitOid,
            List<InlineFeedback> candidates,
            Readback readback) {
        List<Map<String, Object>> threads = new ArrayList<>(candidates.size());
        for (InlineFeedback item : candidates) {
            threads.add(buildThread((FeedbackAnchor.DiffAnchor) item.anchor(), postedBody(item, readback)));
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
            String reviewId =
                    response.field("addPullRequestReview.pullRequestReview.id").getValue();
            List<Map<String, Object>> comments = response.field("addPullRequestReview.pullRequestReview.comments.nodes")
                    .getValue();
            log.info(
                    "Posted {} GitHub inline threads as single review: workspaceId={}, prNodeId={}",
                    threads.size(),
                    scopeId,
                    prNodeId);
            return postedSignals(comments, reviewId, candidates);
        } catch (Exception e) {
            log.warn(
                    "GitHub review mutation outcome unknown: workspaceId={}, threadCount={}",
                    scopeId,
                    threads.size(),
                    e);
            return attempted(candidates);
        }
    }

    /**
     * Matches returned comment ids back to their feedback by the exact correlation tag in each body: two pieces of
     * feedback can anchor to one line, so coordinates alone cannot bind a comment to its feedback.
     */
    private static List<DeliveredSignal> postedSignals(
            @Nullable List<Map<String, Object>> comments, @Nullable String reviewId, List<InlineFeedback> candidates) {
        Map<String, SummaryChannel.SummaryHandle> commentByCk = new HashMap<>();
        if (comments != null) {
            for (Map<String, Object> comment : comments) {
                String id = (String) comment.get("id");
                String body = (String) comment.get("body");
                String ck = body == null ? null : parseDeliveryKey(body);
                if (id != null && !id.isBlank() && ck != null) {
                    commentByCk.putIfAbsent(ck, new SummaryChannel.SummaryHandle(id, (String) comment.get("url")));
                }
            }
        }
        List<DeliveredSignal> signals = new ArrayList<>(candidates.size());
        for (InlineFeedback item : candidates) {
            SummaryChannel.SummaryHandle comment =
                    item.deliveryKey() == null ? null : commentByCk.get(item.deliveryKey());
            signals.add(
                    comment == null
                            ? DeliveredSignal.attempted(item.deliveryKey(), item.anchor())
                            : new DeliveredSignal(
                                    item.deliveryKey(),
                                    item.anchor(),
                                    Disposition.POSTED,
                                    comment.externalId(),
                                    reviewId,
                                    comment.url(),
                                    true));
        }
        return signals;
    }

    /**
     * The package's copies on the pull request, by delivery key, from a complete scan: a page budget, a lost
     * cursor, a missing connection or an unanswered {@code hasNextPage} fails the scan instead of passing for
     * absence. A key whose copies do not verify is kept as a conflict.
     */
    private Map<String, Copy> indexCopies(
            long scopeId, PrCoordinates pr, List<InlineFeedback> items, Readback readback, @Nullable String commitOid) {
        // Historical automatic threads carried only their key, so the marker cannot select them.
        String marker = readback == Readback.SHARED ? null : items.getFirst().marker();
        Map<String, InlineFeedback> expected = new HashMap<>();
        for (InlineFeedback item : items) {
            if (item.deliveryKey() != null) expected.putIfAbsent(item.deliveryKey(), item);
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
                    indexThread(thread, marker, expected, readback, commitOid, copies);
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

    /** Indexes one review thread under the correlation key of its first comment, if it is a package copy. */
    private static void indexThread(
            @Nullable Object node,
            @Nullable String marker,
            Map<String, InlineFeedback> expected,
            Readback readback,
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
        if (marker != null && !body.contains(marker)) return;
        String key = parseDeliveryKey(body);
        if (key == null) {
            return; // human thread or a bot note that carries no delivery key
        }
        InlineFeedback item = expected.get(key);
        boolean verified = item != null && sameCopy(firstComment, body, item, readback, commitOid);
        String url = firstComment.get("url") instanceof String commentUrl ? commentUrl : null;
        Copy previous = copies.get(key);
        if (verified && (previous == null || !previous.verified())) {
            copies.put(key, new Copy(true, threadId, commentId, url));
        } else if (!verified && previous == null) {
            copies.put(key, new Copy(false, threadId, commentId, url));
        }
    }

    /** The viewer's own comment, with the exact body this channel posts, at the item's original lines and commit. */
    private static boolean sameCopy(
            Map<?, ?> comment, String body, InlineFeedback item, Readback readback, @Nullable String commitOid) {
        if (!(item.anchor() instanceof FeedbackAnchor.DiffAnchor diff)) {
            return false;
        }
        Integer startLine = diff.startLine();
        Integer expectedStart = startLine != null && startLine < diff.newLineNumber() ? startLine : null;
        return Boolean.TRUE.equals(comment.get("viewerDidAuthor"))
                && body.equals(postedBody(item, readback))
                && diff.filePath().equals(comment.get("path"))
                && comment.get("originalLine") instanceof Number line
                && line.intValue() == diff.newLineNumber()
                && Objects.equals(expectedStart, intOrNull(comment.get("originalStartLine")))
                && commitOid != null
                && comment.get("originalCommit") instanceof Map<?, ?> commit
                && commitOid.equals(commit.get("oid"));
    }

    private static @Nullable Integer intOrNull(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /** The commit the reviewed work was captured at; blank means none, since an empty GitObjectID fails the batch. */
    private static @Nullable String commitOid(SummaryChannel.FeedbackTarget target) {
        String raw = target.reviewedRevision();
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static List<DeliveredSignal> notSent(List<InlineFeedback> items) {
        return items.stream()
                .map(item -> DeliveredSignal.notSent(item.deliveryKey(), item.anchor()))
                .toList();
    }

    private static List<DeliveredSignal> attempted(List<InlineFeedback> items) {
        return items.stream()
                .map(item -> DeliveredSignal.attempted(item.deliveryKey(), item.anchor()))
                .toList();
    }

    private static List<String> deliveryKeys(List<InlineFeedback> items) {
        return items.stream()
                .map(InlineFeedback::deliveryKey)
                .filter(Objects::nonNull)
                .toList();
    }

    @Nullable
    private static String parseDeliveryKey(String body) {
        Matcher m = CK_TAG.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The thread body: the sealed text, the package marker and the correlation tag. Historical automatic threads
     * were posted without the shared marker, and are rendered that way again.
     */
    private static String postedBody(InlineFeedback item, Readback readback) {
        String body = readback == Readback.SHARED || item.body().contains(item.marker())
                ? item.body()
                : item.body() + "\n\n" + item.marker();
        String key = item.deliveryKey();
        return key == null || key.isBlank() ? body : body + "\n<!-- hephaestus-diff-note-ck=" + key + " -->";
    }

    /** A copy of a package item; {@code verified} false when it carries the key but not the item. */
    private record Copy(
            boolean verified,
            String threadId,
            String commentId,
            @Nullable String url) {
        DeliveredSignal preserved(InlineFeedback item) {
            return new DeliveredSignal(
                    item.deliveryKey(), item.anchor(), Disposition.PRESERVED_EXISTING, commentId, threadId, url);
        }
    }

    /** Builds a GitHub review-thread payload from a {@link FeedbackAnchor.DiffAnchor}. */
    private static Map<String, Object> buildThread(FeedbackAnchor.DiffAnchor diff, String body) {
        Map<String, Object> thread = new HashMap<>();
        thread.put("path", diff.filePath());
        thread.put("body", body);

        Integer startLine = diff.startLine();
        boolean isMultiLine = startLine != null && startLine < diff.newLineNumber();
        if (isMultiLine) {
            thread.put("startLine", startLine);
            thread.put("line", diff.newLineNumber());
            thread.put("side", "RIGHT");
            thread.put("startSide", "RIGHT");
        } else {
            thread.put("line", diff.newLineNumber());
            thread.put("side", "RIGHT");
        }
        return thread;
    }
}
