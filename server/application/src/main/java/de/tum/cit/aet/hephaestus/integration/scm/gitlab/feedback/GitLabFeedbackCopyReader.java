package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackCopyReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebClients;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback.GitLabMrResolver.MrCoordinates;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabFeedbackCopyReader implements FeedbackCopyReader {
    private static final JsonMapper PROVIDER_JSON = new JsonMapper();
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 3;
    private final GitLabGraphQlClientProvider provider;
    private final ScmFeedbackTargetResolver targets;
    private final GitLabTokenService tokens;
    private final WebClient rest;
    private final ObjectMapper mapper;

    public GitLabFeedbackCopyReader(
            GitLabGraphQlClientProvider provider,
            ScmFeedbackTargetResolver targets,
            GitLabTokenService tokens,
            GitLabWebClients rest,
            ObjectMapper mapper) {
        this.provider = provider;
        this.targets = targets;
        this.tokens = tokens;
        this.rest = rest.builder().build();
        this.mapper = mapper;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public List<CopyReadback> read(FeedbackTarget target, List<CopyReadRequest> copies) {
        if (copies.isEmpty()) return List.of();
        if (copies.size() > 31) throw new IllegalArgumentException("A package has at most 31 copies");
        var found = new HashMap<Long, JsonNode>();
        try {
            long workspace = target.item().workspaceId();
            if (provider.isRateLimitCritical(workspace)) return unavailable(copies);
            String subject = targets.subject(target, kind());
            boolean issue = GitLabSummaryWriter.isIssueSubject(subject);
            var item = issue
                    ? GitLabMrResolver.parseIssueSubjectExternalId(subject)
                    : GitLabMrResolver.parseSubjectExternalId(subject);
            var requested = copies.stream()
                    .map(copy -> GitLabSyncConstants.extractNumericId(copy.externalRef()))
                    .toList();
            String cursor = null;
            boolean complete = false;
            String author = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                var response = provider.forScope(workspace)
                        .documentName("GetOwnedFeedbackCopies")
                        .variable("fullPath", item.projectPath())
                        .variable("iid", String.valueOf(item.iid()))
                        .variable("last", PAGE_SIZE)
                        .variable("before", cursor)
                        .variable("issue", issue)
                        .execute()
                        .block(GRAPHQL_TIMEOUT);
                if (response == null || !response.getErrors().isEmpty()) return unavailable(copies);
                String viewer = response.field("currentUser.id").getValue();
                if (viewer == null || viewer.isBlank() || (author != null && !author.equals(viewer)))
                    return unavailable(copies);
                author = viewer;
                Object payload = response.field(issue ? "project.issue.notes" : "project.mergeRequest.notes")
                        .getValue();
                JsonNode notes = PROVIDER_JSON.valueToTree(payload);
                if (!notes.path("nodes").isArray()
                        || !notes.path("pageInfo").path("hasPreviousPage").isBoolean()) return unavailable(copies);
                for (var note : notes.path("nodes")) {
                    String id = text(note, "id");
                    if (id == null) return unavailable(copies);
                    long numeric = GitLabSyncConstants.extractNumericId(id);
                    if (requested.contains(numeric) && found.put(numeric, note) != null) return unavailable(copies);
                }
                complete = !notes.path("pageInfo").path("hasPreviousPage").asBoolean();
                if (complete || found.keySet().containsAll(requested)) break;
                String previous = text(notes.path("pageInfo"), "startCursor");
                if (previous == null || previous.equals(cursor)) return unavailable(copies);
                cursor = previous;
            }
            if (author == null) return unavailable(copies);
            String verifiedAuthor = author;
            boolean fullyRead = complete;
            return copies.stream()
                    .map(copy -> snapshot(
                            target,
                            item,
                            issue,
                            copy,
                            found.get(GitLabSyncConstants.extractNumericId(copy.externalRef())),
                            verifiedAuthor,
                            fullyRead))
                    .toList();
        } catch (RuntimeException unavailable) {
            return unavailable(copies);
        }
    }

    private static List<CopyReadback> unavailable(List<CopyReadRequest> copies) {
        return copies.stream()
                .map(copy -> CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE))
                .toList();
    }

    private CopyReadback snapshot(
            FeedbackTarget target,
            MrCoordinates item,
            boolean issue,
            CopyReadRequest copy,
            @Nullable JsonNode note,
            String author,
            boolean complete) {
        if (copy.anchor() != null && copy.threadRef() == null)
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (note == null)
            return CopyReadback.refused(
                    copy.copyId(), complete ? CopyReadback.Status.GONE : CopyReadback.Status.UNAVAILABLE);
        if (text(note.path("author"), "id") == null
                || !note.path("system").isBoolean()
                || !note.path("internal").isBoolean())
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (!author.equals(text(note.path("author"), "id"))
                || note.path("system").asBoolean()
                || note.path("internal").asBoolean())
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        var editor = note.path("lastEditedBy");
        if (editor.isMissingNode() || (!editor.isNull() && text(editor, "id") == null))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (!editor.isNull() && !author.equals(text(editor, "id")))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.HUMAN_EDITED);
        String body = text(note, "body");
        String rendered = text(note, "bodyHtml");
        if (body == null || rendered == null || body.length() > 128_000 || rendered.length() > 128_000)
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        if (!ScmFeedbackFormatter.carries(body, copy.writeMarker()))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        if (!note.path("resolved").isBoolean())
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
        boolean resolved = note.path("resolved").asBoolean();
        var discussion = note.path("discussion");
        if (copy.threadRef() != null && !copy.threadRef().equals(text(discussion, "id")))
            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
        if (discussion.isObject()) {
            if (!discussion.path("notes").path("nodes").isArray()
                    || !discussion
                            .path("notes")
                            .path("pageInfo")
                            .path("hasNextPage")
                            .isBoolean()) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            if (discussion.path("notes").path("nodes").size() != 1
                    || discussion
                            .path("notes")
                            .path("pageInfo")
                            .path("hasNextPage")
                            .asBoolean()) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.HUMAN_EDITED);
            if (!discussion.path("resolved").isBoolean())
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            resolved |= discussion.path("resolved").asBoolean();
        }
        FeedbackAnchor anchor = null;
        boolean outdated = false;
        var position = note.path("position");
        if (!position.isNull()) {
            if (!position.isObject() || issue)
                return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            String head = text(position.path("diffRefs"), "headSha");
            if (head == null) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            outdated = !head.equals(ScmVersion.reviewedHead(target.version()));
            var raw = position(target, item, copy, note);
            if (raw == null) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.UNAVAILABLE);
            anchor = anchor(raw);
        }
        return new CopyReadback(copy.copyId(), CopyReadback.Status.FOUND, body, rendered, anchor, resolved, outdated);
    }

    /** GraphQL omits range starts; the REST note must describe the same body and update before it supplies one. */
    private @Nullable JsonNode position(
            FeedbackTarget target, MrCoordinates item, CopyReadRequest copy, JsonNode observed) {
        long workspace = target.item().workspaceId();
        long id = GitLabSyncConstants.extractNumericId(copy.externalRef());
        var uri = UriComponentsBuilder.fromUriString(targets.serverUrl(target, kind()))
                .pathSegment(
                        "api",
                        "v4",
                        "projects",
                        item.projectPath(),
                        "merge_requests",
                        String.valueOf(item.iid()),
                        "notes",
                        String.valueOf(id))
                .build()
                .encode()
                .toUri();
        var raw = rest.get()
                .uri(uri)
                .header("Authorization", "Bearer " + tokens.getAccessToken(workspace))
                .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, workspace)
                .exchangeToMono(response -> {
                    provider.updateRateLimit(workspace, response.headers().asHttpHeaders());
                    if (!response.statusCode().is2xxSuccessful())
                        return response.releaseBody().then(Mono.<JsonNode>empty());
                    return response.bodyToMono(String.class).map(mapper::readTree);
                })
                .block(GRAPHQL_TIMEOUT);
        if (raw == null || !raw.isObject() || !raw.path("body").equals(observed.path("body"))) return null;
        String restTime = text(raw, "updated_at");
        String observedTime = text(observed, "updatedAt");
        if (restTime == null || observedTime == null || !Instant.parse(restTime).equals(Instant.parse(observedTime)))
            return null;
        var position = raw.path("position");
        if (!position.isObject()
                || !position.path("head_sha")
                        .equals(observed.path("position").path("diffRefs").path("headSha"))) return null;
        return position;
    }

    private static FeedbackAnchor anchor(JsonNode position) {
        Integer next = line(position, "new_line");
        Integer previous = line(position, "old_line");
        if (next != null && previous != null)
            throw new IllegalArgumentException("The provider returned an ambiguous side");
        CitationSide side = next == null ? CitationSide.OLD : CitationSide.NEW;
        String path = text(position, side == CitationSide.NEW ? "new_path" : "old_path");
        if (path == null) throw new IllegalArgumentException("The provider omitted the note's path");
        var range = position.path("line_range");
        Integer end = next == null ? previous : next;
        if (end == null) return new FeedbackAnchor(path, null, null, null);
        Integer start = null;
        if (!range.isNull() && !range.isMissingNode()) {
            String field = side == CitationSide.NEW ? "new_line" : "old_line";
            start = line(range.path("start"), field);
            Integer last = line(range.path("end"), field);
            if (start == null || last == null || !end.equals(last))
                throw new IllegalArgumentException("The provider returned an incomplete range");
        }
        return new FeedbackAnchor(path, start == null ? end : start, end, side);
    }

    private static @Nullable Integer line(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0)
            throw new IllegalArgumentException("The provider returned an invalid line");
        return value.asInt();
    }

    private static @Nullable String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
