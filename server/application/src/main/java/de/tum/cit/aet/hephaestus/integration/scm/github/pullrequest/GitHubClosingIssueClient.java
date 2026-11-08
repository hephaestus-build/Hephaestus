package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncConstants.MAX_PAGINATION_PAGES;

import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper.PaginationRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Reads the closing references beyond a pull request's embedded first page. */
@Service
@RequiredArgsConstructor
public class GitHubClosingIssueClient {

    private final GitHubGraphQlClientProvider clients;
    private final GitHubSyncProperties properties;
    private final GraphQlPaginationHelper pagination;

    /**
     * Completes the embedded list before the caller opens its write transaction. A failed read
     * leaves the page checkpoint unchanged: advancing it would skip references on the next sync.
     */
    public void completeReferences(Long scopeId, @Nullable GHPullRequest pullRequest) {
        if (pullRequest == null || pullRequest.getClosingIssuesReferences() == null) return;
        GHIssueConnection first = pullRequest.getClosingIssuesReferences();
        var info = first.getPageInfo();
        if (first.getNodes() == null || info == null) {
            throw new IllegalStateException("Closing issue references are incomplete");
        }
        if (!info.getHasNextPage()) return;
        String cursor = info.getEndCursor();
        if (cursor == null) {
            throw new IllegalStateException("Closing issue reference cursor is missing");
        }

        List<GHIssue> issues = new ArrayList<>(first.getNodes());
        Set<String> cursors = new HashSet<>();
        cursors.add(cursor);
        var result = pagination.paginate(PaginationRequest.<GHIssueConnection>builder()
                .client(clients.forScope(scopeId))
                .scopeId(scopeId)
                .documentName("GetPullRequestClosingIssues")
                .variables(Map.of("id", pullRequest.getId()))
                .initialCursor(cursor)
                .maxPages(MAX_PAGINATION_PAGES - 1)
                .timeout(properties.extendedGraphqlTimeout())
                .connectionFieldPath("node.closingIssuesReferences")
                .connectionType(GHIssueConnection.class)
                .pageInfoExtractor(GHIssueConnection::getPageInfo)
                .pageProcessorWhile(page -> {
                    var pageInfo = page.getPageInfo();
                    if (page.getNodes() == null || pageInfo == null) return false;
                    String next = pageInfo.getEndCursor();
                    if (pageInfo.getHasNextPage() && (next == null || !cursors.add(next))) return false;
                    issues.addAll(page.getNodes());
                    return true;
                })
                .contextDescription("pull request closing references")
                .build());
        if (!result.isComplete()) {
            throw new IllegalStateException(
                    "Closing issue reference pagination stopped before the end: " + result.terminationReason());
        }
        first.setNodes(issues);
        info.setHasNextPage(false);
    }
}
