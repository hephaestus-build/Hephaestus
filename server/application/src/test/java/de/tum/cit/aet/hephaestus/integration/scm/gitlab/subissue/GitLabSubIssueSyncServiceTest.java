package de.tum.cit.aet.hephaestus.integration.scm.gitlab.subissue;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * Guards the polymorphic {@code List<Issue>} read hazard in sub-issue sync.
 *
 * <p>On GitLab, issue IIDs and merge-request IIDs are separate per-project namespaces (issue #5 and
 * MR !5 coexist), and both live in the single-table {@code issue} table under the discriminator. The
 * sub-issue sync keys every row by {@code getNumber()} (the IID) into {@code issueByIid}. A polymorphic
 * read makes issue #5 and MR !5 collide in that map (last-writer-wins on arbitrary result order): the
 * WorkItem node for issue #5 can then resolve to the merge-request row, {@code setParentIssue(parent)}
 * writes a bogus {@code parent_issue_id} onto the merge request, the real issue never gets parented,
 * and {@code clearStaleParents} (keyed on the MR's id) clears the real issue's legitimate parent as
 * "stale."
 *
 * <p>The type-scoped read {@code IssueRepository.findAllIssuesByRepositoryId} ({@code TYPE(i) = Issue})
 * keeps merge requests out of the map, so the parent stays on the issue and the MR is untouched.
 */
@Tag("unit")
class GitLabSubIssueSyncServiceTest extends BaseUnitTest {

    private static final long SCOPE_ID = 1L;
    private static final long REPO_ID = 500L;
    private static final int CHILD_IID = 5;
    private static final int PARENT_IID = 10;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    private GitLabSubIssueSyncService service;

    @BeforeEach
    void setUp() {
        lenient()
                .when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
        service = new GitLabSubIssueSyncService(
                issueRepository, repositoryRepository, graphQlClientProvider, responseHandler);
    }

    @Test
    void shouldParentTheIssueAndLeaveACollidingMergeRequestUntouched() {
        Repository repository = TestEntities.repository(REPO_ID, "acme/widgets");

        Issue parentIssue = issue(PARENT_IID * 100L, PARENT_IID);
        Issue childIssue = issue(CHILD_IID * 100L, CHILD_IID);
        childIssue.setParentIssue(parentIssue); // already parented locally; GitLab still reports the same parent
        PullRequest collidingMr = TestEntities.pullRequest(CHILD_IID * 100L + 1, CHILD_IID, "MR " + CHILD_IID);

        // Type-scoped read: the merge request never enters issueByIid.
        when(issueRepository.findAllIssuesByRepositoryId(REPO_ID)).thenReturn(List.of(childIssue, parentIssue));

        // GitLab reports issue #5's parent as issue #10 (unchanged), via the WorkItem hierarchy widget.
        mockWorkItemResponse(workItemNode(CHILD_IID, PARENT_IID, "acme/widgets"));

        SyncResult result = service.syncSubIssuesForRepository(SCOPE_ID, repository);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        // The parent lands (stays) on the ISSUE row...
        assertThat(childIssue.getParentIssue()).isSameAs(parentIssue);
        // ...and the merge request that happens to share IID 5 is never given a parent.
        assertThat(collidingMr.getParentIssue()).isNull();
        // The relationship was unchanged and clearStaleParents cleared nothing, so nothing was written.
        verify(issueRepository, never()).save(any());
        // The colliding merge request is never even looked up as a parent-lookup fallback.
        verify(issueRepository, never()).findByRepositoryIdAndNumber(anyLong(), any(Integer.class));
    }

    private static Issue issue(long id, int number) {
        Issue issue = new Issue();
        issue.setId(id);
        issue.setNumber(number);
        return issue;
    }

    /**
     * A GraphQL WorkItem node for {@code iid} whose HIERARCHY widget points at {@code parentIid}.
     *
     * <p>{@code WorkItem.iid} is {@code String!} in the checked-in schema, so GitLab sends {@code "42"} and never
     * {@code 42}. Stubbing it as a number would exercise only the {@code Number} branch of the production
     * extractor — the branch GitLab can never trigger.
     */
    private static Map<String, Object> workItemNode(int iid, int parentIid, String parentNamespace) {
        Map<String, Object> parent =
                Map.of("iid", String.valueOf(parentIid), "namespace", Map.of("fullPath", parentNamespace));
        Map<String, Object> hierarchyWidget = Map.of("type", "HIERARCHY", "parent", parent);
        return Map.of("iid", String.valueOf(iid), "widgets", List.of(hierarchyWidget));
    }

    @SafeVarargs
    private void mockWorkItemResponse(Map<String, Object>... nodes) {
        List<Map<String, Object>> nodeList = new java.util.ArrayList<>(nodes.length);
        for (Map<String, Object> node : nodes) {
            nodeList.add(node);
        }
        assertVendorCouldReturn(GITLAB, "GetProjectWorkItemHierarchy", "project.workItems.nodes", nodeList);
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        when(graphQlClientProvider.forScope(anyLong())).thenReturn(client);

        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(requestSpec.execute()).thenReturn(Mono.just(response));

        ClientResponseField nodesField = mock(ClientResponseField.class);
        doReturn(nodeList).when(nodesField).toEntityList(Map.class);
        when(response.field("project.workItems.nodes")).thenReturn(nodesField);

        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        doReturn(new GitLabPageInfo(false, null)).when(pageInfoField).toEntity(GitLabPageInfo.class);
        when(response.field("project.workItems.pageInfo")).thenReturn(pageInfoField);
    }
}
