package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * GitLab adapter for {@link InlineFeedbackChannel}. Posts inline diff notes one at a time via
 * {@code CreateDiffNote} (GitLab has no batch API); a position GitLab rejects as outside the diff falls back to a
 * merge request comment headed by its {@code file:line}.
 *
 * <p>Each note carries the package marker and a hidden correlation tag with its delivery key, and is anchored to
 * the reviewed commit, never to a newer head. Before any create, the merge request's discussions are read
 * completely and the copies of the package are indexed by key; an item with a copy is never posted again, and no
 * copy is ever edited or deleted. Each create is fenced on its own, so a stop between two notes leaves the second
 * provably unrequested.
 *
 * <p>Every readback mode renders the same body: GitLab copies always carried the package marker.
 *
 * <p>Gated on {@code hephaestus.integration.gitlab.enabled=true} to track {@link GitLabGraphQlClientProvider}.
 */
@Component
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabInlineFeedbackChannel implements InlineFeedbackChannel {

    private static final Logger log = LoggerFactory.getLogger(GitLabInlineFeedbackChannel.class);

    /** GitLab's max page size for the {@code discussions} connection (the GraphQL {@code first} cap is 100). */
    private static final int DISCUSSIONS_PAGE_SIZE = 100;

    /** Hard ceiling on discussion pages walked per scan; a scan that reaches it proves nothing. */
    private static final int MAX_DISCUSSION_PAGES = 50;

    /** Hidden per-delivery correlation tag; the key is alnum/dash/underscore/colon, so no escaping is needed. */
    private static final Pattern CK_TAG = Pattern.compile("<!-- hephaestus-diff-note-ck=([A-Za-z0-9_:-]+) -->");

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitLabMrResolver mrResolver;
    private final OutboundEgressGuard egressGuard;

    public GitLabInlineFeedbackChannel(
            GitLabGraphQlClientProvider gitLabProvider, GitLabMrResolver mrResolver, OutboundEgressGuard egressGuard) {
        this.gitLabProvider = gitLabProvider;
        this.mrResolver = mrResolver;
        this.egressGuard = egressGuard;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
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
        String revision = target.reviewedRevision();
        boolean rateLimited = gitLabProvider.isRateLimitCritical(scopeId);
        if (rateLimited || revision == null || revision.isBlank()) {
            // Without the reviewed commit no note can be anchored where the review read the code.
            log.warn(
                    "GitLab diff notes not requested: workspaceId={}, rateLimited={}, reviewedRevision={}",
                    scopeId,
                    rateLimited,
                    revision);
            return InlineResult.of(notSent(feedbackItems));
        }
        MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(target.subjectExternalId());
        MrInfo mrInfo = mrResolver.resolve(scopeId, mr.projectPath(), mr.iid());
        if (mrInfo.startSha() == null) {
            log.warn(
                    "GitLab MR missing diffRefs — not requesting diff notes: workspaceId={}, mrGid={}",
                    scopeId,
                    mrInfo.globalId());
            return InlineResult.of(notSent(feedbackItems));
        }
        Map<String, Copy> copies = indexCopies(scopeId, mr, feedbackItems, revision);

        List<DeliveredSignal> completed = new ArrayList<>(feedbackItems.size());
        Set<String> processedKeys = new HashSet<>();
        for (int index = 0; index < feedbackItems.size(); index++) {
            InlineFeedback item = feedbackItems.get(index);
            String key = item.deliveryKey();
            if (key != null && !processedKeys.add(key)) {
                continue; // one copy per delivery key
            }
            if (!(item.anchor() instanceof FeedbackAnchor.DiffAnchor diff)
                    || item.body().isBlank()
                    || key == null) {
                completed.add(DeliveredSignal.notSent(key, item.anchor()));
                continue;
            }
            Copy copy = copies.get(key);
            if (copy != null) {
                // A copy that does not verify proves neither delivery nor absence: nothing is created next to it.
                completed.add(copy.verified() ? copy.preserved(item) : DeliveredSignal.notSent(key, diff));
                continue;
            }
            List<InlineFeedback> rest = feedbackItems.subList(index, feedbackItems.size());
            try {
                egressGuard.requireDeliveryAllowed("gitlab.post-inline-feedback");
            } catch (OutboundEgressSuppressedException e) {
                completed.addAll(notSent(rest));
                return InlineResult.suppressed(completed, deliveryKeys(rest));
            }
            if (!fence.beforeCreate(List.of(item), List.copyOf(completed))) {
                completed.addAll(notSent(rest));
                return InlineResult.of(completed);
            }
            Attempt attempt = createThread(scopeId, mrInfo, revision, diff, item, fence, completed);
            completed.add(attempt.signal());
            List<InlineFeedback> after = feedbackItems.subList(index + 1, feedbackItems.size());
            if (attempt.suppressed()) {
                completed.addAll(notSent(after));
                return InlineResult.suppressed(completed, deliveryKeys(rest));
            }
            if (attempt.stop()) {
                completed.addAll(notSent(after));
                break;
            }
        }
        return InlineResult.of(completed);
    }

    @Override
    public @Nullable List<DeliveredSignal> findPosted(
            SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedbackItems, Readback readback) {
        if (feedbackItems.isEmpty()) {
            return List.of();
        }
        long scopeId = target.ref().workspaceId();
        if (gitLabProvider.isRateLimitCritical(scopeId)
                || feedbackItems.stream().anyMatch(item -> item.deliveryKey() == null)) {
            return null;
        }
        try {
            MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(target.subjectExternalId());
            Map<String, Copy> copies = indexCopies(scopeId, mr, feedbackItems, target.reviewedRevision());
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
     * Requests one diff note. Only GitLab's line-code validation answering a valid response with no top-level
     * error and an explicit {@code note: null} lets the fallback follow: the position is validated before the note
     * is saved, so that answer proves no note exists. Any other failure, a missing response or a partial note may
     * still have created one.
     */
    private Attempt createThread(
            long scopeId,
            MrInfo mrInfo,
            String revision,
            FeedbackAnchor.DiffAnchor diff,
            InlineFeedback item,
            WriteFence fence,
            List<DeliveredSignal> completed) {
        String key = item.deliveryKey();
        ClientGraphQlResponse response;
        try {
            response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("CreateDiffNote")
                    .variable("noteableId", mrInfo.globalId())
                    .variable("body", postedBody(item))
                    .variable("position", buildPosition(diff, mrInfo, revision))
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (Exception e) {
            log.warn(
                    "GitLab diff note outcome unknown: workspaceId={}, file={}, line={}",
                    scopeId,
                    sanitizeForLog(diff.filePath()),
                    diff.newLineNumber(),
                    e);
            return new Attempt(DeliveredSignal.attempted(key, diff), isRateLimitError(e), false);
        }
        if (response == null) {
            log.warn("Null response posting GitLab diff note: workspaceId={}, file={}", scopeId, diff.filePath());
            return new Attempt(DeliveredSignal.attempted(key, diff), false, false);
        }
        Map<String, Object> payload = response.field("createDiffNote").getValue();
        Object note = payload == null ? null : payload.get("note");
        if (note instanceof Map<?, ?> created && created.get("id") instanceof String noteId && !noteId.isBlank()) {
            String discussionId = created.get("discussion") instanceof Map<?, ?> discussion
                            && discussion.get("id") instanceof String id
                    ? id
                    : null;
            String url = created.get("url") instanceof String noteUrl ? noteUrl : null;
            return new Attempt(
                    new DeliveredSignal(key, diff, Disposition.POSTED, noteId, discussionId, url, true), false, false);
        }
        if (response.isValid()
                && response.getErrors().isEmpty()
                && payload != null
                && payload.containsKey("note")
                && note == null
                && payload.get("errors") instanceof List<?> errors
                && isLineCodeError(errors)) {
            log.info(
                    "Diff note line outside diff hunk, falling back to MR comment: workspaceId={}, file={}, line={}",
                    scopeId,
                    diff.filePath(),
                    diff.newLineNumber());
            return postFallbackComment(scopeId, mrInfo.globalId(), diff, item, fence, completed);
        }
        log.warn(
                "GitLab createDiffNote returned no note: workspaceId={}, file={}, line={}",
                scopeId,
                sanitizeForLog(diff.filePath()),
                diff.newLineNumber());
        return new Attempt(DeliveredSignal.attempted(key, diff), false, false);
    }

    /**
     * Posts out-of-hunk feedback as a merge request comment headed by its location, fenced like any create. The
     * diff note was proven never created, so a refused fallback reports the item as never sent.
     */
    private Attempt postFallbackComment(
            long scopeId,
            String mrGlobalId,
            FeedbackAnchor.DiffAnchor diff,
            InlineFeedback item,
            WriteFence fence,
            List<DeliveredSignal> completed) {
        String key = item.deliveryKey();
        try {
            egressGuard.requireDeliveryAllowed("gitlab.post-inline-fallback");
        } catch (OutboundEgressSuppressedException e) {
            return new Attempt(DeliveredSignal.notSent(key, diff), true, true);
        }
        if (!fence.beforeCreate(List.of(item), List.copyOf(completed))) {
            return new Attempt(DeliveredSignal.notSent(key, diff), true, false);
        }
        ClientGraphQlResponse response;
        try {
            response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("CreateMergeRequestNote")
                    .variable("noteableId", mrGlobalId)
                    .variable("body", fallbackBody(diff, item))
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (Exception e) {
            log.warn(
                    "Fallback MR comment outcome unknown: workspaceId={}, file={}",
                    scopeId,
                    sanitizeForLog(diff.filePath()),
                    e);
            return new Attempt(DeliveredSignal.attempted(key, diff), isRateLimitError(e), false);
        }
        Map<String, Object> payload =
                response == null ? null : response.field("createNote").getValue();
        if (payload != null
                && payload.get("note") instanceof Map<?, ?> created
                && created.get("id") instanceof String noteId
                && !noteId.isBlank()) {
            String url = created.get("url") instanceof String noteUrl ? noteUrl : null;
            return new Attempt(
                    new DeliveredSignal(key, diff, Disposition.FELL_BACK, noteId, null, url, true), false, false);
        }
        log.warn("Fallback MR comment returned no note: workspaceId={}", scopeId);
        return new Attempt(DeliveredSignal.attempted(key, diff), false, false);
    }

    /**
     * The package's copies on the merge request, by delivery key, from a complete scan: a page budget, a lost
     * cursor, a missing connection or identity, or a discussion with more notes than one read returns fails the
     * scan instead of passing for absence. A key whose copies do not verify is kept as a conflict.
     */
    private Map<String, Copy> indexCopies(
            long scopeId, MrCoordinates mr, List<InlineFeedback> items, @Nullable String revision) {
        String marker = items.getFirst().marker();
        Map<String, InlineFeedback> expected = new HashMap<>();
        for (InlineFeedback item : items) {
            if (item.deliveryKey() != null) expected.putIfAbsent(item.deliveryKey(), item);
        }
        Map<String, Copy> copies = new LinkedHashMap<>();
        try {
            String cursor = null;
            for (int page = 1; ; page++) {
                ClientGraphQlResponse response = gitLabProvider
                        .forScope(scopeId)
                        .documentName("GetMergeRequestDiscussions")
                        .variable("fullPath", mr.projectPath())
                        .variable("iid", String.valueOf(mr.iid()))
                        .variable("first", DISCUSSIONS_PAGE_SIZE)
                        .variable("after", cursor)
                        .execute()
                        .block(GRAPHQL_TIMEOUT);
                if (response == null || !response.getErrors().isEmpty()) {
                    throw new FeedbackDeliveryException("GitLab discussion lookup returned no answer");
                }
                String currentUserId = response.field("currentUser.id").getValue();
                Map<String, Object> connection =
                        response.field("project.mergeRequest.discussions").getValue();
                if (currentUserId == null
                        || currentUserId.isBlank()
                        || connection == null
                        || !(connection.get("nodes") instanceof List<?> discussions)
                        || !(connection.get("pageInfo") instanceof Map<?, ?> pageInfo)) {
                    throw new FeedbackDeliveryException("GitLab discussion lookup was incomplete");
                }
                for (Object discussion : discussions) {
                    indexDiscussion(discussion, marker, currentUserId, revision, expected, copies);
                }
                Object hasNextPage = pageInfo.get("hasNextPage");
                if (Boolean.FALSE.equals(hasNextPage)) {
                    return copies;
                }
                if (!Boolean.TRUE.equals(hasNextPage)
                        || page == MAX_DISCUSSION_PAGES
                        || !(pageInfo.get("endCursor") instanceof String next)
                        || next.isBlank()
                        || next.equals(cursor)) {
                    throw new FeedbackDeliveryException("GitLab discussion pagination did not reach its end");
                }
                cursor = next;
            }
        } catch (FeedbackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedbackDeliveryException("GitLab discussion lookup was inconclusive", e);
        }
    }

    private static void indexDiscussion(
            @Nullable Object node,
            String marker,
            String currentUserId,
            @Nullable String revision,
            Map<String, InlineFeedback> expected,
            Map<String, Copy> copies) {
        if (!(node instanceof Map<?, ?> discussion)
                || !(discussion.get("notes") instanceof Map<?, ?> connection)
                || !(connection.get("nodes") instanceof List<?> notes)
                || !(connection.get("pageInfo") instanceof Map<?, ?> pageInfo)
                || !Boolean.FALSE.equals(pageInfo.get("hasNextPage"))) {
            throw new FeedbackDeliveryException("A GitLab discussion was not read completely");
        }
        String discussionId = discussion.get("id") instanceof String id ? id : null;
        for (Object entry : notes) {
            if (!(entry instanceof Map<?, ?> note)
                    || !(note.get("body") instanceof String body)
                    || !(note.get("id") instanceof String noteId)
                    || noteId.isBlank()) {
                throw new FeedbackDeliveryException("A GitLab note was not read completely");
            }
            if (Boolean.TRUE.equals(note.get("system")) || !body.contains(marker)) continue;
            String key = parseDeliveryKey(body);
            if (key == null) {
                continue;
            }
            InlineFeedback item = expected.get(key);
            boolean verified = item != null
                    && note.get("author") instanceof Map<?, ?> author
                    && currentUserId.equals(author.get("id"))
                    && sameCopy(note, body, item, revision);
            String url = note.get("url") instanceof String noteUrl ? noteUrl : null;
            Copy previous = copies.get(key);
            if (verified && (previous == null || !previous.verified())) {
                copies.put(key, new Copy(true, noteId, discussionId, url));
            } else if (!verified && previous == null) {
                copies.put(key, new Copy(false, noteId, discussionId, url));
            }
        }
    }

    /**
     * The exact body this channel posts for the item: as a diff note at its line on the reviewed commit, or as the
     * fallback comment. This adapter places a range at its end line.
     */
    private static boolean sameCopy(Map<?, ?> note, String body, InlineFeedback item, @Nullable String revision) {
        if (!(item.anchor() instanceof FeedbackAnchor.DiffAnchor diff)) {
            return false;
        }
        if (body.equals(fallbackBody(diff, item))) {
            return true;
        }
        return revision != null
                && body.equals(postedBody(item))
                && note.get("position") instanceof Map<?, ?> position
                && diff.filePath().equals(position.get("newPath"))
                && position.get("newLine") instanceof Number line
                && line.intValue() == diff.newLineNumber()
                && position.get("diffRefs") instanceof Map<?, ?> diffRefs
                && revision.equals(diffRefs.get("headSha"));
    }

    private static List<DeliveredSignal> notSent(List<InlineFeedback> items) {
        return items.stream()
                .map(item -> DeliveredSignal.notSent(item.deliveryKey(), item.anchor()))
                .toList();
    }

    private static List<String> deliveryKeys(List<InlineFeedback> feedbackItems) {
        return feedbackItems.stream()
                .map(InlineFeedback::deliveryKey)
                .filter(Objects::nonNull)
                .toList();
    }

    @Nullable
    private static String parseDeliveryKey(String body) {
        Matcher m = CK_TAG.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** The diff-note body: slash commands escaped, then the package marker and the correlation tag. */
    private static String postedBody(InlineFeedback item) {
        String body = GitLabSummaryChannel.escapeSlashCommands(item.body());
        if (!item.marker().isBlank()) {
            body = body + "\n" + item.marker();
        }
        String key = item.deliveryKey();
        return key == null || key.isBlank() ? body : body + "\n<!-- hephaestus-diff-note-ck=" + key + " -->";
    }

    private static String fallbackBody(FeedbackAnchor.DiffAnchor diff, InlineFeedback item) {
        return "**`" + diff.filePath() + ":" + diff.newLineNumber() + "`**\n\n" + postedBody(item);
    }

    /** A copy of a package item; {@code verified} false when it carries the key but not the item. */
    private record Copy(
            boolean verified,
            String noteId,
            @Nullable String discussionId,
            @Nullable String url) {
        DeliveredSignal preserved(InlineFeedback item) {
            return new DeliveredSignal(
                    item.deliveryKey(), item.anchor(), Disposition.PRESERVED_EXISTING, noteId, discussionId, url);
        }
    }

    /** One create request's outcome, and whether the rest of the package must wait. */
    private record Attempt(DeliveredSignal signal, boolean stop, boolean suppressed) {}

    /** The position on the reviewed commit; base and start come from the merge request's diff. */
    private static Map<String, Object> buildPosition(FeedbackAnchor.DiffAnchor diff, MrInfo mrInfo, String revision) {
        Map<String, Object> position = new HashMap<>();
        position.put("headSha", revision);
        position.put("startSha", mrInfo.startSha());
        position.put("baseSha", mrInfo.baseSha());
        // GitLab matches the position to the diff file by oldPath too. For a renamed file it should be the
        // pre-rename path, but the DiffAnchor only carries the new one.
        Map<String, String> paths = new HashMap<>();
        paths.put("newPath", diff.filePath());
        paths.put("oldPath", diff.filePath());
        position.put("paths", paths);
        position.put("newLine", diff.newLineNumber());
        return position;
    }

    private static boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("rate limit") || message.contains("429"));
    }

    private static boolean isLineCodeError(List<?> errors) {
        return errors.stream()
                .map(error -> String.valueOf(error).toLowerCase(Locale.ROOT))
                .anyMatch(error -> error.contains("line code") || error.contains("line_code"));
    }
}
