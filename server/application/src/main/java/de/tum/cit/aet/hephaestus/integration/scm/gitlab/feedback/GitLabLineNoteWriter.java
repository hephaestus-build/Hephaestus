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
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter.Footer;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebClients;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import java.net.URI;
import java.util.ArrayList;
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

    static final JsonMapper MAPPER = new JsonMapper();

    private static final int DISCUSSIONS_PAGE_SIZE = 100;

    private static final int MAX_DISCUSSION_PAGES = 50;

    static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

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
            GitLabWebClients rest,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets,
            GitLabDiffVersionReader diffVersions,
            ScmFeedbackFormatter formatter) {
        this.gitLabProvider = gitLabProvider;
        this.rest = rest.builder().build();
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
            var scan = indexCopies(workspace, mr, publication.notes(), target);
            var found = packageReceipts(workspace, mr, publication, scan);
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
                    || !(fields.get("note") instanceof String note)
                    || !ScmFeedbackFormatter.sameCopy(body, note)
                    || !found.add(key)
                    || !draft.path("author_id").isIntegralNumber()
                    || draft.path("author_id").longValue() != author
                    || !draft.path("id").isIntegralNumber()
                    || draft.path("id").longValue() <= 0
                    || !draft.path("discussion_id").isNull()
                    || !GitLabDiscussionNotes.sameDraftPosition(draft.path("position"), fields.get("position")))
                throw new FeedbackDeliveryException("Unrelated or changed draft notes prevent bulk publication");
        }
        return found;
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

    private List<CopyReceipt> packageReceipts(long workspace, MrCoordinates mr, OnWorkPackage publication, Scan scan) {
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
                String marker = summary.writeMarker();
                if (marker == null || !body.contains(marker)) continue;
                if (!ScmFeedbackFormatter.carries(body, marker)
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
            var mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, kind()));
            var scan = indexCopies(workspace, mr, publication.notes(), target);
            return packageReceipts(workspace, mr, publication, scan);
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
    private Scan indexCopies(long scopeId, MrCoordinates mr, List<LineNote> items, FeedbackTarget target) {
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
                    indexDiscussion(discussion, currentUserId, target, expected, copies);
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
                copyForm = GitLabDiscussionNotes.copyForm(note, body, item, target);
                rangePending = copyForm == CopyForm.LINE_NOTE && GitLabDiscussionNotes.placesRange(body, item);
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

    // Encode each project segment independently so namespace slashes retain their structure.
    private String projectUrl(long scopeId, MrCoordinates mr) {
        String serverUrl = tokenService.resolveServerUrl(scopeId);
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        return base + "/" + GitLabDiscussionNotes.encodedPath(mr.projectPath());
    }

    // A range copy names its placement; a body without it is an end-line copy, as written before ranges.
    private String lineBody(Footer footer, LineNote item) {
        if (!GitLabDiscussionNotes.ranged(item.anchor())) return postedBody(footer, item);
        return footer.appendLineNotePrompt(PostedText.of(item.body()))
                + "\n"
                + ScmFeedbackFormatter.provenanceLine(item.provenance())
                + item.writeMarker()
                + "\n"
                + RANGE_PLACEMENT
                + "\n"
                + ScmFeedbackFormatter.deliveryTag(item.writeKey());
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
        return copy.confirmed(Objects.equals(anchor.startLine(), GitLabDiscussionNotes.line(range.path("start"), side))
                && Objects.equals(anchor.endLine(), GitLabDiscussionNotes.line(range.path("end"), side)));
    }

    private String postedBody(Footer footer, LineNote item) {
        String body = footer.appendLineNotePrompt(PostedText.of(item.body()));
        body = body + "\n" + ScmFeedbackFormatter.provenanceLine(item.provenance()) + item.writeMarker();
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
                    + GitLabDiscussionNotes.encodedPath(diff.path())
                    + ")**\n\n"
                    + postedBody(footer, item);
        Integer start = diff.startLine();
        boolean range = start != null && start < diff.endLine();
        String lines = range ? start + "-" + diff.endLine() : String.valueOf(diff.endLine());
        String label =
                range ? "View code at lines " + start + "–" + diff.endLine() : "View code at line " + diff.endLine();
        String link = projectUrl + "/-/blob/" + revision + "/" + GitLabDiscussionNotes.encodedPath(diff.path()) + "#L"
                + lines;
        return "**[" + label + "](" + link + ")**\n\n" + postedBody(footer, item);
    }

    private record Copy(
            boolean verified,
            String noteId,
            @Nullable String discussionId,
            @Nullable String url,
            @Nullable CopyForm copyForm,
            boolean rangePending) {
        CopyReceipt preserved(LineNote item) {
            return new CopyReceipt(
                    item.copyId(), CopyDisposition.PRESERVED_EXISTING, noteId, url, null, copyForm, discussionId);
        }

        Copy confirmed(boolean range) {
            return new Copy(range, noteId, discussionId, url, range ? copyForm : null, false);
        }
    }

    private record Scan(Map<String, Copy> copies, long author) {}

    // GitLab has no node(id:) root field; its merge request notes API reads and edits every note type by REST id.
    @Override
    public List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> copies) {
        return rewrite(target, copies, (copy, type, original) -> ScmFeedbackFormatter.withdrawnCopyBody(original));
    }

    @Override
    public List<CopyUpdateReceipt> restore(FeedbackTarget target, List<RetractedNote> notes) {
        var footer = formatter.footer(target.item());
        var byCopy = notes.stream().collect(Collectors.toMap(note -> note.copy().copyId(), RetractedNote::note));
        return rewrite(target, notes.stream().map(RetractedNote::copy).toList(), (copy, type, original) -> {
            var item = Objects.requireNonNull(byCopy.get(copy.copyId()));
            if (!item.writeKey().equals(ScmFeedbackFormatter.lastDeliveryKey(original))) return null;
            if ("DiffNote".equals(type)) return lineBody(footer, item);
            var mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, kind()));
            String revision = item.anchor().side() == CitationSide.OLD
                    ? target.version().baseVersion()
                    : ScmVersion.reviewedHead(target.version());
            return revision == null
                    ? null
                    : locationBody(footer, projectUrl(target.item().workspaceId(), mr), revision, item.anchor(), item);
        });
    }

    @Override
    public List<CopyUpdateReceipt> editOwn(FeedbackTarget target, List<OwnedEdit> notes) {
        var footer = formatter.footer(target.item());
        var byCopy = notes.stream().collect(Collectors.toMap(note -> note.copy().copyId(), note -> note));
        return rewrite(target, notes.stream().map(OwnedEdit::copy).toList(), (copy, type, original) -> {
            var edit = Objects.requireNonNull(byCopy.get(copy.copyId()));
            if (!edit.previousWriteKey().equals(ScmFeedbackFormatter.lastDeliveryKey(original))) return null;
            if ("DiffNote".equals(type)) return lineBody(footer, edit.note());
            var mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, kind()));
            String revision = edit.note().anchor().side() == CitationSide.OLD
                    ? target.version().baseVersion()
                    : ScmVersion.reviewedHead(target.version());
            return revision == null
                    ? null
                    : locationBody(
                            footer,
                            projectUrl(target.item().workspaceId(), mr),
                            revision,
                            edit.note().anchor(),
                            edit.note());
        });
    }

    /** The new text of one of the author's own notes, or null when this note does not belong to the copy. */
    @FunctionalInterface
    private interface Rewrite {
        @Nullable
        String body(CopyReceipt copy, String type, String original);
    }

    private List<CopyUpdateReceipt> rewrite(FeedbackTarget target, List<CopyReceipt> copies, Rewrite rewrite) {
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
                outcomes.add(GitLabDiscussionNotes.unsupported(copy, "Existing copy has no GitLab note identity"));
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
                String original = GitLabDiscussionNotes.text(found, "body");
                if (!GitLabDiscussionNotes.ownNote(found, noteId, type, author)
                        || original == null
                        || ScmFeedbackFormatter.lastDeliveryKey(original) == null) {
                    outcomes.add(
                            GitLabDiscussionNotes.unsupported(copy, "Existing copy authority could not be verified"));
                    continue;
                }
                String rewritten = rewrite.body(copy, type, original);
                if (rewritten == null) {
                    outcomes.add(
                            GitLabDiscussionNotes.unsupported(copy, "Existing copy authority could not be verified"));
                    continue;
                }
                if (original.equals(rewritten)) {
                    outcomes.add(GitLabDiscussionNotes.edited(copy));
                    continue;
                }
                egressGuard.requireDeliveryAllowed("gitlab.correct-feedback-copy");
                Answer written;
                try {
                    written = exchange(workspaceId, HttpMethod.PUT, note, Map.of("body", rewritten));
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
                        && GitLabDiscussionNotes.ownNote(stored, noteId, type, author)
                        && rewritten.equals(GitLabDiscussionNotes.text(stored, "body"));
                outcomes.add(
                        confirmed
                                ? GitLabDiscussionNotes.edited(copy)
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

    @Override
    public @Nullable String actor(FeedbackTarget target) {
        long workspaceId = target.item().workspaceId();
        try {
            String server = tokenService.resolveServerUrl(workspaceId);
            return Long.toString(currentUser(
                    workspaceId, authorized(workspaceId, URI.create(server.replaceAll("/+$", "") + "/api/v4/user"))));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    // A discussion is resolved when its resolvable notes are; the owned note starts it, so its flag is the thread's.
    @Override
    public ThreadChange resolveThread(FeedbackTarget target, CopyReceipt copy, boolean resolved, String actor) {
        String subject = targets.subject(target, kind());
        long workspaceId = target.item().workspaceId();
        String externalRef = copy.externalRef();
        String thread = copy.threadRef();
        var ref = externalRef == null ? null : NOTE_REF.matcher(externalRef);
        String segment =
                thread != null && thread.startsWith(DISCUSSION_GID) ? thread.substring(DISCUSSION_GID.length()) : "";
        if (ref == null || !ref.matches() || !SEGMENT.matcher(segment).matches())
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.NOT_OWNED, "Copy has no GitLab thread identity");
        if (gitLabProvider.isRateLimitCritical(workspaceId))
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "The GitLab rate limit is critical");
        String type = ref.group(1);
        long noteId = Long.parseLong(ref.group(2));
        try {
            MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(subject);
            String server = tokenService.resolveServerUrl(workspaceId);
            // The account that wrote the note is the account that resolves it: the actor recorded before this write.
            long author = Long.parseLong(actor);
            Answer read = exchange(workspaceId, HttpMethod.GET, request(workspaceId, mr, segment), null);
            if (read.status() == 404 && NOTE_ABSENT.matcher(read.text()).matches()) {
                Answer parent = exchange(
                        workspaceId,
                        HttpMethod.GET,
                        authorized(workspaceId, URI.create(GitLabDiffVersionReader.mergeRequestApi(server, mr))),
                        null);
                JsonNode readable = parent.status() == 200 ? parent.json() : null;
                return readable != null
                                && readable.path("iid").isIntegralNumber()
                                && readable.path("iid").longValue() == mr.iid()
                        ? ThreadChange.of(
                                copy.copyId(), ThreadChange.Outcome.GONE, "The provider thread no longer exists")
                        : ThreadChange.of(
                                copy.copyId(), ThreadChange.Outcome.TRANSIENT, "The merge request could not be read");
            }
            JsonNode found = read.status() == 200 ? read.json() : null;
            Boolean current = threadState(found, noteId, type, author);
            if (current == null)
                return ThreadChange.of(
                        copy.copyId(),
                        ThreadChange.Outcome.NOT_OWNED,
                        "The thread no longer starts with the owned note");
            JsonNode resolver =
                    Objects.requireNonNull(found).path("notes").get(0).path("resolved_by");
            if (current
                    && !(resolver.path("id").isIntegralNumber()
                            && resolver.path("id").longValue() == author))
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TAKEN, "Another account resolved the thread");
            if (current == resolved) return ThreadChange.done(copy.copyId());
            if (resolved && !endsWithOwn(found, author))
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.REPLIED, "A person replied after the owned note");
            if (!egressGuard.deliveryAllowed(
                    resolved ? "gitlab.resolve-feedback-thread" : "gitlab.reopen-feedback-thread"))
                return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.DEFERRED, "Silent Mode holds the change");
            Answer written;
            try {
                written = exchange(
                        workspaceId, HttpMethod.PUT, request(workspaceId, mr, segment), Map.of("resolved", resolved));
            } catch (RuntimeException unknown) {
                return ThreadChange.of(
                        copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider thread change outcome is unknown");
            }
            Boolean stored = written.status() == 200 ? threadState(written.json(), noteId, type, author) : null;
            return Objects.equals(stored, resolved)
                    ? ThreadChange.done(copy.copyId())
                    : ThreadChange.of(
                            copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider did not confirm the thread state");
        } catch (RuntimeException e) {
            return ThreadChange.of(copy.copyId(), ThreadChange.Outcome.TRANSIENT, "Provider thread change failed");
        }
    }

    /** Whether the discussion that starts with the owned note is resolved; null when it does not start with it. */
    private static @Nullable Boolean threadState(@Nullable JsonNode discussion, long noteId, String type, long author) {
        if (discussion == null
                || !discussion.path("notes").isArray()
                || discussion.path("notes").isEmpty()) return null;
        JsonNode first = discussion.path("notes").get(0);
        if (!GitLabDiscussionNotes.ownNote(first, noteId, type, author)
                || !first.path("resolvable").booleanValue()
                || !first.path("resolved").isBoolean()) return null;
        return first.path("resolved").booleanValue();
    }

    // A system note, such as "resolved all threads", is no reply.
    private static boolean endsWithOwn(JsonNode discussion, long author) {
        JsonNode last = null;
        for (JsonNode note : discussion.path("notes")) if (!note.path("system").booleanValue()) last = note;
        return last != null
                && last.path("author").path("id").isIntegralNumber()
                && last.path("author").path("id").longValue() == author;
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
}
