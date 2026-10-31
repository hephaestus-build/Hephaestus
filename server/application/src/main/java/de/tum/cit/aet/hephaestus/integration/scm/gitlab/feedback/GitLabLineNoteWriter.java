package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

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
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter.Footer;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import tools.jackson.databind.json.JsonMapper;

// Stages a package as private draft notes, then one bulk publication with the summary is the only notifying write.
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabLineNoteWriter implements LineNoteWriter {

    private static final Logger log = LoggerFactory.getLogger(GitLabLineNoteWriter.class);

    private static final JsonMapper MAPPER = new JsonMapper();

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
    private final ScmFeedbackFormatter formatter;

    public GitLabLineNoteWriter(
            GitLabGraphQlClientProvider gitLabProvider,
            WebClient.Builder rest,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets,
            GitLabDiffVersionReader diffVersions,
            ScmFeedbackFormatter formatter) {
        this.gitLabProvider = gitLabProvider;
        this.rest = rest.clone().build();
        this.tokenService = tokenService;
        this.egressGuard = egressGuard;
        this.targets = targets;
        this.diffVersions = diffVersions;
        this.formatter = formatter;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public CopyWriteResult postPackage(FeedbackTarget target, OnWorkPackage publication, CopyWriteFence fence) {
        if (publication.copyIds().isEmpty()) return CopyWriteResult.of(List.of());
        long workspace = target.item().workspaceId();
        if (gitLabProvider.isRateLimitCritical(workspace)) return publication.notSent();
        boolean fenced = false;
        try {
            var footer = formatter.footer(target.item());
            var mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, kind()));
            String revision = ScmVersion.reviewedHead(target.version());
            if (revision == null || !COMMIT_SHA.matcher(revision).matches()) return publication.notSent();
            String project = projectUrl(workspace, mr);
            var scan = indexCopies(workspace, mr, publication.notes(), target, project, footer);
            var found = packageReceipts(workspace, mr, publication, scan, footer);
            if (!found.isEmpty()) {
                // Even a partial submitted package is never followed by a second notification.
                // A receipt is recorded only on a fenced copy.
                if (!fence.beforeCreate(publication.copyIds(), List.of())) return publication.notSent();
                return CopyWriteResult.of(found);
            }
            var version =
                    publication.notes().stream().anyMatch(note -> note.anchor().endLine() != null)
                            ? diffVersions.read(workspace, mr, target.version())
                            : null;
            var expected = new LinkedHashMap<String, Map<String, Object>>();
            for (var note : publication.notes()) {
                var position = version == null ? null : GitLabDiffPositions.build(version, note.anchor());
                if (note.anchor().endLine() != null && position == null) return publication.notSent();
                String anchorVersion = note.anchor().side() == CitationSide.OLD
                        ? target.version().baseVersion()
                        : revision;
                if (anchorVersion == null) return publication.notSent();
                var fields = new LinkedHashMap<String, Object>();
                fields.put(
                        "note",
                        position == null
                                ? locationBody(footer, project, anchorVersion, note.anchor(), note)
                                : lineBody(footer, note));
                if (position != null) fields.put("position", position.request());
                expected.put(note.writeKey(), fields);
            }
            String api = GitLabDiffVersionReader.mergeRequestApi(tokenService.resolveServerUrl(workspace), mr);
            var pending = ownDrafts(workspace, api, scan.author(), expected);
            egressGuard.requireDeliveryAllowed("gitlab.publish-feedback-package");
            // A refused fence started nothing: the copies stay as they were.
            if (!fence.beforeCreate(publication.copyIds(), List.of())) return CopyWriteResult.of(List.of());
            fenced = true;
            for (var entry : expected.entrySet()) {
                if (pending.contains(entry.getKey())) continue;
                if (gitLabProvider.isRateLimitCritical(workspace) || !fence.beforeCreate(List.of(), List.of()))
                    return publication.notSent();
                var created = exchange(
                        workspace,
                        HttpMethod.POST,
                        authorized(workspace, URI.create(api + "/draft_notes")),
                        entry.getValue());
                if (created.status() < 200 || created.status() >= 300) return publication.notSent();
            }
            // Bulk publish includes every draft of this token. Refuse drafts outside this exact checked package.
            if (ownDrafts(workspace, api, scan.author(), expected).size() != expected.size()
                    || !fence.beforeCreate(List.of(), List.of())) return publication.notSent();
            egressGuard.requireDeliveryAllowed("gitlab.submit-feedback-package");
            var summary = publication.summary();
            var fields = summary == null
                    ? Map.<String, Object>of()
                    : Map.<String, Object>of("note", footer.summary(summary));
            var submitted = exchange(
                    workspace,
                    HttpMethod.POST,
                    authorized(workspace, URI.create(api + "/draft_notes/bulk_publish")),
                    fields);
            if (submitted.status() < 200 || submitted.status() >= 300) return publication.attempted();
            var posted = findPosted(target, publication);
            return posted == null || posted.size() != publication.copyIds().size()
                    ? publication.attempted()
                    : CopyWriteResult.of(posted);
        } catch (OutboundEgressSuppressedException suppressed) {
            throw suppressed;
        } catch (RuntimeException unknown) {
            return fenced ? publication.attempted() : publication.notSent();
        }
    }

    /** Reads all this token's drafts, adopting only exact bodies and positions from this package. */
    private Set<String> ownDrafts(long workspace, String api, long author, Map<String, Map<String, Object>> expected) {
        var found = new HashSet<String>();
        for (var draft : pages(workspace, api + "/draft_notes")) {
            String body = draft.path("note").asString("");
            String key = ScmFeedbackFormatter.lastDeliveryKey(body);
            var fields = key == null ? null : expected.get(key);
            if (fields == null
                    || !body.equals(fields.get("note"))
                    || !found.add(key)
                    || !draft.path("author_id").isIntegralNumber()
                    || draft.path("author_id").longValue() != author
                    || !draft.path("id").isIntegralNumber()
                    || draft.path("id").longValue() <= 0
                    || !draft.path("discussion_id").isNull()
                    || !sameDraftPosition(draft.path("position"), fields.get("position")))
                throw new FeedbackDeliveryException("Unrelated or changed draft notes prevent bulk publication");
        }
        return found;
    }

    private static boolean sameDraftPosition(JsonNode actual, @Nullable Object expected) {
        if (expected == null)
            return actual.isNull()
                    || actual.isMissingNode()
                    || (actual.isObject()
                            && actual.properties().stream()
                                    .allMatch(entry -> entry.getValue().isNull()));
        var wanted = MAPPER.valueToTree(expected);
        return wanted.properties().stream().allMatch(entry -> entry.getValue().equals(actual.path(entry.getKey())));
    }

    private List<JsonNode> pages(long workspace, String endpoint) {
        var result = new ArrayList<JsonNode>();
        for (int page = 1; page <= MAX_DISCUSSION_PAGES; page++) {
            var response = exchange(
                    workspace,
                    HttpMethod.GET,
                    authorized(workspace, URI.create(endpoint + "?per_page=100&page=" + page)),
                    null);
            var rows = response.json();
            if (response.status() != 200 || rows == null || !rows.isArray())
                throw new FeedbackDeliveryException("GitLab pending notes were not read completely");
            rows.forEach(result::add);
            if (rows.size() < 100) return List.copyOf(result);
        }
        throw new FeedbackDeliveryException("GitLab pending note pagination did not reach its end");
    }

    private List<CopyReceipt> packageReceipts(
            long workspace, MrCoordinates mr, OnWorkPackage publication, Scan scan, Footer footer) {
        var result = new ArrayList<CopyReceipt>();
        for (var note : publication.notes()) {
            var copy = scan.copies().get(note.writeKey());
            if (copy != null && !copy.verified())
                throw new FeedbackDeliveryException("Existing copy conflicts with the package");
            if (copy != null) result.add(copy.preserved(note));
        }
        var summary = publication.summary();
        if (summary != null) {
            String api = GitLabDiffVersionReader.mergeRequestApi(tokenService.resolveServerUrl(workspace), mr);
            for (var note : pages(workspace, api + "/notes")) {
                String body = note.path("body").asString("");
                if (summary.writeMarker() == null || !body.contains(summary.writeMarker())) continue;
                if (!body.equals(footer.summary(summary))
                        || note.path("author").path("id").longValue() != scan.author()
                        || !note.path("id").isIntegralNumber()
                        || note.path("id").longValue() <= 0
                        || note.path("system").asBoolean(true))
                    throw new FeedbackDeliveryException("Existing summary conflicts with the package");
                if (result.stream().anyMatch(receipt -> receipt.copyId().equals(summary.copyId())))
                    throw new FeedbackDeliveryException("Duplicate summary markers prevent acknowledgement");
                String id = Long.toString(note.path("id").longValue());
                result.add(new CopyReceipt(
                        summary.copyId(),
                        CopyDisposition.PRESERVED_EXISTING,
                        "gid://gitlab/Note/" + id,
                        projectUrl(workspace, mr) + "/-/merge_requests/" + mr.iid() + "#note_" + id,
                        true,
                        null));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public @Nullable List<CopyReceipt> findPosted(FeedbackTarget target, OnWorkPackage publication) {
        long workspace = target.item().workspaceId();
        if (gitLabProvider.isRateLimitCritical(workspace)) return null;
        try {
            var footer = formatter.footer(target.item());
            var mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, kind()));
            var scan = indexCopies(workspace, mr, publication.notes(), target, projectUrl(workspace, mr), footer);
            return packageReceipts(workspace, mr, publication, scan, footer);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private record Answer(int status, @Nullable JsonNode json, String text) {}

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
                    // A 204 such as bulk_publish's carries no body, so only a non-empty success body is JSON.
                    boolean success = response.statusCode().is2xxSuccessful();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(text -> new Answer(
                                    status, success && !text.isBlank() ? MAPPER.readTree(text) : null, text));
                })
                .block(GRAPHQL_TIMEOUT);
        if (answer == null) throw new IllegalStateException("GitLab answered without a response");
        return answer;
    }

    private record Request(URI uri, String authorization) {}

    // The address and credential are local facts; resolving them is never part of a provider request.
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
            long scopeId,
            MrCoordinates mr,
            List<LineNote> items,
            FeedbackTarget target,
            String projectUrl,
            Footer footer) {
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
                    indexDiscussion(discussion, currentUserId, target, projectUrl, expected, copies, footer);
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

    private void indexDiscussion(
            @Nullable Object node,
            String currentUserId,
            FeedbackTarget target,
            String projectUrl,
            Map<String, LineNote> expected,
            Map<String, Copy> copies,
            Footer footer) {
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
                copyForm = copyForm(note, body, item, target, projectUrl, footer);
                rangePending = copyForm == CopyForm.LINE_NOTE && placesRange(body, footer, item);
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

    private @Nullable CopyForm copyForm(
            Map<?, ?> note, String body, LineNote item, FeedbackTarget target, String projectUrl, Footer footer) {
        ItemVersion version = target.version();
        FeedbackAnchor diff = item.anchor();
        String revision = diff.side() == CitationSide.OLD ? version.baseVersion() : ScmVersion.reviewedHead(version);
        if (diff.endLine() != null && diff.side() == null) return null;
        // Only a position the response answered with null proves an ordinary comment; a missing one
        // proves nothing.
        boolean unpositioned = note.containsKey("position") && note.get("position") == null;
        if (unpositioned && revision != null && body.equals(locationBody(footer, projectUrl, revision, diff, item))) {
            return CopyForm.FILE_NOTE;
        }
        boolean lineCopy = diff.endLine() != null
                && revision != null
                && (body.equals(postedBody(footer, item)) || placesRange(body, footer, item))
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

    // Encode each project segment independently so namespace slashes retain their structure.
    private String projectUrl(long scopeId, MrCoordinates mr) {
        String serverUrl = tokenService.resolveServerUrl(scopeId);
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return base + "/" + encodedPath(mr.projectPath());
    }

    // A range copy names its placement; a body without it is an end-line copy, as written before ranges.
    private String lineBody(Footer footer, LineNote item) {
        if (!ranged(item.anchor())) return postedBody(footer, item);
        return footer.appendLineNotePrompt(PostedText.of(item.body()))
                + "\n"
                + item.writeMarker()
                + "\n"
                + RANGE_PLACEMENT
                + "\n"
                + ScmFeedbackFormatter.deliveryTag(item.writeKey());
    }

    private boolean placesRange(String body, Footer footer, LineNote item) {
        return ranged(item.anchor()) && body.equals(lineBody(footer, item));
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

    private String postedBody(Footer footer, LineNote item) {
        String body = footer.appendLineNotePrompt(PostedText.of(item.body()));
        body = body + "\n" + item.writeMarker();
        String key = item.writeKey();
        return body + "\n" + ScmFeedbackFormatter.deliveryTag(key);
    }

    // File names stay inside the encoded URL; link text contains only line coordinates.
    private String locationBody(Footer footer, String projectUrl, String revision, FeedbackAnchor diff, LineNote item) {
        if (diff.endLine() == null)
            return "**[View file]("
                    + projectUrl
                    + "/-/blob/"
                    + revision
                    + "/"
                    + encodedPath(diff.path())
                    + ")**\n\n"
                    + postedBody(footer, item);
        Integer start = diff.startLine();
        boolean range = start != null && start < diff.endLine();
        String lines = range ? start + "-" + diff.endLine() : String.valueOf(diff.endLine());
        String label =
                range ? "View code at lines " + start + "–" + diff.endLine() : "View code at line " + diff.endLine();
        String link = projectUrl + "/-/blob/" + revision + "/" + encodedPath(diff.path()) + "#L" + lines;
        return "**[" + label + "](" + link + ")**\n\n" + postedBody(footer, item);
    }

    private static String encodedPath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment ->
                        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
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

    private static @Nullable Integer line(JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).intValue() : null;
    }

    private static @Nullable String text(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }
}
