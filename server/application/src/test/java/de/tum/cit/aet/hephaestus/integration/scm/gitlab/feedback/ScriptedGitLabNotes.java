package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedCommentThreads;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient.Request;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** One GitLab: GraphQL reads and summaries, and REST discussion threads that both of them see. */
public final class ScriptedGitLabNotes {

    private static final String BOT = "gid://gitlab/User/1";
    private static final Pattern DISCUSSIONS = Pattern.compile(".*/merge_requests/(\\d+)/discussions(?:/(d\\d+))?");

    private final ScriptedCommentThreads threads = new ScriptedCommentThreads("gid://gitlab/Note/");
    private final SummaryWriter channel;
    private boolean rejectPositions;
    private final List<Map<String, Object>> drafts = new ArrayList<>();
    private int draftCalls;
    private int failDraft;
    private boolean losePublish;
    private int bulkPublications;

    void failDraft(int call) {
        failDraft = call;
    }

    void losePublish(boolean lose) {
        losePublish = lose;
    }

    int bulkPublications() {
        return bulkPublications;
    }

    int pendingDrafts() {
        return drafts.size();
    }

    void foreignDraft() {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("id", 999L);
        draft.put("author_id", 1L);
        draft.put("note", "An unrelated draft");
        draft.put("discussion_id", null);
        draft.put("position", null);
        drafts.add(draft);
    }

    private final Map<String, Map<String, Object>> positions = new ConcurrentHashMap<>();
    private final GitLabGraphQlClientProvider provider;
    private final ScriptedGitLabRest rest = new ScriptedGitLabRest(this::discuss);

    public ScriptedGitLabNotes(OutboundEgressGuard egressGuard) {
        this(egressGuard, ScmFeedbackFixtures.resolver());
    }

    ScriptedGitLabNotes(OutboundEgressGuard egressGuard, ScmFeedbackTargetResolver targets) {
        provider = mock(GitLabGraphQlClientProvider.class);
        when(provider.forScope(anyLong())).thenReturn(ScriptedGraphQlClient.of(this::answer));
        channel = new GitLabSummaryWriter(
                provider, new GitLabMrResolver(provider), egressGuard, targets, ScmFeedbackFixtures.formatter());
    }

    void rejectPositions(boolean reject) {
        rejectPositions = reject;
    }

    GitLabGraphQlClientProvider provider() {
        return provider;
    }

    WebClient.Builder rest() {
        return rest.builder();
    }

    public SummaryWriter channel() {
        return channel;
    }

    public ScriptedCommentThreads threads() {
        return threads;
    }

    public static String issue(int iid) {
        return "gid://gitlab/Issue/" + iid;
    }

    public static String mergeRequest(int iid) {
        return "gid://gitlab/MergeRequest/" + iid;
    }

    private static long number(String globalId) {
        return Long.parseLong(globalId.substring(globalId.lastIndexOf('/') + 1));
    }

    // GitLab's REST discussions API: a diff thread with the asked position, or an overview thread without one.
    @SuppressWarnings("unchecked")
    private Mono<ClientResponse> discuss(ScriptedGitLabRest.Call call) {
        var pending = Pattern.compile(".*/merge_requests/(\\d+)/(draft_notes(?:/bulk_publish)?|notes)")
                .matcher(call.path());
        if (pending.matches()) return drafts(call, Integer.parseInt(pending.group(1)), pending.group(2));
        var path = DISCUSSIONS.matcher(call.path());
        if (!path.matches()) return Mono.error(new IllegalStateException("Unscripted GitLab REST call " + call.path()));
        String thread = mergeRequest(Integer.parseInt(path.group(1)));
        if (HttpMethod.GET.equals(call.method())) {
            for (var note : threads.on(thread))
                if (("d" + number(note.id())).equals(path.group(2)))
                    return ScriptedGitLabRest.respond(200, discussion(note.id(), note.body()));
            return ScriptedGitLabRest.respond(404, Map.of("message", "404 Not found"));
        }
        Map<String, Object> request = call.json();
        Object position = request.get("position");
        if (position != null && rejectPositions)
            return ScriptedGitLabRest.respond(
                    400, Map.of("message", "400 Bad request - Note {:position=>[\"is invalid\"]}"));
        String body = Objects.requireNonNull((String) request.get("body"));
        String id = threads.append(
                thread, position == null ? "gid://gitlab/DiscussionNote/" : "gid://gitlab/DiffNote/", body);
        if (position != null) positions.put(id, (Map<String, Object>) position);
        return ScriptedGitLabRest.respond(201, discussion(id, body));
    }

