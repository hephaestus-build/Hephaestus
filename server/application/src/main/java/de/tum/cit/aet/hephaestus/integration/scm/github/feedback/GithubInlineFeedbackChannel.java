package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GithubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GithubSummaryChannel.PrCoordinates;
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
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link InlineFeedbackChannel}. Posts all inline feedbackItems as a
 * single atomic {@code addPullRequestReview} mutation with embedded threads — one
 * notification per review, all-or-nothing semantics for the batch.
 *
 * <p>Reconciliation under GitHub's append-only review model differs from GitLab's edit-in-place: a review
 * thread cannot be deleted or edited, only minimized. So each finding's stable
 * {@link de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint} is embedded in the thread body as a
 * hidden HTML tag, and before posting we read the PR's existing review threads
 * ({@code GetPullRequestReviewThreads}) and index this reviewer's own prior threads by that key. A finding
 * whose key already has a live (non-outdated) bot thread is PRESERVED rather than re-posted, so retrying an exact
 * delivery does not create a duplicate thread. After posting, the created comment node ids are
 * read back from the mutation payload and matched by their exact delivery keys so each
 * {@link DeliveredSignal} carries the durable comment + review handles.
 *
 * <p>Both the post path and the {@link #clearStaleFeedback} override retire (minimize as {@code OUTDATED}) the
 * prior bot threads whose finding the current run no longer emits — the GitHub analogue of GitLab's
 * delete-stale-notes path. {@code clearStaleFeedback} covers the zero-note re-run; {@code postInlineFeedback}
 * covers the partial-vanish case (some feedbackItems still hold, others went away).
 *
 * <p>Non-{@link FeedbackAnchor.DiffAnchor} anchors are counted as failed, logged, and emitted as a
 * {@code FAILED} {@link DeliveredSignal} (GitHub has no analogue for document/channel/issue anchors on a PR
 * review) so the SPI invariant {@code posted + failed == signals.size()} holds, matching GitLab.
 *
 * <p>The commit SHA the review is anchored to is read from the {@link SummaryChannel.FeedbackTarget#resourceUrl}
 * field — the agent layer encodes the head commit there so the channel doesn't need
 * to re-resolve PR metadata.
 */
@Component
@OutboundEgressGateway
public class GithubInlineFeedbackChannel implements InlineFeedbackChannel {

    private static final Logger log = LoggerFactory.getLogger(GithubInlineFeedbackChannel.class);

    /** GitHub caps {@code reviewThreads(first:)} at 100; we page through with the connection cursor. */
    private static final int THREADS_PAGE_SIZE = 100;

    /** Hard cap on pagination so a pathological PR with thousands of threads cannot hang reconciliation. */
    private static final int MAX_THREAD_PAGES = 20;

    /** GitHub {@code minimizeComment} classifier for a thread whose finding is no longer reported. */
    private static final String OUTDATED_CLASSIFIER = "OUTDATED";

    /**
     * Hidden per-delivery correlation tag embedded in a thread body so a prior thread can be matched back to the
     * finding that produced it across re-runs. Humans never type this HTML comment, so its presence in a thread's
     * first comment marks the thread as one of ours. The key is alnum/dash/underscore (a
     * {@link de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint} digest), so no escaping is needed.
     */
    private static final Pattern CK_TAG = Pattern.compile("<!-- hephaestus-diff-note-ck=([A-Za-z0-9_:-]+) -->");

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final GithubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;

    public GithubInlineFeedbackChannel(
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
    public InlineResult postInlineFeedback(SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems) {
        return postInlineFeedback(target, feedbackItems, false);
    }

    @Override
    public InlineResult postImmutablePackage(SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems) {
        return postInlineFeedback(target, feedbackItems, true);
    }

    private InlineResult postInlineFeedback(
            SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems, boolean immutablePackage) {
        if (feedbackItems == null || feedbackItems.isEmpty()) {
            return InlineResult.counts(0, 0);
        }
        long scopeId = target.ref().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            log.warn(
                    "GitHub rate limit critical — skipping {} inline feedbackItems: workspaceId={}",
                    feedbackItems.size(),
                    scopeId);
            return InlineResult.counts(0, feedbackItems.size());
        }

        PrCoordinates pr = GithubSummaryChannel.parseSubjectExternalId(target.subjectExternalId());

        String marker = immutablePackage ? feedbackItems.get(0).marker() : null;
        Map<String, PriorThread> priorByKey = indexPriorThreads(scopeId, pr, marker);

        // Partition feedbackItems: those whose key already has a live prior thread are preserved; the rest are posted.
        List<InlineFeedback> toPost = new ArrayList<>(feedbackItems.size());
        List<DeliveredSignal> preservedSignals = new ArrayList<>();
        // Per-finding FAILED signals for anchors GitHub cannot place (non-diff). Counted in `failed` AND carried
        // in `signals` so the SPI invariant posted + failed == signals.size() holds (GitLab parity).
        List<DeliveredSignal> unsupportedSignals = new ArrayList<>();
        // Keys still backed by a finding this run (preserved now, posted below). Prior threads outside this set
        // belong to feedbackItems that vanished and must be retired.
        Set<String> seenKeys = new HashSet<>();
        for (InlineFeedback finding : feedbackItems) {
            if (!(finding.anchor() instanceof FeedbackAnchor.DiffAnchor diff)) {
                log.warn("Skipping non-diff anchor on GitHub inline feedback: anchor={}", finding.anchor());
                unsupportedSignals.add(
                        new DeliveredSignal(finding.deliveryKey(), finding.anchor(), Disposition.FAILED, null, null));
                continue;
            }
            // Register the key as seen BEFORE the blank-body guard: a finding whose key is still present this run
            // must never be reaped by minimizeVanishedThreads, regardless of body content (GitLab parity).
            // Otherwise a valid-key, blank-body finding would silently minimize its own still-current thread.
            String key = finding.deliveryKey();
            if (key != null) {
                seenKeys.add(key);
            }
            if (finding.body() == null || finding.body().isBlank()) {
                continue;
            }
            PriorThread prior = key == null ? null : priorByKey.get(key);
            if (prior != null && !prior.outdated()) {
                // The finding still holds and already has a live thread — leave it, don't duplicate.
                preservedSignals.add(new DeliveredSignal(
                        key, diff, Disposition.PRESERVED_EXISTING, prior.commentId(), prior.threadId()));
                continue;
            }
            toPost.add(finding);
        }
        int unsupportedAnchorCount = unsupportedSignals.size();

        if (toPost.isEmpty()) {
            // Nothing new to post, but feedbackItems that vanished since the last run still have live bot threads —
            // retire them here too (this is the most common partial re-review: all survivors are preserved).
            int minimized = minimizeVanishedThreads(scopeId, priorByKey.values(), seenKeys);
            log.debug(
                    "All GitHub inline feedbackItems preserved or skipped (none to post): workspaceId={}, preserved={}, minimized={}",
                    scopeId,
                    preservedSignals.size(),
                    minimized);
            List<DeliveredSignal> all = new ArrayList<>(preservedSignals);
            all.addAll(unsupportedSignals);
            return new InlineResult(preservedSignals.size(), unsupportedAnchorCount, List.copyOf(all));
        }

        String prNodeId = prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());
        // The agent encodes the head SHA in resourceUrl. Normalize blank to null: commitOID is a nullable
        // GitObjectID (a null falls back to head-anchoring), but a blank "" is an INVALID GitObjectID that
        // would fail the whole batch mutation with a GraphQL error and route every finding to FAILED.
        String rawCommitOid = target.resourceUrl();
        String commitOid = (rawCommitOid == null || rawCommitOid.isBlank()) ? null : rawCommitOid;

        // Build the thread payloads, embedding each finding's correlation tag so the next run can index it back.
        List<Map<String, Object>> threads = new ArrayList<>(toPost.size());
        List<FeedbackAnchor.DiffAnchor> postedAnchors = new ArrayList<>(toPost.size());
        List<String> postedKeys = new ArrayList<>(toPost.size());
        for (InlineFeedback finding : toPost) {
            FeedbackAnchor.DiffAnchor diff = (FeedbackAnchor.DiffAnchor) finding.anchor();
            String body = immutablePackage ? appendMarker(finding.body(), finding.marker()) : finding.body();
            threads.add(buildThread(diff, appendCorrelationTag(body, finding.deliveryKey())));
            postedAnchors.add(diff);
            postedKeys.add(finding.deliveryKey());
        }
        seenKeys.addAll(postedKeys);
        int postedBeforeSuppression = preservedSignals.size();
        List<DeliveredSignal> signalsBeforeSuppression = new ArrayList<>(preservedSignals);
        signalsBeforeSuppression.addAll(unsupportedSignals);

        try {
            egressGuard.requireDeliveryAllowed("github.post-inline-feedbackItems");
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
                throw new FeedbackDeliveryException("Null response from AddPullRequestReviewWithThreads");
            }
            gitHubProvider.trackRateLimit(scopeId, response);

            if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                log.warn(
                        "GitHub addPullRequestReview with threads failed: workspaceId={}, errors={}, threadCount={}",
                        scopeId,
                        response.getErrors(),
                        threads.size());
                List<DeliveredSignal> failed = failedSignals(postedAnchors, postedKeys);
                failed.addAll(preservedSignals);
                failed.addAll(unsupportedSignals);
                return new InlineResult(preservedSignals.size(), threads.size() + unsupportedAnchorCount, failed);
            }

            String reviewId =
                    response.field("addPullRequestReview.pullRequestReview.id").getValue();
            List<DeliveredSignal> postedSignals = buildPostedSignals(response, reviewId, postedAnchors, postedKeys);
            postedBeforeSuppression += postedSignals.size();
            signalsBeforeSuppression.addAll(0, postedSignals);

            // Retire prior bot threads whose finding vanished this run (still-seen = posted ∪ preserved).
            int minimized = minimizeVanishedThreads(scopeId, priorByKey.values(), seenKeys);

            log.info(
                    "Posted {} GitHub inline feedbackItems as single review: workspaceId={}, prNodeId={}, preserved={}, minimized={}",
                    threads.size(),
                    scopeId,
                    prNodeId,
                    preservedSignals.size(),
                    minimized);
            List<DeliveredSignal> all = new ArrayList<>(postedSignals);
            all.addAll(preservedSignals);
            all.addAll(unsupportedSignals);
            return new InlineResult(threads.size() + preservedSignals.size(), unsupportedAnchorCount, List.copyOf(all));
        } catch (OutboundEgressSuppressedException e) {
            Set<String> completedKeys = signalsBeforeSuppression.stream()
                    .map(DeliveredSignal::deliveryKey)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<String> suppressedKeys = toPost.stream()
                    .map(InlineFeedback::deliveryKey)
                    .filter(Objects::nonNull)
                    .filter(key -> !completedKeys.contains(key))
                    .toList();
            return InlineResult.suppressed(
                    postedBeforeSuppression, unsupportedAnchorCount, signalsBeforeSuppression, suppressedKeys);
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            log.warn("GitHub inline feedback batch failed: workspaceId={}, threadCount={}", scopeId, threads.size(), e);
            List<DeliveredSignal> failed = failedSignals(postedAnchors, postedKeys);
            failed.addAll(preservedSignals);
            failed.addAll(unsupportedSignals);
            return new InlineResult(preservedSignals.size(), threads.size() + unsupportedAnchorCount, failed);
        }
    }

    /**
     * Minimizes (hides as {@code OUTDATED}) every prior bot thread on the PR — the GitHub analogue of GitLab's
     * stale-note delete. Called on a zero-note re-run so a PR re-reviewed into nothing-inline doesn't keep
     * feedbackItems on code no longer in the diff. GitHub reviews are append-only, so minimize is the only
     * non-destructive way to retire a thread.
     */
    @Override
    public void clearStaleFeedback(SummaryChannel.FeedbackTarget target, String marker) {
        long scopeId = target.ref().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("GitHub rate limit is too low to reconcile stale inline threads");
        }
        PrCoordinates pr = GithubSummaryChannel.parseSubjectExternalId(target.subjectExternalId());
        Map<String, PriorThread> priorByKey = indexPriorThreads(scopeId, pr, null);
        int minimized = minimizeVanishedThreads(scopeId, priorByKey.values(), Set.of());
        if (minimized > 0) {
            log.info("Minimized {} stale GitHub inline threads: workspaceId={}", minimized, scopeId);
        }
    }

    /**
     * Builds {@link DeliveredSignal}s for the posted threads, matching comment node ids back to feedbackItems.
     *
     * <p>Match uses the exact delivery correlation tag embedded in each posted comment body:
     * {@code path:line} is NOT unique — two feedbackItems can anchor to the same line — so a positional or path:line
     * index would hand the second finding the first's comment id (or none), corrupting its ledger external_ref.
     * Unmatched responses retain no comment ID; coordinates alone cannot bind two pieces of feedback.
     */
    private static List<DeliveredSignal> buildPostedSignals(
            ClientGraphQlResponse response,
            @Nullable String reviewId,
            List<FeedbackAnchor.DiffAnchor> anchors,
            List<String> keys) {
        Map<String, String> commentIdByCk = new HashMap<>();
        List<Map<String, Object>> comments = response.field("addPullRequestReview.pullRequestReview.comments.nodes")
                .getValue();
        if (comments != null) {
            for (Map<String, Object> comment : comments) {
                String id = (String) comment.get("id");
                if (id == null) {
                    continue;
                }
                String body = (String) comment.get("body");
                String ck = body == null ? null : parseDeliveryKey(body);
                if (ck != null) {
                    commentIdByCk.putIfAbsent(ck, id);
                }
            }
        }

        List<DeliveredSignal> signals = new ArrayList<>(anchors.size());
        for (int i = 0; i < anchors.size(); i++) {
            FeedbackAnchor.DiffAnchor diff = anchors.get(i);
            String key = keys.get(i);
            String commentId = key == null ? null : commentIdByCk.get(key);
            signals.add(new DeliveredSignal(key, diff, Disposition.POSTED, commentId, reviewId));
        }
        return signals;
    }

    private static List<DeliveredSignal> failedSignals(List<FeedbackAnchor.DiffAnchor> anchors, List<String> keys) {
        List<DeliveredSignal> signals = new ArrayList<>(anchors.size());
        for (int i = 0; i < anchors.size(); i++) {
            signals.add(new DeliveredSignal(keys.get(i), anchors.get(i), Disposition.FAILED, null, null));
        }
        return signals;
    }

    private static String appendMarker(String body, String marker) {
        return body.contains(marker) ? body : body + "\n\n" + marker;
    }

    private Map<String, PriorThread> indexPriorThreads(long scopeId, PrCoordinates pr, @Nullable String marker) {
        Map<String, PriorThread> byKey = new LinkedHashMap<>();
        String after = null;
        try {
            for (int page = 0; page < MAX_THREAD_PAGES; page++) {
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

                if (response == null) {
                    throw new FeedbackDeliveryException("GitHub review-thread lookup returned no response");
                }
                if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                    throw new FeedbackDeliveryException("GitHub review-thread lookup returned errors");
                }
                gitHubProvider.trackRateLimit(scopeId, response);

                List<Map<String, Object>> nodes = response.field("repository.pullRequest.reviewThreads.nodes")
                        .getValue();
                if (nodes != null) {
                    for (Map<String, Object> thread : nodes) {
                        indexThread(thread, marker, byKey);
                    }
                }

                Boolean hasNext = response.field("repository.pullRequest.reviewThreads.pageInfo.hasNextPage")
                        .getValue();
                if (!Boolean.TRUE.equals(hasNext)) {
                    break;
                }
                after = response.field("repository.pullRequest.reviewThreads.pageInfo.endCursor")
                        .getValue();
                if (after == null) {
                    throw new FeedbackDeliveryException("GitHub review-thread pagination lost its cursor");
                }
            }
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitHub review-thread lookup was inconclusive", e);
        }
        return byKey;
    }

    /** Indexes one review thread under the correlation key parsed from its first comment, if it is one of ours. */
    @SuppressWarnings("unchecked")
    private static void indexThread(
            Map<String, Object> thread, @Nullable String marker, Map<String, PriorThread> byKey) {
        String threadId = (String) thread.get("id");
        if (threadId == null) {
            return;
        }
        Object commentsField = thread.get("comments");
        if (!(commentsField instanceof Map<?, ?> commentsMap)) {
            return;
        }
        Object nodes = commentsMap.get("nodes");
        if (!(nodes instanceof List) || ((List<?>) nodes).isEmpty()) {
            return;
        }
        Map<String, Object> firstComment = ((List<Map<String, Object>>) nodes).get(0);
        String body = (String) firstComment.get("body");
        String commentId = (String) firstComment.get("id");
        if (body == null || commentId == null || (marker != null && !body.contains(marker))) {
            return;
        }
        String key = parseDeliveryKey(body);
        if (key == null) {
            return; // human thread or a legacy bot note posted before keys existed — not ours to reconcile
        }
        boolean outdated =
                Boolean.TRUE.equals(thread.get("isOutdated")) || Boolean.TRUE.equals(thread.get("isResolved"));
        byKey.put(key, new PriorThread(key, threadId, commentId, outdated));
    }

    private int minimizeVanishedThreads(long scopeId, Iterable<PriorThread> priorThreads, Set<String> seenKeys) {
        int minimized = 0;
        for (PriorThread prior : priorThreads) {
            if (seenKeys.contains(prior.key()) || prior.outdated()) {
                continue;
            }
            if (minimizeComment(scopeId, prior.commentId())) {
                minimized++;
            }
        }
        return minimized;
    }

    private boolean minimizeComment(long scopeId, String commentId) {
        try {
            egressGuard.requireDeliveryAllowed("github.minimize-inline-finding");
            ClientGraphQlResponse response = gitHubProvider
                    .forScope(scopeId)
                    .documentName("MinimizeComment")
                    .variable("subjectId", commentId)
                    .variable("classifier", OUTDATED_CLASSIFIER)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);

            if (response == null) {
                throw new FeedbackDeliveryException("GitHub stale-thread minimize returned no response");
            }
            if (response.getErrors() != null && !response.getErrors().isEmpty()) {
                throw new FeedbackDeliveryException("GitHub stale-thread minimize returned errors");
            }
            return true;
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitHub stale-thread minimize was inconclusive", e);
        }
    }

    @Nullable
    private static String parseDeliveryKey(String body) {
        Matcher m = CK_TAG.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Appends the hidden per-delivery correlation tag; a null key appends nothing. */
    private static String appendCorrelationTag(String body, @Nullable String deliveryKey) {
        if (deliveryKey == null || deliveryKey.isBlank()) {
            return body;
        }
        return body + "\n<!-- hephaestus-diff-note-ck=" + deliveryKey + " -->";
    }

    /** A prior review thread we posted, matched by the correlation key in its first comment. */
    private record PriorThread(String key, String threadId, String commentId, boolean outdated) {}

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
