package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedCommentThreads;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGraphQlClient.Request;
import java.util.Map;
import org.springframework.graphql.client.ClientGraphQlResponse;
import reactor.core.publisher.Mono;

/** The real {@link GitHubSummaryChannel} over a GitHub whose issue and pull request comments the test controls. */
public final class ScriptedGitHubComments {

    private final ScriptedCommentThreads threads = new ScriptedCommentThreads("IC_");
    private final SummaryChannel channel;

    public ScriptedGitHubComments(OutboundEgressGuard egressGuard) {
        GitHubGraphQlClientProvider provider = mock(GitHubGraphQlClientProvider.class);
        when(provider.forScope(anyLong())).thenReturn(ScriptedGraphQlClient.of(this::answer));
        channel = new GitHubSummaryChannel(provider, new GitHubPrNodeIdResolver(provider), egressGuard);
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

    /** Every scripted comment was written through the channel, so the viewer wrote it. */
    private Mono<ClientGraphQlResponse> comments(String path, String subject) {
        return threads.read(
                subject,
                comments -> Map.of(
                        path,
                        Map.of(
                                "nodes",
                                comments.stream()
                                        .map(comment -> Map.of(
                                                "id", comment.id(), "body", comment.body(), "viewerDidAuthor", true))
                                        .toList(),
                                "pageInfo",
                                Map.of("hasPreviousPage", false))));
    }

    private static int number(Request request) {
        return Integer.parseInt(request.text("number"));
    }
}
