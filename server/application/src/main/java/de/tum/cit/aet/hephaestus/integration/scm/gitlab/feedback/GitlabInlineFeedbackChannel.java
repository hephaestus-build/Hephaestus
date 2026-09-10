package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitlabMrResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitlabMrResolver.MrCoordinates;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitlabMrResolver.MrInfo;
import java.io.Serial;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitLab adapter for {@link InlineFeedbackChannel}. Posts inline diff notes one at a
 * time via {@code CreateDiffNote} (GitLab has no batch API). For positions outside the
 * diff hunk, falls back to a regular MR comment with {@code file:line} prefix.
 *
 * <p>Reconciles by {@code recurrenceKey} rather than clear-then-post: each finding's stable key is embedded
 * in the note body as a hidden HTML tag, and before posting we read the MR's existing discussions
 * ({@code GetMergeRequestDiscussions}) and index this reviewer's own prior threads by that key. A finding whose
 * key matches a prior, non-human-replied thread is EDITED in place ({@code UpdateNote}) so a stable finding
 * keeps its single thread across re-runs instead of being deleted and re-created; a human-replied thread is
 * PRESERVED untouched; an unmatched finding is posted as a fresh {@code CreateDiffNote} thread. Prior bot
 * threads whose key is absent from the current run AND have no human reply are the truly-gone ones — those, and
 * only those, are {@code DestroyNote}d. Reconciliation reads are best-effort; a failed read degrades to
 * fresh posts (still keyed) rather than blocking delivery.
 *
 * <p>The {@link #clearStaleFeedback} path remains for the zero-note re-run (the empty-diff pathology) where
 * there are no feedbackItems to reconcile against and every prior note is therefore stale.
 *
 * <p>Non-{@link FeedbackAnchor.DiffAnchor} anchors are counted as failed.
 *
 * <p>Gated on {@code hephaestus.integration.gitlab.enabled=true} to track
 * {@link GitLabGraphQlClientProvider}.
 */
@Component
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitlabInlineFeedbackChannel implements InlineFeedbackChannel {

    private static final Logger log = LoggerFactory.getLogger(GitlabInlineFeedbackChannel.class);

    /**
     * GitLab's max page size for the {@code discussions} connection (the GraphQL {@code first} cap is 100).
     * The note count we post per review is small (~30), but an active MR's TOTAL discussion count (human
     * threads included) routinely exceeds one page, so {@link #fetchAllDiscussions} pages on the cursor.
     */
    private static final int DISCUSSIONS_PAGE_SIZE = 100;

    /** Hard ceiling on discussion pages walked per reconcile, mirroring the sync paginators' MAX_PAGES guard. */
    private static final int MAX_DISCUSSION_PAGES = 50;

    /**
     * Hidden per-finding correlation tag embedded in a note body so a prior thread can be matched back to the
     * finding that produced it across re-runs. Distinct from the run-level {@code marker} (which identifies all
     * hephaestus notes for the zero-note clear path); both coexist in the body. The key is alnum/dash/underscore
     * (a {@link de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint} digest), so no escaping is needed.
     */
    private static final Pattern CK_TAG = Pattern.compile("<!-- hephaestus-diff-note-ck=([A-Za-z0-9_-]+) -->");

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitlabMrResolver mrResolver;
    private final OutboundEgressGuard egressGuard;

    public GitlabInlineFeedbackChannel(
            GitLabGraphQlClientProvider gitLabProvider, GitlabMrResolver mrResolver, OutboundEgressGuard egressGuard) {
        this.gitLabProvider = gitLabProvider;
        this.mrResolver = mrResolver;
        this.egressGuard = egressGuard;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    /**
     * Deletes every marker-bearing inline note on the MR without posting new ones — the clear half of
     * clear-then-post (SPI {@link InlineFeedbackChannel#clearStaleFeedback}). Called on a zero-note re-run so
     * a PR re-reviewed into nothing-inline doesn't keep line-numbered notes on code no longer in the diff.
     */
    @Override
    public void clearStaleFeedback(SummaryChannel.FeedbackTarget target, String marker) {
        if (marker == null || marker.isBlank()) {
            return;
        }
        long scopeId = target.ref().workspaceId();
        if (gitLabProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("GitLab rate limit is too low to reconcile stale inline notes");
        }
        MrCoordinates mr = GitlabMrResolver.parseSubjectExternalId(target.subjectExternalId());
        deleteOldMarkedNotes(scopeId, mr.projectPath(), mr.iid(), marker);
    }

    @Override
    public InlineResult postInlineFeedback(SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems) {
        return reconcileInlineFeedback(target, feedbackItems);
    }

    @Override
    public InlineResult postImmutablePackage(SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems) {
        return reconcileInlineFeedback(target, feedbackItems);
    }

    private InlineResult reconcileInlineFeedback(
            SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems) {
        if (feedbackItems == null || feedbackItems.isEmpty()) {
            return InlineResult.counts(0, 0);
        }
        long scopeId = target.ref().workspaceId();
        if (gitLabProvider.isRateLimitCritical(scopeId)) {
            log.warn(
                    "GitLab rate limit critical — skipping {} inline feedbackItems: workspaceId={}",
                    feedbackItems.size(),
                    scopeId);
            return InlineResult.counts(0, feedbackItems.size());
        }

        MrCoordinates mr = GitlabMrResolver.parseSubjectExternalId(target.subjectExternalId());
        MrInfo mrInfo = mrResolver.resolve(scopeId, mr.projectPath(), mr.iid());
        if (mrInfo.headSha() == null || mrInfo.startSha() == null) {
            log.warn(
                    "GitLab MR missing diffRefs — skipping diff notes: workspaceId={}, mrGid={}",
                    scopeId,
                    mrInfo.globalId());
            return InlineResult.counts(0, feedbackItems.size());
        }

        String marker = feedbackItems.get(0).marker();
        Map<String, PriorThread> priorByKey = indexPriorThreads(scopeId, mr.projectPath(), mr.iid(), marker);

        int posted = 0;
        int failed = 0;
        boolean rateLimited = false;
        Set<String> seenKeys = new HashSet<>();
        // Keys we've already posted/edited a thread for THIS run. Guards the case where two feedbackItems in one
        // batch carry the same non-null recurrenceKey (a fingerprint that escaped upstream dedup): without
        // this, both would createThread a fresh duplicate, and the next run's last-wins index would orphan one
        // permanently (its key stays in seenKeys, so it is never reaped). First wins; the twin is skipped.
        Set<String> processedKeys = new HashSet<>();
        List<DeliveredSignal> signals = new ArrayList<>(feedbackItems.size());

        for (int index = 0; index < feedbackItems.size(); index++) {
            InlineFeedback finding = feedbackItems.get(index);
            int remaining = feedbackItems.size() - index - 1;
            if (!(finding.anchor() instanceof FeedbackAnchor.DiffAnchor diff)) {
                log.warn("Skipping non-diff anchor on GitLab inline feedback: anchor={}", finding.anchor());
                failed++;
                signals.add(failedSignal(finding));
                continue;
            }
            // Register the key as seen BEFORE the blank-body guard: a finding whose key is still present this
            // run must never be reaped by destroyVanishedThreads, regardless of body content. Otherwise a
            // valid-key, blank-body finding would silently delete its own still-current prior thread.
            String key = finding.recurrenceKey();
            if (key != null) {
                seenKeys.add(key);
            }
            if (finding.body() == null || finding.body().isBlank()) {
                continue;
            }

            // Within-batch duplicate fingerprint: this key already produced a thread this run. Skip the twin
            // rather than create a second thread that no future run could reconcile. (Null keys are
            // pre-correlation feedbackItems and are never collapsed.)
            if (key != null && !processedKeys.add(key)) {
                log.warn("Skipping duplicate recurrenceKey within batch: workspaceId={}, key={}", scopeId, key);
                continue;
            }

            PriorThread prior = key == null ? null : priorByKey.get(key);

            // A prior thread a developer engaged with is left exactly as is — neither edited nor deleted.
            if (prior != null && prior.humanReplied()) {
                posted++; // the finding IS represented on the MR, just not by us this run
                signals.add(new DeliveredSignal(
                        key, diff, Disposition.PRESERVED_EXISTING, prior.noteId(), prior.discussionId()));
                continue;
            }

            String body = appendCorrelationTag(
                    appendMarker(GitlabSummaryChannel.escapeSlashCommands(finding.body()), marker), key);

            try {
                Outcome outcome = prior != null
                        ? editInPlace(scopeId, prior, body, diff)
                        : createThread(scopeId, mrInfo, diff, body);
                if (outcome.disposition() == Disposition.FELL_BACK || outcome.disposition() == Disposition.POSTED) {
                    posted++;
                } else {
                    failed++;
                }
                signals.add(new DeliveredSignal(
                        key, diff, outcome.disposition(), outcome.noteId(), outcome.discussionId()));
            } catch (OutboundEgressSuppressedException e) {
                return InlineResult.suppressed(
                        posted, failed, signals, recurrenceKeys(feedbackItems.subList(index, feedbackItems.size())));
            } catch (RateLimitHit e) {
                log.warn("GitLab rate limit hit during diff note posting — stopping: workspaceId={}", scopeId);
                failed += remaining + 1;
                signals.add(failedSignal(finding));
                rateLimited = true;
                break;
            }
        }

        int deletedGone;
        try {
            deletedGone = rateLimited ? 0 : destroyVanishedThreads(scopeId, priorByKey, seenKeys);
        } catch (OutboundEgressSuppressedException e) {
            return InlineResult.suppressed(posted, failed, signals, List.of());
        }

        log.info(
                "Reconciled GitLab inline feedbackItems: posted/edited={}, failed={}, deleted-gone={}, workspaceId={}",
                posted,
                failed,
                deletedGone,
                scopeId);
        return new InlineResult(posted, failed, List.copyOf(signals));
    }

    private static List<String> recurrenceKeys(List<InlineFeedback> feedbackItems) {
        return feedbackItems.stream()
                .map(InlineFeedback::recurrenceKey)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Posts a brand-new diff-note thread; falls back to an MR comment when the line is outside the diff hunk. */
    private Outcome createThread(long scopeId, MrInfo mrInfo, FeedbackAnchor.DiffAnchor diff, String body) {
        try {
            Map<String, Object> position = buildPosition(diff, mrInfo);
            egressGuard.requireDeliveryAllowed("gitlab.post-inline-finding");
            ClientGraphQlResponse response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("CreateDiffNote")
                    .variable("noteableId", mrInfo.globalId())
                    .variable("body", body)
                    .variable("position", position)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);

            if (response == null) {
                log.warn("Null response posting GitLab diff note: workspaceId={}, file={}", scopeId, diff.filePath());
                return Outcome.failed();
            }

            List<String> errors = Objects.requireNonNull(response)
                    .field("createDiffNote.errors")
                    .getValue();
            if (errors != null && !errors.isEmpty()) {
                if (isLineCodeError(errors)) {
                    log.info(
                            "Diff note line outside diff hunk, falling back to MR comment: workspaceId={}, file={}, line={}",
                            scopeId,
                            diff.filePath(),
                            diff.newLineNumber());
                    String noteId = postFallbackComment(scopeId, mrInfo.globalId(), diff, body);
                    return noteId != null ? new Outcome(Disposition.FELL_BACK, noteId, null) : Outcome.failed();
                }
                log.warn(
                        "GitLab createDiffNote failed: workspaceId={}, file={}, line={}, errors={}",
                        scopeId,
                        sanitizeForLog(diff.filePath()),
                        diff.newLineNumber(),
                        sanitizeForLog(errors.toString()));
                return Outcome.failed();
            }

            return new Outcome(Disposition.POSTED, noteIdOf(response), discussionIdOf(response));
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (Exception e) {
            if (isRateLimitError(e)) {
                throw new RateLimitHit(e);
            }
            log.warn(
                    "GitLab diff note failed: workspaceId={}, file={}, line={}",
                    scopeId,
                    sanitizeForLog(diff.filePath()),
                    diff.newLineNumber(),
                    e);
            return Outcome.failed();
        }
    }

    private Outcome editInPlace(long scopeId, PriorThread prior, String body, FeedbackAnchor.DiffAnchor diff) {
        try {
            egressGuard.requireDeliveryAllowed("gitlab.update-inline-finding");
            ClientGraphQlResponse response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("UpdateNote")
                    .variable("id", prior.noteId())
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);

            if (response == null) {
                log.warn("Null response editing GitLab diff note: workspaceId={}, noteId={}", scopeId, prior.noteId());
                return Outcome.failed();
            }

            List<String> errors =
                    Objects.requireNonNull(response).field("updateNote.errors").getValue();
            if (errors != null && !errors.isEmpty()) {
                log.warn(
                        "GitLab updateNote failed: workspaceId={}, noteId={}, errors={}",
                        scopeId,
                        prior.noteId(),
                        sanitizeForLog(errors.toString()));
                return Outcome.failed();
            }
            return new Outcome(Disposition.POSTED, prior.noteId(), prior.discussionId());
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (Exception e) {
            if (isRateLimitError(e)) {
                throw new RateLimitHit(e);
            }
            log.warn(
                    "GitLab diff note edit failed: workspaceId={}, file={}, line={}",
                    scopeId,
                    sanitizeForLog(diff.filePath()),
                    diff.newLineNumber(),
                    e);
            return Outcome.failed();
        }
    }

    private Map<String, PriorThread> indexPriorThreads(long scopeId, String projectPath, int mrIid, String marker) {
        Map<String, PriorThread> byKey = new LinkedHashMap<>();
        if (marker == null || marker.isBlank()) {
            return byKey;
        }
        try {
            for (Map<String, Object> discussion : fetchAllDiscussions(scopeId, projectPath, mrIid)) {
                indexDiscussion(discussion, marker, byKey);
            }
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitLab discussion lookup was inconclusive", e);
        }
        return byKey;
    }

    private List<Map<String, Object>> fetchAllDiscussions(long scopeId, String projectPath, int mrIid) {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        int page = 0;
        while (page < MAX_DISCUSSION_PAGES) {
            ClientGraphQlResponse response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("GetMergeRequestDiscussions")
                    .variable("fullPath", projectPath)
                    .variable("iid", String.valueOf(mrIid))
                    .variable("first", DISCUSSIONS_PAGE_SIZE)
                    .variable("after", cursor)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);

            if (response == null) {
                throw new FeedbackDeliveryException("GitLab discussion lookup returned no response");
            }
            if (!Objects.requireNonNull(response).getErrors().isEmpty()) {
                throw new FeedbackDeliveryException("GitLab discussion lookup returned errors");
            }
            List<Map<String, Object>> nodes = Objects.requireNonNull(response)
                    .field("project.mergeRequest.discussions.nodes")
                    .getValue();
            if (nodes != null) {
                all.addAll(nodes);
            }
            GitLabPageInfo pageInfo = Objects.requireNonNull(response)
                    .field("project.mergeRequest.discussions.pageInfo")
                    .toEntity(GitLabPageInfo.class);
            page++;
            if (pageInfo == null) {
                throw new FeedbackDeliveryException("GitLab discussion pagination was incomplete");
            }
            if (!pageInfo.hasNextPage()) {
                break;
            }
            if (pageInfo.endCursor() == null) {
                throw new FeedbackDeliveryException("GitLab discussion pagination lost its cursor");
            }
            cursor = pageInfo.endCursor();
        }
        return all;
    }

    private static void indexDiscussion(Map<String, Object> discussion, String marker, Map<String, PriorThread> byKey) {
        List<Map<String, Object>> notes = notesOf(discussion);
        if (notes.isEmpty()) {
            return;
        }
        String discussionId = (String) discussion.get("id");
        String botNoteId = null;
        String botKey = null;
        boolean humanReplied = false;
        for (Map<String, Object> note : notes) {
            if (Boolean.TRUE.equals(note.get("system"))) {
                continue; // GitLab system notes ("changed the description", etc.) never count.
            }
            String body = (String) note.get("body");
            String noteId = (String) note.get("id");
            if (noteId == null || body == null) {
                continue;
            }
            if (body.contains(marker)) {
                botNoteId = noteId;
                botKey = parseObservationFingerprint(body);
            } else {
                humanReplied = true; // a person (or other tool) participated in this thread
            }
        }
        if (botKey == null || botNoteId == null) {
            return; // not one of ours, or a legacy bot note posted before keys existed — leave the clear path to it
        }
        byKey.put(botKey, new PriorThread(botKey, botNoteId, discussionId, humanReplied));
    }

    private int destroyVanishedThreads(long scopeId, Map<String, PriorThread> priorByKey, Set<String> seenKeys) {
        int deleted = 0;
        for (PriorThread prior : priorByKey.values()) {
            if (seenKeys.contains(prior.key()) || prior.humanReplied()) {
                continue;
            }
            if (destroyNote(scopeId, prior.noteId())) {
                deleted++;
            }
        }
        return deleted;
    }

    private static DeliveredSignal failedSignal(InlineFeedback finding) {
        return new DeliveredSignal(finding.recurrenceKey(), finding.anchor(), Disposition.FAILED, null, null);
    }

    @Nullable
    private static String noteIdOf(ClientGraphQlResponse response) {
        return Objects.requireNonNull(response).field("createDiffNote.note.id").getValue();
    }

    @Nullable
    private static String discussionIdOf(ClientGraphQlResponse response) {
        return Objects.requireNonNull(response)
                .field("createDiffNote.note.discussion.id")
                .getValue();
    }

    @Nullable
    private static String parseObservationFingerprint(String body) {
        Matcher m = CK_TAG.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Appends the hidden per-finding correlation tag; a null key (pre-correlation finding) appends nothing. */
    private static String appendCorrelationTag(String body, @Nullable String recurrenceKey) {
        if (recurrenceKey == null || recurrenceKey.isBlank()) {
            return body;
        }
        return body + "\n<!-- hephaestus-diff-note-ck=" + recurrenceKey + " -->";
    }

    /** A prior diff-note thread we posted, matched by its embedded correlation key. */
    private record PriorThread(
            String key, String noteId, @Nullable String discussionId, boolean humanReplied) {}

    /** Result of a single create/edit attempt: what happened plus the durable note/discussion handles. */
    private record Outcome(
            Disposition disposition,
            @Nullable String noteId,
            @Nullable String discussionId) {
        static Outcome failed() {
            return new Outcome(Disposition.FAILED, null, null);
        }
    }

    /** Signals the per-finding loop to stop and fail the rest of the batch on a rate-limit hit. */
    private static final class RateLimitHit extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private RateLimitHit(Throwable cause) {
            super(cause);
        }
    }

    private static Map<String, Object> buildPosition(FeedbackAnchor.DiffAnchor diff, MrInfo mrInfo) {
        Map<String, Object> position = new HashMap<>();
        position.put("headSha", mrInfo.headSha());
        position.put("startSha", mrInfo.startSha());
        position.put("baseSha", mrInfo.baseSha());
        // oldPath required by GitLab to match the note position to the diff file in the
        // Changes tab. Correct for new + modified files. For renamed files oldPath
        // should be the pre-rename path, but the DiffAnchor only carries the new path.
        // Renames are rare in student assignments; if needed, resolve from MR diff metadata.
        Map<String, String> paths = new HashMap<>();
        paths.put("newPath", diff.filePath());
        paths.put("oldPath", diff.filePath());
        position.put("paths", paths);
        position.put("newLine", diff.newLineNumber());
        return position;
    }

    /**
     * Removes this reviewer's own stale inline notes before re-posting — but NEVER a thread a developer has
     * replied to (ADR 0021 re-review UX). We query discussions (not flat notes) so a marker-bearing note can
     * be judged in the context of its thread: a discussion that contains any non-system note WITHOUT our
     * marker means a human (or another tool) joined it, and deleting it would destroy their words. Such
     * threads are PRESERVED and left to the platform's own code-change-driven outdating — we deliberately do
     * not auto-resolve on non-detection, which is unsafe under the detector's run-to-run non-determinism.
     */
    private void deleteOldMarkedNotes(long scopeId, String projectPath, int mrIid, String marker) {
        if (marker == null || marker.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> discussions = fetchAllDiscussions(scopeId, projectPath, mrIid);
            if (discussions.isEmpty()) {
                return;
            }

            int deleted = 0;
            int preserved = 0;
            for (Map<String, Object> discussion : discussions) {
                List<Map<String, Object>> notes = notesOf(discussion);
                if (notes.isEmpty()) {
                    continue;
                }

                List<String> markedNoteIds = new ArrayList<>();
                boolean humanReplied = false;
                for (Map<String, Object> note : notes) {
                    if (Boolean.TRUE.equals(note.get("system"))) {
                        continue; // GitLab system notes ("changed the description", etc.) never count.
                    }
                    String body = (String) note.get("body");
                    String noteId = (String) note.get("id");
                    if (noteId == null || body == null) {
                        continue;
                    }
                    if (body.contains(marker)) {
                        markedNoteIds.add(noteId);
                    } else {
                        humanReplied = true; // a person (or other tool) participated in this thread
                    }
                }

                if (markedNoteIds.isEmpty()) {
                    continue;
                }
                if (humanReplied) {
                    preserved += markedNoteIds.size();
                    continue; // never destroy a thread a developer engaged with
                }
                for (String noteId : markedNoteIds) {
                    if (destroyNote(scopeId, noteId)) {
                        deleted++;
                    }
                }
            }

            if (deleted > 0 || preserved > 0) {
                log.info(
                        "Reconciled stale inline notes: deleted={}, preserved(human-replied)={}, workspaceId={}, mr={}!{}",
                        deleted,
                        preserved,
                        scopeId,
                        projectPath,
                        mrIid);
            }
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitLab stale-note reconciliation was inconclusive", e);
        }
    }

    /** Safely pulls a discussion's {@code notes.nodes} list, tolerating nulls in the GraphQL map. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> notesOf(Map<String, Object> discussion) {
        Object notesField = discussion.get("notes");
        if (!(notesField instanceof Map<?, ?> notesMap)) {
            return List.of();
        }
        Object nodes = notesMap.get("nodes");
        return nodes instanceof List ? (List<Map<String, Object>>) nodes : List.of();
    }

    private boolean destroyNote(long scopeId, String noteId) {
        try {
            egressGuard.requireDeliveryAllowed("gitlab.delete-inline-finding");
            ClientGraphQlResponse deleteResponse = gitLabProvider
                    .forScope(scopeId)
                    .documentName("DestroyNote")
                    .variable("noteId", noteId)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (deleteResponse == null) {
                throw new FeedbackDeliveryException("GitLab stale-note deletion returned no response");
            }
            List<String> errors = deleteResponse.field("destroyNote.errors").getValue();
            if (errors == null || errors.isEmpty()) {
                return true;
            }
            throw new FeedbackDeliveryException("GitLab stale-note deletion returned errors");
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitLab stale-note deletion was inconclusive", e);
        }
    }

    /**
     * Posts an out-of-hunk finding as a plain MR comment, prefixed with its {@code file:line} so the location is
     * still legible. {@code markedBody} already carries the marker + correlation tag (so a fallback comment is
     * reconciled like any other note); returns the new note id or {@code null} on failure.
     */
    @Nullable
    private String postFallbackComment(
            long scopeId, String mrGlobalId, FeedbackAnchor.DiffAnchor diff, String markedBody) {
        try {
            String fallbackBody = String.format("**`%s:%d`**%n%n%s", diff.filePath(), diff.newLineNumber(), markedBody);
            egressGuard.requireDeliveryAllowed("gitlab.post-inline-fallback");
            ClientGraphQlResponse response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("CreateMergeRequestNote")
                    .variable("noteableId", mrGlobalId)
                    .variable("body", fallbackBody)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);

            if (response == null) {
                log.warn("Null response posting fallback MR comment: workspaceId={}", scopeId);
                return null;
            }

            List<String> errors =
                    Objects.requireNonNull(response).field("createNote.errors").getValue();
            if (errors != null && !errors.isEmpty()) {
                log.warn(
                        "Fallback MR comment failed: workspaceId={}, errors={}",
                        scopeId,
                        sanitizeForLog(errors.toString()));
                return null;
            }
            return Objects.requireNonNull(response).field("createNote.note.id").getValue();
        } catch (OutboundEgressSuppressedException e) {
            throw e;
        } catch (Exception e) {
            log.warn(
                    "Fallback MR comment failed: workspaceId={}, file={}", scopeId, sanitizeForLog(diff.filePath()), e);
            return null;
        }
    }

    /** Appends the run-level marker to a note body; a blank marker appends nothing. */
    private static String appendMarker(String body, String marker) {
        if (marker == null || marker.isBlank()) {
            return body;
        }
        return body + "\n" + marker;
    }

    private static boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("rate limit") || message.contains("429"));
    }

    private static boolean isLineCodeError(List<String> errors) {
        return errors.stream()
                .anyMatch(e ->
                        e.toLowerCase().contains("line code") || e.toLowerCase().contains("line_code"));
    }
}
