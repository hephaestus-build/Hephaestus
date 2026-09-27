package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedCommentThreads;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient.Request;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.client.ClientGraphQlResponse;
import reactor.core.publisher.Mono;

/** The real {@link GithubSummaryChannel} over a GitHub whose issue and pull request comments the test controls. */
public final class ScriptedGithubComments {

    private final ScriptedCommentThreads threads = new ScriptedCommentThreads("IC_");
    private final SummaryChannel channel;

    public ScriptedGithubComments(OutboundEgressGuard egressGuard) {
        GitHubGraphQlClientProvider provider = mock(GitHubGraphQlClientProvider.class);
        when(provider.forScope(anyLong())).thenReturn(ScriptedGraphQlClient.of(this::answer));
        channel = new GithubSummaryChannel(provider, new GithubPrNodeIdResolver(provider), egressGuard);
    }

    public SummaryChannel channel() {
        return channel;
    }

    public ScriptedCommentThreads threads() {
        return threads;
    }

    public static String issue(int number) {
        return "I_" + number;
    }

    public static String pullRequest(int number) {
        return "PR_" + number;
    }

    private Mono<ClientGraphQlResponse> answer(Request request) {
        return switch (request.document()) {
            case "GetIssueNodeId" -> threads.resolve(Map.of("repository.issue.id", issue(number(request))));
            case "GetPullRequestNodeId" ->
                threads.resolve(Map.of("repository.pullRequest.id", pullRequest(number(request))));
            case "AddPullRequestComment" ->
                threads.write(
                        request.text("subjectId"),
                        request.text("body"),
                        id -> Map.of("addComment.commentEdge.node.id", id));
            case "GetIssueCommentsNewest" -> comments("repository.issue.comments", issue(number(request)));
            case "GetPullRequestCommentsNewest" ->
                comments("repository.pullRequest.comments", pullRequest(number(request)));
            default -> Mono.error(new IllegalStateException("Unscripted GitHub document " + request.document()));
        };
    }

    private Mono<ClientGraphQlResponse> comments(String path, String subject) {
        return threads.read(subject, comments -> {
            List<GHIssueComment> nodes = comments.stream()
                    .map(comment -> {
                        GHIssueComment node = new GHIssueComment();
                        node.setId(comment.id());
                        node.setBody(comment.body());
                        return node;
                    })
                    .toList();
            GHIssueCommentConnection connection = new GHIssueCommentConnection();
            connection.setNodes(nodes);
            connection.setPageInfo(new GHPageInfo());
            return Map.of(path, connection);
        });
    }

    private static int number(Request request) {
        return Integer.parseInt(request.text("number"));
    }
}
