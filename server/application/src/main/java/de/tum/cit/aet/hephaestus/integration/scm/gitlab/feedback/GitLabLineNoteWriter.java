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
import de.tum.cit.aet.hephaestus.integration.core.spi.LiveHead;
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
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

// Stages a package as private draft notes, then one bulk publication with the summary is the only notifying write.
// A stop before the publication deletes the package's drafts, so a private draft never outlives the send that staged
// it.
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabLineNoteWriter implements LineNoteWriter {

    private static final Logger log = LoggerFactory.getLogger(GitLabLineNoteWriter.class);

    private static final int DISCUSSIONS_PAGE_SIZE = 100;

    private static final int MAX_DISCUSSION_PAGES = 50;

    static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{7,64}");

    // Names the placement of a range copy, so readback never mistakes it for an end-line copy written before ranges.
    static final String RANGE_PLACEMENT = "<!-- hephaestus-gitlab-placement=line-range -->";

    private final GitLabGraphQlClientProvider gitLabProvider;
    private final GitLabNotesApi http;
    private final GitLabTokenService tokenService;
    private final GitLabDiffVersionReader diffVersions;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;
    private final ScmFeedbackFormatter formatter;

    public GitLabLineNoteWriter(
            GitLabGraphQlClientProvider gitLabProvider,
            GitLabNotesApi http,
            GitLabTokenService tokenService,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets,
            GitLabDiffVersionReader diffVersions,
            ScmFeedbackFormatter formatter) {
        this.gitLabProvider = gitLabProvider;
        this.http = http;
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

    /**
     * Reads the merge request's head and its source branch's head. GitLab refreshes the merge request's diff after a
     * push, so only both together say that the reviewed head is still current.
     */
    @Override
    public LiveHead liveHead(FeedbackTarget target) {
        long workspace = target.item().workspaceId();
        if (gitLabProvider.isRateLimitCritical(workspace)) return LiveHead.UNKNOWN;
        try {
            String reviewed = ScmVersion.reviewedHead(target.version());
            if (reviewed == null) return LiveHead.UNKNOWN;
            var mr = GitLabMrResolver.parseSubjectExternalId(targets.subject(target, kind()));
            var request = graphQl(
                    workspace,
                    "GetFeedbackMergeRequestHead",
                    Map.of("fullPath", mr.projectPath(), "iid", String.valueOf(mr.iid())));
            if (request == null) return LiveHead.UNKNOWN;
            String diffHead = request.field("project.mergeRequest.diffHeadSha").getValue();
            String branch = request.field("project.mergeRequest.sourceBranch").getValue();
            String source =
                    request.field("project.mergeRequest.sourceProject.fullPath").getValue();
            if (diffHead == null || branch == null || source == null) return LiveHead.UNKNOWN;
            if (!diffHead.equalsIgnoreCase(reviewed)) return LiveHead.MOVED;
            // The full branch ref: a tag of the same name could still point at the reviewed commit.
            var ref = graphQl(
                    workspace, "GetFeedbackBranchHead", Map.of("fullPath", source, "ref", "refs/heads/" + branch));
            String branchHead = ref == null
                    ? null
                    : ref.field("project.repository.commit.sha").getValue();
            if (branchHead == null) return LiveHead.UNKNOWN;
            if (!branchHead.equalsIgnoreCase(reviewed)) return LiveHead.MOVED;
            // The package needs the rest of the budget: a read that spent it lets the send wait, not fail.
            return gitLabProvider.isRateLimitCritical(workspace) ? LiveHead.UNKNOWN : LiveHead.CURRENT;
        } catch (RuntimeException unknown) {
            return LiveHead.UNKNOWN;
        }
    }

    private @Nullable ClientGraphQlResponse graphQl(long workspace, String document, Map<String, Object> variables) {
        var request = gitLabProvider.forScope(workspace).documentName(document);
        variables.forEach(request::variable);
        ClientGraphQlResponse response = request.execute().block(GRAPHQL_TIMEOUT);
        return response == null || !response.getErrors().isEmpty() ? null : response;
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
            var staging = Staging.of(api, publication);
            var pending = ownDrafts(workspace, api, scan.author(), expected);
            egressGuard.requireDeliveryAllowed("gitlab.publish-feedback-package");
            // A refused fence started nothing: the copies stay as they were.
            if (!fence.beforeCreate(publication.copyIds(), List.of())) return CopyWriteResult.of(List.of());
            fenced = true;
            for (var entry : expected.entrySet()) {
                if (pending.contains(entry.getKey())) continue;
                if (gitLabProvider.isRateLimitCritical(workspace)) return publication.attempted();
                if (!fence.beforeCreate(List.of(), List.of())) return discarded(workspace, staging, publication);
                var created = http.exchange(
                        workspace,
                        HttpMethod.POST,
                        http.authorized(workspace, URI.create(api + "/draft_notes")),
                        entry.getValue());
                int status = created.status();
                // Only a client error proves GitLab refused the draft. A server error or a timeout, such as a proxy's
                // 504, can answer while GitLab still saves it: that draft is settled by a later readback.
                if (status >= 400 && status < 500 && status != 408) return discarded(workspace, staging, publication);
                if (status < 200 || status >= 300) return publication.attempted();
            }
            // Bulk publish includes every draft of this token. Refuse drafts outside this exact checked package.
            if (ownDrafts(workspace, api, scan.author(), expected).size() != expected.size())
                return publication.attempted();
            egressGuard.requireDeliveryAllowed("gitlab.submit-feedback-package");
            if (!fence.beforeNotify()) return discarded(workspace, staging, publication);
            var summary = publication.summary();
            var fields = summary == null
                    ? Map.<String, Object>of()
                    : Map.<String, Object>of("note", footer.summary(summary));
            var submitted = http.exchange(
                    workspace,
                    HttpMethod.POST,
                    http.authorized(workspace, URI.create(api + "/draft_notes/bulk_publish")),
                    fields);
            if (submitted.status() < 200 || submitted.status() >= 300) return publication.attempted();
            var posted = findPosted(target, publication);
            return posted == null || posted.size() != publication.copyIds().size()
                    ? publication.attempted()
                    : CopyWriteResult.of(posted);
        } catch (OutboundEgressSuppressedException suppressed) {
            throw suppressed;
        } catch (RuntimeException unknown) {
            // A request without an answer, a draft creation too, may still land: only a later readback settles it.
            return fenced ? publication.attempted() : publication.notSent();
        }
    }

    /** Where a package stages its drafts: the merge request, and each note's write marker by its delivery key. */
    private record Staging(String api, Map<String, String> markers) {
        static Staging of(String api, OnWorkPackage publication) {
            return new Staging(
                    api,
                    publication.notes().stream().collect(Collectors.toMap(LineNote::writeKey, LineNote::writeMarker)));
        }
    }

    /** Reads all this token's drafts, adopting only exact bodies and positions from this package. */
    private Set<String> ownDrafts(long workspace, String api, long author, Map<String, Map<String, Object>> expected) {
        var found = new HashSet<String>();
        for (var draft : drafts(workspace, api)) {
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

    /** The token's drafts on the merge request. GitLab answers the whole list, unpaginated. */
    private List<JsonNode> drafts(long workspace, String api) {
        var response = http.exchange(
                workspace, HttpMethod.GET, http.authorized(workspace, URI.create(api + "/draft_notes")), null);
        var rows = response.json();
        if (response.status() != 200 || rows == null || !rows.isArray())
            throw new FeedbackDeliveryException("GitLab pending notes were not read completely");
        var result = new ArrayList<JsonNode>();
        rows.forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * Ends a send that stopped before its publication. Nothing was published, so once GitLab confirms that the
     * package's drafts are gone, no copy of the package exists.
     */
    private CopyWriteResult discarded(long workspace, Staging staging, OnWorkPackage publication) {
        return discard(workspace, staging) ? publication.notSent() : publication.attempted();
    }

    /**
     * Deletes the token's drafts of this package: a draft whose closing markers are one of its notes' write marker and
     * delivery tag. GitLab lists and deletes only the token's own drafts; one that a person wrote is left alone.
     */
    private boolean discard(long workspace, Staging staging) {
        if (staging.markers().isEmpty()) return true;
        try {
            for (var draft : drafts(workspace, staging.api())) {
                String body = draft.path("note").asString("");
                String key = ScmFeedbackFormatter.lastDeliveryKey(body);
                if (key == null) continue;
                String marker = staging.markers().get(key);
                if (marker != null
                        && ScmFeedbackFormatter.carries(body, marker)
                        && ScmFeedbackFormatter.carries(body, ScmFeedbackFormatter.deliveryTag(key))
                        && draft.path("id").isIntegralNumber()
                        && !delete(workspace, staging.api(), draft.path("id").longValue())) return false;
            }
            return true;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** Deletes one draft; a draft that is already gone counts as deleted. */
    private boolean delete(long workspace, String api, long draft) {
        int status = http.exchange(
                        workspace,
                        HttpMethod.DELETE,
                        http.authorized(workspace, URI.create(api + "/draft_notes/" + draft)),
                        null)
                .status();
        return (status >= 200 && status < 300) || status == 404;
    }

    private List<JsonNode> pages(long workspace, String endpoint) {
        var result = new ArrayList<JsonNode>();
        for (int page = 1; page <= MAX_DISCUSSION_PAGES; page++) {
            var response = http.exchange(
                    workspace,
                    HttpMethod.GET,
                    http.authorized(workspace, URI.create(endpoint + "?per_page=100&page=" + page)),
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
            // The package's drafts are private: readback deletes them, so a draft that a stop or a crash left never
            // blocks a later package. It deletes them before it reads the notes, so it reads a publication that
            // consumed them instead of missing it.
            var staging = Staging.of(
                    GitLabDiffVersionReader.mergeRequestApi(tokenService.resolveServerUrl(workspace), mr), publication);
            if (!discard(workspace, staging)) return null;
            return packageReceipts(workspace, mr, publication, indexCopies(workspace, mr, publication.notes(), target));
        } catch (RuntimeException unknown) {
            return null;
        }
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
        String segment = discussion != null && discussion.startsWith(GitLabDiscussionNotes.DISCUSSION_GID)
                ? discussion.substring(GitLabDiscussionNotes.DISCUSSION_GID.length())
                : "";
        if (!GitLabDiscussionNotes.SEGMENT.matcher(segment).matches())
            throw new FeedbackDeliveryException("A GitLab range thread has no readable identity");
        long noteId = GitLabSyncConstants.extractNumericId(copy.noteId());
        GitLabNotesApi.Answer answer = http.exchange(scopeId, HttpMethod.GET, http.request(scopeId, mr, segment), null);
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
            var ref = GitLabDiscussionNotes.NOTE_REF.matcher(externalRef);
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
            GitLabNotesApi.Request note;
            GitLabNotesApi.Request user;
            GitLabNotesApi.Request parent;
            try {
                MrCoordinates mr = GitLabMrResolver.parseSubjectExternalId(subject);
                String server = tokenService.resolveServerUrl(workspaceId);
                note = http.authorized(
                        workspaceId,
                        URI.create(GitLabDiffVersionReader.mergeRequestApi(server, mr) + "/notes/" + noteId));
                user = http.authorized(workspaceId, URI.create(server.replaceAll("/+$", "") + "/api/v4/user"));
                parent = http.authorized(workspaceId, URI.create(GitLabDiffVersionReader.mergeRequestApi(server, mr)));
            } catch (RuntimeException unavailable) {
                outcomes.add(CopyUpdateReceipt.transientFailure(
                        copy.copyId(), "The correction request could not be prepared"));
                continue;
            }
            try {
                if (author == null) author = http.currentUser(workspaceId, user);
                GitLabNotesApi.Answer read = http.exchange(workspaceId, HttpMethod.GET, note, null);
                if (read.status() == 404
                        && GitLabDiscussionNotes.NOTE_ABSENT
                                .matcher(read.text())
                                .matches()) {
                    GitLabNotesApi.Answer mergeRequest = http.exchange(workspaceId, HttpMethod.GET, parent, null);
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
                GitLabNotesApi.Answer written;
                try {
                    written = http.exchange(workspaceId, HttpMethod.PUT, note, Map.of("body", rewritten));
                } catch (OutboundEgressSuppressedException silenced) {
                    throw silenced;
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
}
