package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;
import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.GRAPHQL_TIMEOUT;

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
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

// Fence each physical copy before creating it, and preserve its marker during correction.
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabLineNoteWriter implements LineNoteWriter {

    private static final Logger log = LoggerFactory.getLogger(GitLabLineNoteWriter.class);

    private static final int DISCUSSIONS_PAGE_SIZE = 100;

    private static final int MAX_DISCUSSION_PAGES = 50;

    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

    // Names the placement of a range copy, so readback never mistakes it for an end-line copy written before ranges.
    static final String RANGE_PLACEMENT = "<!-- hephaestus-gitlab-placement=line-range -->";

    private static final String DISCUSSION_GID = "gid://gitlab/Discussion/";

    private static final Pattern SEGMENT = Pattern.compile("[0-9A-Za-z]{1,128}");

    // The note models a feedback copy can have been recorded as, each with its REST id.
    private static final Pattern NOTE_REF =
            Pattern.compile("gid://gitlab/(Note|DiscussionNote|DiffNote)/([1-9][0-9]{0,17})");

    // A generic 404 proves absence only when the merge request is still readable.
    private static final Pattern NOTE_ABSENT = Pattern.compile("\\{\\s*\"message\"\\s*:\\s*\"404 Not found\"\\s*}");

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final WebClient rest;
    private final GitLabTokenService tokenService;
    private final GitLabDiffVersionReader diffVersions;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;

    public GitLabLineNoteWriter(
            GitLabGraphQlClientProvider gitLabProvider,
            WebClient.Builder rest,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets,
            GitLabDiffVersionReader diffVersions) {
        this.gitLabProvider = gitLabProvider;
        this.rest = rest.clone().build();
        this.tokenService = tokenService;
        this.egressGuard = egressGuard;
        this.targets = targets;
        this.diffVersions = diffVersions;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public CopyWriteResult postImmutablePackage(
            FeedbackTarget target, List<LineNote> feedbackItems, CopyWriteFence fence) {
        if (feedbackItems.isEmpty()) {
            return CopyWriteResult.of(List.of());
        }
        long scopeId = target.integration().workspaceId();
        String revision;
        try {
            revision = ScmVersion.reviewedHead(target.version());
        } catch (IllegalArgumentException unavailable) {
            return CopyWriteResult.of(notSent(feedbackItems));
        }
        boolean rateLimited = gitLabProvider.isRateLimitCritical(scopeId);
        if (rateLimited || revision == null || !COMMIT_SHA.matcher(revision).matches()) {
            // Without the reviewed commit no comment can link to the code the review read.
            log.warn(
                    "GitLab location comments not requested: workspaceId={}, rateLimited={}, reviewedRevision={}",
                    scopeId,
                    rateLimited,
                    sanitizeForLog(revision));
            return CopyWriteResult.of(notSent(feedbackItems));
        }
        MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, IntegrationKind.GITLAB));
        String projectUrl = projectUrl(scopeId, mr);
        Scan scan = indexCopies(scopeId, mr, feedbackItems, target.version(), projectUrl);
        Map<String, Copy> copies = scan.copies();

        // An unread diff version stops the package before any create instead of posting plain comments.
        boolean positioned = feedbackItems.stream()
                .anyMatch(item -> !item.body().isBlank()
                        && item.anchor().endLine() != null
                        && item.anchor().side() != null
                        && !copies.containsKey(item.writeKey()));
        var pinnedDiff = positioned ? diffVersions.read(scopeId, mr, target.version()) : null;
        List<CopyReceipt> completed = new ArrayList<>(feedbackItems.size());
        Set<String> processedKeys = new HashSet<>();
        for (int index = 0; index < feedbackItems.size(); index++) {
            LineNote item = feedbackItems.get(index);
            String key = item.writeKey();
            if (key != null && !processedKeys.add(key)) {
                continue;
            }
            FeedbackAnchor diff = item.anchor();
            if (item.body().isBlank() || (diff.endLine() != null && diff.side() == null)) {
                completed.add(CopyReceipt.notSent(item.copyId()));
                continue;
            }
            Copy copy = copies.get(key);
            if (copy != null) {
                // A copy that does not verify proves neither delivery nor absence: nothing is
                // created next to it.
                completed.add(copy.verified() ? copy.preserved(item) : CopyReceipt.notSent(item.copyId()));
                continue;
            }
            String anchorVersion =
                    diff.side() == CitationSide.OLD ? target.version().baseVersion() : revision;
            if (anchorVersion == null || !COMMIT_SHA.matcher(anchorVersion).matches()) {
                completed.add(CopyReceipt.notSent(item.copyId()));
                continue;
            }
            List<LineNote> rest = feedbackItems.subList(index, feedbackItems.size());
            try {
                egressGuard.requireDeliveryAllowed("gitlab.post-inline-feedback");
            } catch (OutboundEgressSuppressedException e) {
                completed.addAll(unrequested(rest, copies));
                return CopyWriteResult.withheld(completed, deliveryKeys(rest));
            }
            var position = pinnedDiff == null ? null : GitLabDiffPositions.build(pinnedDiff, diff);
            Request request;
            try {
                request = request(scopeId, mr);
            } catch (RuntimeException unavailable) {
                // Resolved before the fence, so no provider request was made for this copy or the rest.
                log.warn("GitLab feedback request could not be prepared: workspaceId={}", scopeId, unavailable);
                completed.addAll(unrequested(rest, copies));
                return CopyWriteResult.of(completed);
            }
            if (!fence.beforeCreate(List.of(item.copyId()), List.copyOf(completed))) {
                completed.addAll(unrequested(rest, copies));
                return CopyWriteResult.of(completed);
            }
            Attempt attempt = position == null
                    ? null
                    : createDiffThread(scopeId, request, mr, projectUrl, scan.author(), item, position);
            if (attempt == null) {
                // The committed copy fence covers the fallback after a confirmed position rejection.
                if (position != null) {
                    try {
                        egressGuard.requireDeliveryAllowed("gitlab.post-inline-feedback");
                    } catch (OutboundEgressSuppressedException e) {
                        completed.addAll(unrequested(rest, copies));
                        return CopyWriteResult.withheld(completed, deliveryKeys(rest));
                    }
                }
                attempt = createLocationThread(
                        scopeId,
                        request,
                        mr,
                        projectUrl,
                        scan.author(),
                        locationBody(projectUrl, anchorVersion, diff, item),
                        diff,
                        item.copyId());
            }
            completed.add(attempt.receipt());
            if (attempt.stop()) {
                completed.addAll(unrequested(feedbackItems.subList(index + 1, feedbackItems.size()), copies));
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
        if (gitLabProvider.isRateLimitCritical(scopeId)
                || feedbackItems.stream().anyMatch(item -> item.writeKey() == null)) {
            return null;
        }
        try {
            MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, IntegrationKind.GITLAB));
            Map<String, Copy> copies = indexCopies(
                            scopeId, mr, feedbackItems, target.version(), projectUrl(scopeId, mr))
                    .copies();
            List<CopyReceipt> found = new ArrayList<>();
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

    // Only an explicit position rejection proves that a fallback cannot duplicate a created note.
    private @Nullable Attempt createDiffThread(
            long scopeId,
            Request request,
            MrCoordinates mr,
            String projectUrl,
            long author,
            LineNote item,
            GitLabDiffPositions.Position position) {
        String body = lineBody(item);
        Answer answer;
        try {
            answer = exchange(scopeId, HttpMethod.POST, request, Map.of("body", body, "position", position.request()));
        } catch (RuntimeException failure) {
            log.warn(
                    "GitLab diff thread outcome unknown: workspaceId={}, file={}, line={}",
                    scopeId,
                    sanitizeForLog(item.anchor().path()),
                    item.anchor().endLine(),
                    failure);
            return new Attempt(CopyReceipt.attempted(item.copyId()), isRateLimitError(failure));
        }
        JsonNode note = answer.created() ? createdNote(answer.json(), "DiffNote", body, author) : null;
        if (note != null && placed(note.path("position"), position))
            return posted(item.copyId(), note, "DiffNote", projectUrl, mr, CopyForm.LINE_NOTE);
        if (answer.status() == 400 && positionError(answer.text())) return null;
        log.warn(
                "GitLab diff thread was not confirmed: workspaceId={}, file={}, line={}, status={}",
                scopeId,
                sanitizeForLog(item.anchor().path()),
                item.anchor().endLine(),
                answer.status());
        return new Attempt(CopyReceipt.attempted(item.copyId()), answer.status() == 429);
    }

    private static boolean positionError(String text) {
        String message = text.toLowerCase(Locale.ROOT);
        return message.contains("position")
                || message.contains("line code")
                || message.contains("line_code")
                || message.contains("line_range")
                || message.contains("diff refs")
                || message.contains("diff_refs");
    }

    // A failed request can still create a thread; only a confirmed answer proves the copy and its form.
    private Attempt createLocationThread(
            long scopeId,
            Request request,
            MrCoordinates mr,
            String projectUrl,
            long author,
            String body,
            FeedbackAnchor diff,
            UUID copyId) {
        Answer answer;
        try {
            answer = exchange(scopeId, HttpMethod.POST, request, Map.of("body", body));
        } catch (RuntimeException failure) {
            log.warn(
                    "GitLab location thread outcome unknown: workspaceId={}, file={}, line={}",
                    scopeId,
                    sanitizeForLog(diff.path()),
                    diff.endLine(),
                    failure);
            return new Attempt(CopyReceipt.attempted(copyId), isRateLimitError(failure));
        }
        JsonNode note = answer.created() ? createdNote(answer.json(), "DiscussionNote", body, author) : null;
        if (note != null
                && (note.path("position").isMissingNode()
                        || note.path("position").isNull()))
            return posted(copyId, note, "DiscussionNote", projectUrl, mr, CopyForm.FILE_NOTE);
        log.warn(
                "GitLab location thread was not confirmed: workspaceId={}, file={}, line={}, status={}",
                scopeId,
                sanitizeForLog(diff.path()),
                diff.endLine(),
                answer.status());
        return new Attempt(CopyReceipt.attempted(copyId), answer.status() == 429);
    }

    // The first note of the created thread must be this copy, written by this token, of the expected kind.
    private static @Nullable JsonNode createdNote(
            @Nullable JsonNode discussion, String type, String body, long author) {
        if (discussion == null
                || !discussion.path("id").isString()
                || !SEGMENT.matcher(discussion.path("id").asString()).matches()
                || !discussion.path("notes").isArray()
                || discussion.path("notes").isEmpty()) return null;
        JsonNode note = discussion.path("notes").get(0);
        boolean own = note.path("id").isIntegralNumber()
                && note.path("id").longValue() > 0
                && note.path("type").isString()
                && type.equals(note.path("type").asString())
                && note.path("system").isBoolean()
                && !note.path("system").booleanValue()
                && note.path("body").isString()
                && body.equals(note.path("body").asString())
                && note.path("author").path("id").isIntegralNumber()
                && note.path("author").path("id").longValue() == author;
        return own ? note : null;
    }

    // GitLab names a note by its model and REST id; the receipt carries the same global id the scan reads.
    private static Attempt posted(
            UUID copyId, JsonNode note, String type, String projectUrl, MrCoordinates mr, CopyForm form) {
        long id = note.path("id").longValue();
        return new Attempt(
                new CopyReceipt(
                        copyId,
                        CopyDisposition.POSTED,
                        "gid://gitlab/" + type + "/" + id,
                        projectUrl + "/-/merge_requests/" + mr.iid() + "#note_" + id,
                        true,
                        form),
                false);
    }

    // The created thread must hold the requested place, including both ends of a range.
    private static boolean placed(JsonNode actual, GitLabDiffPositions.Position expected) {
        if (!actual.isObject()
                || !"text".equals(text(actual, "position_type"))
                || !expected.base().equals(text(actual, "base_sha"))
                || !expected.start().equals(text(actual, "start_sha"))
                || !expected.head().equals(text(actual, "head_sha"))
                || !expected.oldPath().equals(text(actual, "old_path"))
                || !expected.newPath().equals(text(actual, "new_path"))
                || !Objects.equals(expected.oldLine(), line(actual, "old_line"))
                || !Objects.equals(expected.newLine(), line(actual, "new_line"))) return false;
        JsonNode range = actual.path("line_range");
        var requested = expected.range();
        if (requested == null)
            return range.isMissingNode()
                    || range.isNull()
                    || (endpoint(range.path("start"), expected.oldLine(), expected.newLine())
                            && endpoint(range.path("end"), expected.oldLine(), expected.newLine()));
        return endpoint(range.path("start"), requested.start()) && endpoint(range.path("end"), requested.end());
    }

    private static boolean endpoint(JsonNode actual, GitLabDiffPositions.Point expected) {
        return expected.type().equals(text(actual, "type")) && endpoint(actual, expected.oldLine(), expected.newLine());
    }

    private static boolean endpoint(JsonNode actual, @Nullable Integer oldLine, @Nullable Integer newLine) {
        return actual.isObject()
                && Objects.equals(oldLine, line(actual, "old_line"))
                && Objects.equals(newLine, line(actual, "new_line"));
    }

    private static @Nullable String text(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }

    private static @Nullable Integer line(JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).intValue() : null;
    }

    private record Answer(int status, @Nullable JsonNode json, String text) {
        boolean created() {
            return status >= 200 && status < 300 && json != null;
        }
    }

    // A status is an answer; only transport failures and timeouts leave the outcome unknown to the caller.
    private Answer exchange(long scopeId, HttpMethod method, Request target, @Nullable Map<String, Object> body) {
        var request = rest.method(method)
                .uri(target.uri())
                .header(HttpHeaders.AUTHORIZATION, target.authorization())
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, scopeId);
        WebClient.RequestHeadersSpec<?> spec = body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        Answer answer = spec.exchangeToMono(response -> {
                    gitLabProvider.updateRateLimit(scopeId, response.headers().asHttpHeaders());
                    int status = response.statusCode().value();
                    return response.statusCode().is2xxSuccessful()
                            ? response.bodyToMono(JsonNode.class)
                                    .map(json -> new Answer(status, json, ""))
                                    .defaultIfEmpty(new Answer(status, null, ""))
                            : response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(text -> new Answer(status, null, text));
                })
                .block(GRAPHQL_TIMEOUT);
        if (answer == null) throw new IllegalStateException("GitLab answered without a response");
        return answer;
    }

    private record Request(URI uri, String authorization) {}

    // The address and credential are local facts; resolving them is never part of a provider request.
    private Request request(long scopeId, MrCoordinates mr) {
        return request(scopeId, mr, null);
    }

    private Request request(long scopeId, MrCoordinates mr, @Nullable String discussion) {
        return authorized(scopeId, discussions(scopeId, mr, discussion));
    }

    private Request authorized(long scopeId, URI uri) {
        return new Request(uri, "Bearer " + tokenService.getAccessToken(scopeId));
    }

    private URI discussions(long scopeId, MrCoordinates mr, @Nullable String discussion) {
        return URI.create(GitLabDiffVersionReader.mergeRequestApi(tokenService.resolveServerUrl(scopeId), mr)
                + "/discussions"
                + (discussion == null ? "" : "/" + discussion));
    }

    // Partial discussions, missing identity, or incomplete pagination cannot prove a copy absent.
    private Scan indexCopies(
            long scopeId, MrCoordinates mr, List<LineNote> items, ItemVersion version, String projectUrl) {
        Map<String, LineNote> expected = new HashMap<>();
        for (LineNote item : items) {
            expected.putIfAbsent(item.writeKey(), item);
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
                    indexDiscussion(discussion, currentUserId, version, projectUrl, expected, copies);
                }
                Object hasNextPage = pageInfo.get("hasNextPage");
                if (Boolean.FALSE.equals(hasNextPage)) {
                    for (var entry : copies.entrySet())
                        if (entry.getValue().rangePending())
                            entry.setValue(confirmRange(
                                    scopeId,
                                    mr,
                                    Objects.requireNonNull(expected.get(entry.getKey())),
                                    entry.getValue()));
                    return new Scan(copies, GitLabSyncConstants.extractNumericId(currentUserId));
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
            String currentUserId,
            ItemVersion version,
            String projectUrl,
            Map<String, LineNote> expected,
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
            if (Boolean.TRUE.equals(note.get("system"))) continue;
            String key = ScmFeedbackFormatter.lastDeliveryKey(body);
            if (key == null) {
                continue;
            }
            LineNote item = expected.get(key);
            CopyForm copyForm = null;
            boolean rangePending = false;
            if (item != null
                    && note.get("author") instanceof Map<?, ?> author
                    && currentUserId.equals(author.get("id"))) {
                copyForm = copyForm(note, body, item, version, projectUrl);
                rangePending = copyForm == CopyForm.LINE_NOTE && placesRange(body, item);
            }
            String url = note.get("url") instanceof String noteUrl ? noteUrl : null;
            Copy previous = copies.get(key);
            if (copyForm != null && (previous == null || !previous.verified())) {
                copies.put(key, new Copy(true, noteId, discussionId, url, copyForm, rangePending));
            } else if (copyForm == null && previous == null) {
                copies.put(key, new Copy(false, noteId, discussionId, url, null, false));
            }
        }
    }

    private static @Nullable CopyForm copyForm(
            Map<?, ?> note, String body, LineNote item, ItemVersion version, String projectUrl) {
        FeedbackAnchor diff = item.anchor();
        String revision = diff.side() == CitationSide.OLD ? version.baseVersion() : ScmVersion.reviewedHead(version);
        if (diff.endLine() != null && diff.side() == null) return null;
        // Only a position the response answered with null proves an ordinary comment; a missing one
        // proves nothing.
        boolean unpositioned = note.containsKey("position") && note.get("position") == null;
        if (unpositioned
                && ((revision != null && body.equals(locationBody(projectUrl, revision, diff, item)))
                        || (diff.endLine() != null && body.equals(fallbackBody(diff, item))))) {
            return CopyForm.FILE_NOTE;
        }
        boolean lineCopy = diff.endLine() != null
                && revision != null
                && (body.equals(postedBody(item)) || placesRange(body, item))
                && note.get("position") instanceof Map<?, ?> position
                && diff.path().equals(position.get(diff.side() == CitationSide.OLD ? "oldPath" : "newPath"))
                && position.get(diff.side() == CitationSide.OLD ? "oldLine" : "newLine") instanceof Number line
                && line.intValue() == diff.endLine()
                && position.get("diffRefs") instanceof Map<?, ?> diffRefs
                && Objects.equals(version.baseVersion(), diffRefs.get("baseSha"))
                && Objects.equals(ScmVersion.reviewedHead(version), diffRefs.get("headSha"))
                && diffRefs.get("startSha") instanceof String startSha
                && COMMIT_SHA.matcher(startSha).matches();
        return lineCopy ? CopyForm.LINE_NOTE : null;
    }

    // Stopping a package must preserve receipts for copies already verified at the provider.
    private static List<CopyReceipt> unrequested(List<LineNote> items, Map<String, Copy> copies) {
        return items.stream()
                .map(item -> {
                    Copy copy = item.writeKey() == null ? null : copies.get(item.writeKey());
                    return copy != null && copy.verified() ? copy.preserved(item) : CopyReceipt.notSent(item.copyId());
                })
                .toList();
    }

    private static List<CopyReceipt> notSent(List<LineNote> items) {
        return items.stream().map(item -> CopyReceipt.notSent(item.copyId())).toList();
    }

    private static List<UUID> deliveryKeys(List<LineNote> feedbackItems) {
        return feedbackItems.stream().map(LineNote::copyId).toList();
    }

    // Encode each project segment independently so namespace slashes retain their structure.
    private String projectUrl(long scopeId, MrCoordinates mr) {
        String serverUrl = tokenService.resolveServerUrl(scopeId);
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return base + "/" + encodedPath(mr.projectPath());
    }

    // A range copy names its placement; a body without it is an end-line copy, as written before ranges.
    private static String lineBody(LineNote item) {
        if (!ranged(item.anchor())) return postedBody(item);
        return GitLabSummaryWriter.gitLabBody(item.body())
                + "\n"
                + item.writeMarker()
                + "\n"
                + RANGE_PLACEMENT
                + "\n"
                + ScmFeedbackFormatter.deliveryTag(item.writeKey());
    }

    private static boolean placesRange(String body, LineNote item) {
        return ranged(item.anchor()) && body.equals(lineBody(item));
    }

    private static boolean ranged(FeedbackAnchor anchor) {
        Integer start = anchor.startLine();
        Integer end = anchor.endLine();
        return start != null && end != null && start < end;
    }

    // Only GitLab's record of both ends verifies a placed range; a range it does not hold proves nothing delivered.
    private Copy confirmRange(long scopeId, MrCoordinates mr, LineNote item, Copy copy) {
        String discussion = copy.discussionId();
        String segment = discussion != null && discussion.startsWith(DISCUSSION_GID)
                ? discussion.substring(DISCUSSION_GID.length())
                : "";
        if (!SEGMENT.matcher(segment).matches())
            throw new FeedbackDeliveryException("A GitLab range thread has no readable identity");
        long noteId = GitLabSyncConstants.extractNumericId(copy.noteId());
        Answer answer = exchange(scopeId, HttpMethod.GET, request(scopeId, mr, segment), null);
        JsonNode thread = answer.json();
        if (answer.status() != 200 || thread == null || !thread.path("notes").isArray())
            throw new FeedbackDeliveryException("A GitLab range thread was not read");
        JsonNode note = null;
        for (JsonNode candidate : thread.path("notes"))
            if (candidate.path("id").isIntegralNumber() && candidate.path("id").longValue() == noteId) note = candidate;
        if (note == null) throw new FeedbackDeliveryException("A GitLab range thread was not read completely");
        FeedbackAnchor anchor = item.anchor();
        String side = anchor.side() == CitationSide.OLD ? "old_line" : "new_line";
        JsonNode range = note.path("position").path("line_range");
        return copy.confirmed(Objects.equals(anchor.startLine(), line(range.path("start"), side))
                && Objects.equals(anchor.endLine(), line(range.path("end"), side)));
    }

    private static String postedBody(LineNote item) {
        String body = GitLabSummaryWriter.gitLabBody(item.body());
        body = body + "\n" + item.writeMarker();
        String key = item.writeKey();
        return body + "\n" + ScmFeedbackFormatter.deliveryTag(key);
    }

    // File names stay inside the encoded URL; link text contains only line coordinates.
    private static String locationBody(String projectUrl, String revision, FeedbackAnchor diff, LineNote item) {
        if (diff.endLine() == null)
            return "**[View file]("
                    + projectUrl
                    + "/-/blob/"
                    + revision
                    + "/"
                    + encodedPath(diff.path())
                    + ")**\n\n"
                    + postedBody(item);
        Integer start = diff.startLine();
        boolean range = start != null && start < diff.endLine();
        String lines = range ? start + "-" + diff.endLine() : String.valueOf(diff.endLine());
        String label =
                range ? "View code at lines " + start + "–" + diff.endLine() : "View code at line " + diff.endLine();
        String link = projectUrl + "/-/blob/" + revision + "/" + encodedPath(diff.path()) + "#L" + lines;
        return "**[" + label + "](" + link + ")**\n\n" + postedBody(item);
    }

    private static String encodedPath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment ->
                        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static String fallbackBody(FeedbackAnchor diff, LineNote item) {
        return "**`" + diff.path() + ":" + diff.endLine() + "`**\n\n" + postedBody(item);
    }

    private record Copy(
            boolean verified,
            String noteId,
            @Nullable String discussionId,
            @Nullable String url,
            @Nullable CopyForm copyForm,
            boolean rangePending) {
        CopyReceipt preserved(LineNote item) {
            return new CopyReceipt(item.copyId(), CopyDisposition.PRESERVED_EXISTING, noteId, url, null, copyForm);
        }

        Copy confirmed(boolean range) {
            return new Copy(range, noteId, discussionId, url, range ? copyForm : null, false);
        }
    }

    private record Scan(Map<String, Copy> copies, long author) {}

    private record Attempt(CopyReceipt receipt, boolean stop) {}

    private static boolean isRateLimitError(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("rate limit") || message.contains("429"));
    }

    // GitLab has no node(id:) root field; its merge request notes API reads and edits every note type by REST id.
    @Override
    public List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> copies) {
        String subject = targets.subject(target, kind());
        long workspaceId = target.item().workspaceId();
        List<CopyUpdateReceipt> outcomes = new ArrayList<>();
        Long author = null;
        for (CopyReceipt copy : copies) {
            String externalRef = copy.externalRef();
            if (externalRef == null || externalRef.isBlank()) {
                outcomes.add(
                        CopyUpdateReceipt.transientFailure(copy.copyId(), "Copy has no recorded provider reference"));
                continue;
            }
            var ref = NOTE_REF.matcher(externalRef);
            if (!ref.matches()) {
                outcomes.add(unsupported(copy, "Existing copy has no GitLab note identity"));
                continue;
            }
            if (gitLabProvider.isRateLimitCritical(workspaceId)) {
                outcomes.add(CopyUpdateReceipt.transientFailure(copy.copyId(), "The GitLab rate limit is critical"));
                continue;
            }
            String type = ref.group(1);
            long noteId = Long.parseLong(ref.group(2));
            Request note;
            Request user;
            Request parent;
            try {
                MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(subject);
                String server = tokenService.resolveServerUrl(workspaceId);
                note = authorized(
                        workspaceId,
                        URI.create(GitLabDiffVersionReader.mergeRequestApi(server, mr) + "/notes/" + noteId));
                user = authorized(workspaceId, URI.create(server.replaceAll("/+$", "") + "/api/v4/user"));
                parent = authorized(workspaceId, URI.create(GitLabDiffVersionReader.mergeRequestApi(server, mr)));
            } catch (RuntimeException unavailable) {
                outcomes.add(CopyUpdateReceipt.transientFailure(
                        copy.copyId(), "The correction request could not be prepared"));
                continue;
            }
            try {
                if (author == null) author = currentUser(workspaceId, user);
                Answer read = exchange(workspaceId, HttpMethod.GET, note, null);
                if (read.status() == 404 && NOTE_ABSENT.matcher(read.text()).matches()) {
                    Answer mergeRequest = exchange(workspaceId, HttpMethod.GET, parent, null);
                    JsonNode readable = mergeRequest.status() == 200 ? mergeRequest.json() : null;
                    var mr = GitLabMrResolver.parseSubjectExternalId(subject);
                    boolean confirmed = readable != null
                            && readable.path("iid").isIntegralNumber()
                            && readable.path("iid").longValue() == mr.iid();
                    outcomes.add(
                            confirmed
                                    ? CopyUpdateReceipt.gone(copy.copyId(), "The provider copy no longer exists")
                                    : CopyUpdateReceipt.transientFailure(
                                            copy.copyId(), "The merge request could not be read"));
                    continue;
                }
                JsonNode found = read.status() == 200 ? read.json() : null;
                if (found == null) {
                    outcomes.add(CopyUpdateReceipt.transientFailure(copy.copyId(), "Existing copy could not be read"));
                    continue;
                }
                String original = text(found, "body");
                if (!ownNote(found, noteId, type, author)
                        || original == null
                        || ScmFeedbackFormatter.lastDeliveryKey(original) == null) {
                    outcomes.add(unsupported(copy, "Existing copy authority could not be verified"));
                    continue;
                }
                String withdrawn = ScmFeedbackFormatter.withdrawnCopyBody(original);
                if (original.equals(withdrawn)) {
                    outcomes.add(edited(copy));
                    continue;
                }
                egressGuard.requireDeliveryAllowed("gitlab.correct-feedback-copy");
                Answer written;
                try {
                    written = exchange(workspaceId, HttpMethod.PUT, note, Map.of("body", withdrawn));
                } catch (RuntimeException unknown) {
                    log.warn(
                            "GitLab copy correction outcome unknown: workspaceId={}, note={}",
                            workspaceId,
                            noteId,
                            unknown);
                    outcomes.add(CopyUpdateReceipt.transientFailure(
                            copy.copyId(), "Provider correction outcome is unknown"));
                    continue;
                }
                JsonNode stored = written.status() == 200 ? written.json() : null;
                boolean confirmed = stored != null
                        && ownNote(stored, noteId, type, author)
                        && withdrawn.equals(text(stored, "body"));
                outcomes.add(
                        confirmed
                                ? edited(copy)
                                : CopyUpdateReceipt.transientFailure(
                                        copy.copyId(), "Provider did not confirm the correction"));
            } catch (OutboundEgressSuppressedException e) {
                throw e;
            } catch (RuntimeException e) {
                outcomes.add(CopyUpdateReceipt.transientFailure(copy.copyId(), "Existing copy could not be read"));
            }
        }
        return List.copyOf(outcomes);
    }

    // The token's own account is the only author whose copy a correction may edit.
    private long currentUser(long scopeId, Request user) {
        Answer answer = exchange(scopeId, HttpMethod.GET, user, null);
        JsonNode json = answer.json();
        if (answer.status() != 200
                || json == null
                || !json.path("id").isIntegralNumber()
                || json.path("id").longValue() <= 0)
            throw new FeedbackDeliveryException("The GitLab account could not be read");
        return json.path("id").longValue();
    }

    // A reply can turn a plain Note into a DiscussionNote without changing its identity or author.
    private static boolean ownNote(JsonNode note, long id, String type, long author) {
        return note.path("id").isIntegralNumber()
                && note.path("id").longValue() == id
                && ("Note".equals(type)
                        ? note.path("type").isNull() || "DiscussionNote".equals(text(note, "type"))
                        : type.equals(text(note, "type")))
                && note.path("system").isBoolean()
                && !note.path("system").booleanValue()
                && note.path("author").path("id").isIntegralNumber()
                && note.path("author").path("id").longValue() == author;
    }

    // The receipt keeps the identity and address the copy was delivered under.
    private static CopyUpdateReceipt edited(CopyReceipt copy) {
        return new CopyUpdateReceipt(
                copy.copyId(), CopyUpdateKind.EDITED, copy.externalRef(), copy.externalUrl(), null);
    }

    private static CopyUpdateReceipt unsupported(CopyReceipt copy, String reason) {
        return new CopyUpdateReceipt(
                copy.copyId(), CopyUpdateKind.UNSUPPORTED, copy.externalRef(), copy.externalUrl(), reason);
    }
}
