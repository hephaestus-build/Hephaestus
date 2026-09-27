package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabBackwardPageInfo;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedCommentThreads;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient.Request;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.client.ClientGraphQlResponse;
import reactor.core.publisher.Mono;

/** The real {@link GitlabSummaryChannel} over a GitLab whose issue and merge request notes the test controls. */
public final class ScriptedGitlabNotes {

    private final ScriptedCommentThreads threads = new ScriptedCommentThreads("gid://gitlab/Note/");
    private final SummaryChannel channel;

    public ScriptedGitlabNotes(OutboundEgressGuard egressGuard) {
        GitLabGraphQlClientProvider provider = mock(GitLabGraphQlClientProvider.class);
        when(provider.forScope(anyLong())).thenReturn(ScriptedGraphQlClient.of(this::answer));
        channel = new GitlabSummaryChannel(provider, new GitlabMrResolver(provider), egressGuard);
    }

    public SummaryChannel channel() {
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

    private Mono<ClientGraphQlResponse> answer(Request request) {
        return switch (request.document()) {
            case "GetIssueGlobalId" -> threads.resolve(Map.of("project.issue.id", issue(iid(request))));
            case "GetMergeRequestGlobalId" ->
                threads.resolve(Map.of("project.mergeRequest.id", mergeRequest(iid(request))));
            case "CreateMergeRequestNote" ->
                threads.write(
                        request.text("noteableId"),
                        request.text("body"),
                        id -> Map.of("createNote.note.id", id, "createNote.errors", List.of()));
            case "GetIssueNotesNewest" -> notes("project.issue.notes", issue(iid(request)));
            case "GetMergeRequestNotesNewest" -> notes("project.mergeRequest.notes", mergeRequest(iid(request)));
            default -> Mono.error(new IllegalStateException("Unscripted GitLab document " + request.document()));
        };
    }

    private Mono<ClientGraphQlResponse> notes(String path, String noteable) {
        return threads.read(
                noteable,
                notes -> Map.of(
                        path + ".nodes",
                        notes.stream()
                                .map(note -> Map.of("id", note.id(), "body", note.body()))
                                .toList(),
                        path + ".pageInfo",
                        new GitLabBackwardPageInfo(false, null)));
    }

    private static int iid(Request request) {
        return Integer.parseInt(request.text("iid"));
    }
}
