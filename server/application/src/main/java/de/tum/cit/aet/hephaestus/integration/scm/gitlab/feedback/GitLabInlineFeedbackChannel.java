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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrInfo;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitLab adapter for {@link InlineFeedbackChannel}. Posts each piece of located feedback as an ordinary merge
 * request comment via {@code CreateMergeRequestNote}, headed by a static link to its file and lines at the reviewed
 * commit. GitLab attaches an ordinary comment to no line, so the link is the location: no diff note is created,
 * resolved, edited or deleted to place it.
 *
 * <p>Each comment carries the package marker and a hidden correlation tag with its delivery key, and links to the
 * reviewed commit, never to a newer head. Before any create, the merge request's discussions are read completely
 * and the copies of the package are indexed by key; an item with a copy is never posted again, and no copy is ever
 * edited or deleted. Diff notes and fallback comments this channel posted earlier stay readable as copies. Each
 * create is fenced on its own, so a stop between two comments leaves the second provably unrequested.
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

    /** A commit id, the only revision a location link may name. */
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitLabMrResolver mrResolver;
    private final GitLabTokenService tokenService;
    private final OutboundEgressGuard egressGuard;

    public GitLabInlineFeedbackChannel(
            GitLabGraphQlClientProvider gitLabProvider,
            GitLabMrResolver mrResolver,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard) {
        this.gitLabProvider = gitLabProvider;
        this.mrResolver = mrResolver;
        this.tokenService = tokenService;
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
        if (rateLimited || revision == null || !COMMIT_SHA.matcher(revision).matches()) {
            // Without the reviewed commit no comment can link to the code the review read.
            log.warn(
                    "GitLab location comments not requested: workspaceId={}, rateLimited={}, reviewedRevision={}",
                    scopeId,
                    rateLimited,
                    sanitizeForLog(revision));
            return InlineResult.of(notSent(feedbackItems));
        }
        MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(target.subjectExternalId());
        MrInfo mrInfo = mrResolver.resolve(scopeId, mr.projectPath(), mr.iid());
        String projectUrl = projectUrl(scopeId, mr);
        Map<String, Copy> copies = indexCopies(scopeId, mr, feedbackItems, revision, projectUrl);

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
            if (diff.side() != FeedbackAnchor.DiffSide.RIGHT) {
                // The link names lines of the reviewed head; old-side lines have no address there.
                completed.add(DeliveredSignal.notSent(key, diff));
                continue;
            }
            List<InlineFeedback> rest = feedbackItems.subList(index, feedbackItems.size());
            try {
                egressGuard.requireDeliveryAllowed("gitlab.post-inline-feedback");
            } catch (OutboundEgressSuppressedException e) {
                completed.addAll(unrequested(rest, copies));
                return InlineResult.suppressed(completed, deliveryKeys(rest));
            }
            if (!fence.beforeCreate(List.of(item), List.copyOf(completed))) {
                completed.addAll(unrequested(rest, copies));
                return InlineResult.of(completed);
            }
            Attempt attempt = createLocationComment(
                    scopeId, mrInfo.globalId(), locationBody(projectUrl, revision, diff, item), diff, key);
            completed.add(attempt.signal());
            if (attempt.stop()) {
                completed.addAll(unrequested(feedbackItems.subList(index + 1, feedbackItems.size()), copies));
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
            Map<String, Copy> copies =
                    indexCopies(scopeId, mr, feedbackItems, target.reviewedRevision(), projectUrl(scopeId, mr));
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
     * Requests one ordinary merge request comment. It attaches to no line, so no answer can prove a position was
     * refused before saving: a failure, a missing response or an answer without a note may still have created one.
     */
    private Attempt createLocationComment(
            long scopeId, String mrGlobalId, String body, FeedbackAnchor.DiffAnchor diff, String key) {
        ClientGraphQlResponse response;
        try {
            response = gitLabProvider
                    .forScope(scopeId)
                    .documentName("CreateMergeRequestNote")
                    .variable("noteableId", mrGlobalId)
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
        } catch (Exception e) {
            log.warn(
                    "GitLab location comment outcome unknown: workspaceId={}, file={}, line={}",
                    scopeId,
                    sanitizeForLog(diff.filePath()),
                    diff.newLineNumber(),
                    e);
            return new Attempt(DeliveredSignal.attempted(key, diff), isRateLimitError(e));
        }
        Map<String, Object> payload =
                response == null ? null : response.field("createNote").getValue();
        if (payload != null
                && payload.get("note") instanceof Map<?, ?> created
                && created.get("id") instanceof String noteId
                && !noteId.isBlank()) {
            String url = created.get("url") instanceof String noteUrl ? noteUrl : null;
            return new Attempt(
                    new DeliveredSignal(
                            key, diff, Disposition.POSTED, noteId, null, url, true, Placement.LOCATION_COMMENT),
                    false);
        }
        log.warn(
                "GitLab location comment returned no note: workspaceId={}, file={}, line={}",
                scopeId,
                sanitizeForLog(diff.filePath()),
                diff.newLineNumber());
        return new Attempt(DeliveredSignal.attempted(key, diff), false);
    }

    /**
     * The package's copies on the merge request, by delivery key, from a complete scan: a page budget, a lost
     * cursor, a missing connection or identity, or a discussion with more notes than one read returns fails the
     * scan instead of passing for absence. A key whose copies do not verify is kept as a conflict.
     */
    private Map<String, Copy> indexCopies(
            long scopeId, MrCoordinates mr, List<InlineFeedback> items, @Nullable String revision, String projectUrl) {
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
                    indexDiscussion(discussion, marker, currentUserId, revision, projectUrl, expected, copies);
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
            String projectUrl,
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
            Placement placement = item != null
                            && note.get("author") instanceof Map<?, ?> author
                            && currentUserId.equals(author.get("id"))
                    ? copyPlacement(note, body, item, revision, projectUrl)
                    : null;
            String url = note.get("url") instanceof String noteUrl ? noteUrl : null;
            Copy previous = copies.get(key);
            if (placement != null && (previous == null || !previous.verified())) {
                copies.put(key, new Copy(true, noteId, discussionId, url, placement));
            } else if (placement == null && previous == null) {
                copies.put(key, new Copy(false, noteId, discussionId, url, null));
            }
        }
    }

    /**
     * Where the note appears when it is a copy of the item, else {@code null}. A copy is the exact body this channel
     * posts for the item: the ordinary comment linking to its lines at the reviewed commit; or, posted before that,
     * a diff note at its line on the reviewed commit (this adapter placed a range at its end line) or the ordinary
     * comment that diff note fell back to.
     */
    private static @Nullable Placement copyPlacement(
            Map<?, ?> note, String body, InlineFeedback item, @Nullable String revision, String projectUrl) {
        if (!(item.anchor() instanceof FeedbackAnchor.DiffAnchor diff)) {
            return null;
        }
        // Only a position the response answered with null proves an ordinary comment; a missing one proves nothing.
        boolean unpositioned = note.containsKey("position") && note.get("position") == null;
        if (unpositioned
                && ((revision != null
                                && diff.side() == FeedbackAnchor.DiffSide.RIGHT
                                && body.equals(locationBody(projectUrl, revision, diff, item)))
                        || body.equals(fallbackBody(diff, item)))) {
            return Placement.LOCATION_COMMENT;
        }
        boolean lineCopy = revision != null
                && body.equals(postedBody(item))
                && note.get("position") instanceof Map<?, ?> position
                && diff.filePath().equals(position.get("newPath"))
                && position.get("newLine") instanceof Number line
                && line.intValue() == diff.newLineNumber()
                && position.get("diffRefs") instanceof Map<?, ?> diffRefs
                && revision.equals(diffRefs.get("headSha"));
        return lineCopy ? Placement.LINE : null;
    }

    /**
     * The items a stopped package does not request: a verified copy the scan found is still reported as kept, so
     * stopping never loses a copy that is already there; every other item is reported as never sent.
     */
    private static List<DeliveredSignal> unrequested(List<InlineFeedback> items, Map<String, Copy> copies) {
        return items.stream()
                .map(item -> {
                    Copy copy = item.deliveryKey() == null ? null : copies.get(item.deliveryKey());
                    return copy != null && copy.verified()
                            ? copy.preserved(item)
                            : DeliveredSignal.notSent(item.deliveryKey(), item.anchor());
                })
                .toList();
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

    /** The key of the correlation tag this channel appends last, so text earlier in the comment cannot name one. */
    @Nullable
    private static String parseDeliveryKey(String body) {
        Matcher m = CK_TAG.matcher(body);
        String key = null;
        while (m.find()) key = m.group(1);
        return key;
    }

    /**
     * The project's web address on the workspace's configured GitLab server: each namespace and project segment
     * encoded on its own, so the slashes between them stay the path's structure.
     */
    private String projectUrl(long scopeId, MrCoordinates mr) {
        String serverUrl = tokenService.resolveServerUrl(scopeId);
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return base + "/" + encodedPath(mr.projectPath());
    }

    /** The item's body: slash commands escaped, then the package marker and the correlation tag. */
    private static String postedBody(InlineFeedback item) {
        String body = GitLabSummaryChannel.escapeSlashCommands(item.body());
        if (!item.marker().isBlank()) {
            body = body + "\n" + item.marker();
        }
        String key = item.deliveryKey();
        return key == null || key.isBlank() ? body : body + "\n<!-- hephaestus-diff-note-ck=" + key + " -->";
    }

    /**
     * The ordinary comment: a static link to the anchored lines of the file at the reviewed commit, then the
     * item's body unchanged. The link is location metadata, never part of the feedback itself; its text names only
     * the lines, so no repository file name reaches the comment's Markdown outside the encoded address.
     */
    private static String locationBody(
            String projectUrl, String revision, FeedbackAnchor.DiffAnchor diff, InlineFeedback item) {
        Integer start = diff.startLine();
        boolean range = start != null && start < diff.newLineNumber();
        String lines = range ? start + "-" + diff.newLineNumber() : String.valueOf(diff.newLineNumber());
        String label = range
                ? "View code at lines " + start + "–" + diff.newLineNumber()
                : "View code at line " + diff.newLineNumber();
        String link = projectUrl + "/-/blob/" + revision + "/" + encodedPath(diff.filePath()) + "#L" + lines;
        return "**[" + label + "](" + link + ")**\n\n" + postedBody(item);
    }

    /** Each segment of a path percent-encoded, so no character of it can end the link. */
    private static String encodedPath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment ->
                        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    /** The comment a diff note outside the diff fell back to, before every item was posted as one. */
    private static String fallbackBody(FeedbackAnchor.DiffAnchor diff, InlineFeedback item) {
        return "**`" + diff.filePath() + ":" + diff.newLineNumber() + "`**\n\n" + postedBody(item);
    }

    /** A copy of a package item; {@code verified} false when it carries the key but not the item. */
    private record Copy(
            boolean verified,
            String noteId,
            @Nullable String discussionId,
            @Nullable String url,
            @Nullable Placement placement) {
        DeliveredSignal preserved(InlineFeedback item) {
            return new DeliveredSignal(
                    item.deliveryKey(),
                    item.anchor(),
                    Disposition.PRESERVED_EXISTING,
                    noteId,
                    discussionId,
                    url,
                    null,
                    placement);
        }
    }

    /** One create request's outcome, and whether the rest of the package must wait. */
    private record Attempt(DeliveredSignal signal, boolean stop) {}

    private static boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("rate limit") || message.contains("429"));
    }
}