    @SuppressWarnings("unchecked") // The fake stores the draft payloads submitted by this client.
    private Mono<ClientResponse> drafts(ScriptedGitLabRest.Call call, int iid, String endpoint) {
        String parent = mergeRequest(iid);
        if (HttpMethod.GET.equals(call.method())) {
            if (endpoint.equals("draft_notes")) return ScriptedGitLabRest.respond(200, drafts);
            return ScriptedGitLabRest.respond(
                    200,
                    threads.on(parent).stream()
                            .map(note -> {
                                var row = new LinkedHashMap<String, Object>();
                                row.put("id", number(note.id()));
                                row.put("body", note.body());
                                row.put("author", Map.of("id", 1));
                                row.put("system", false);
                                return row;
                            })
                            .toList());
        }
        var fields = call.json();
        if (endpoint.equals("draft_notes")) {
            draftCalls++;
            if (draftCalls == failDraft || (rejectPositions && fields.get("position") != null))
                return ScriptedGitLabRest.respond(429, Map.of("message", "Draft creation refused"));
            var draft = new LinkedHashMap<String, Object>();
            draft.put("id", (long) draftCalls);
            draft.put("author_id", 1);
            draft.put("note", fields.get("note"));
            draft.put("discussion_id", null);
            draft.put("position", fields.get("position"));
            drafts.add(draft);
            return ScriptedGitLabRest.respond(201, draft);
        }
        if (!endpoint.equals("draft_notes/bulk_publish")) return Mono.error(new IllegalArgumentException(endpoint));
        bulkPublications++;
        for (var draft : drafts) {
            Object position = draft.get("position");
            String id = threads.append(
                    parent,
                    position == null ? "gid://gitlab/DiscussionNote/" : "gid://gitlab/DiffNote/",
                    Objects.requireNonNull((String) draft.get("note")));
            if (position != null) positions.put(id, (Map<String, Object>) position);
        }
        drafts.clear();
        if (fields.get("note") instanceof String summary) threads.append(parent, "gid://gitlab/Note/", summary);
        return losePublish
                ? Mono.error(new IllegalStateException("Response lost after bulk publication"))
                : ScriptedGitLabRest.respond(204, "");
    }

    private Map<String, Object> discussion(String id, String body) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", number(id));
        note.put("type", id.contains("/DiffNote/") ? "DiffNote" : "DiscussionNote");
        note.put("body", body);
        note.put("system", false);
        note.put("author", Map.of("id", number(BOT)));
        note.put("position", positions.get(id));
        note.put("resolvable", true);
        return Map.of("id", "d" + number(id), "individual_note", false, "notes", List.of(note));
    }

    private Mono<ClientGraphQlResponse> answer(Request request) {
        return switch (request.document()) {
            case "GetIssueGlobalId" -> threads.resolve(Map.of("project.issue.id", issue(iid(request))));
            case "GetMergeRequestGlobalId" ->
                threads.resolve(Map.of("project.mergeRequest.id", mergeRequest(iid(request))));
            case "GetMergeRequestDiscussions" ->
                threads.read(
                        mergeRequest(iid(request)),
                        notes -> Map.of(
                                "currentUser.id",
                                BOT,
                                "project.mergeRequest.discussions",
                                Map.of(
                                        "pageInfo",
                                        Map.of("hasNextPage", false),
                                        "nodes",
                                        notes.stream()
                                                .map(note -> {
                                                    Map<String, Object> node = new HashMap<>();
                                                    node.put("id", note.id());
                                                    node.put("body", note.body());
                                                    node.put("author", Map.of("id", BOT));
                                                    node.put("position", projected(positions.get(note.id())));
                                                    return Map.of(
                                                            "id",
                                                            "gid://gitlab/Discussion/d" + number(note.id()),
                                                            "notes",
                                                            Map.of(
                                                                    "nodes",
                                                                    List.of(node),
                                                                    "pageInfo",
                                                                    Map.of("hasNextPage", false)));
                                                })
                                                .toList())));
            case "CreateMergeRequestNote" ->
                threads.write(
                        request.text("noteableId"),
                        request.text("body"),
                        id -> Map.of(
                                "createNote.note.id",
                                id,
                                "createNote.errors",
                                List.of(),
                                "createNote",
                                Map.of("note", Map.of("id", id), "errors", List.of())));
            case "GetIssueNotesNewest" -> notes("project.issue.notes", issue(iid(request)));
            case "GetMergeRequestNotesNewest" -> notes("project.mergeRequest.notes", mergeRequest(iid(request)));
            default -> Mono.error(new IllegalStateException("Unscripted GitLab document " + request.document()));
        };
    }

    // GraphQL's DiffPosition carries the position's own line, not its range.
    private static @Nullable Map<String, Object> projected(@Nullable Map<String, Object> rest) {
        if (rest == null) return null;
        Map<String, Object> position = new HashMap<>();
        position.put("oldPath", rest.get("old_path"));
        position.put("newPath", rest.get("new_path"));
        position.put("oldLine", rest.get("old_line"));
        position.put("newLine", rest.get("new_line"));
        position.put(
                "diffRefs",
                Map.of(
                        "baseSha",
                        rest.get("base_sha"),
                        "headSha",
                        rest.get("head_sha"),
                        "startSha",
                        rest.get("start_sha")));
        return position;
    }

    private Mono<ClientGraphQlResponse> notes(String path, String noteable) {
        return threads.read(
                noteable,
                notes -> Map.of(
                        "currentUser.id",
                        BOT,
                        path,
                        Map.of(
                                "nodes",
                                notes.stream()
                                        .map(note -> Map.of(
                                                "id", note.id(), "body", note.body(), "author", Map.of("id", BOT)))
                                        .toList(),
                                "pageInfo",
                                Map.of("hasPreviousPage", false))));
    }

    private static int iid(Request request) {
        return Integer.parseInt(request.text("iid"));
    }
}
