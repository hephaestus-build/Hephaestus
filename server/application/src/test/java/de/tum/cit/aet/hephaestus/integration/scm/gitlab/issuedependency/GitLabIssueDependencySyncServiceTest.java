package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuedependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The dependency sync iterates the repository's issues and, for each, calls
 * {@code GET /projects/{id}/issues/{iid}/links}. On GitLab, issue IIDs and merge-request IIDs are
 * separate per-project namespaces (issue #5 and MR !5 coexist) but share the single-table
 * {@code issue} table. A polymorphic read would iterate a merge request !9 and use its IID to fetch
 * <em>issue</em> #9's links — attaching them to the merge-request row (cross-contaminating the
 * dependency graph) and spending one rate-limited API call per merge request.
 *
 * <p>Routing the read through the type-scoped {@code IssueRepository.findAllIssuesByRepositoryId}
 * ({@code TYPE(i) = Issue}) keeps merge requests out: a project whose only artifact is a merge request
 * yields an empty read, so the sync completes without ever resolving a token or issuing an HTTP request.
 */
@Tag("unit")
class GitLabIssueDependencySyncServiceTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    private static final long SCOPE_ID = 1L;
    private static final long REPO_ID = 500L;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private GitLabRateLimitTracker rateLimitTracker;

    @Mock
    private WebClient.Builder webClientBuilder;

    @Mock
    private WebClient webClient;

    private GitLabIssueDependencySyncService service;

    @BeforeEach
    void setUp() {
        when(webClientBuilder.build()).thenReturn(webClient);
        service = new GitLabIssueDependencySyncService(
                issueRepository,
                repositoryRepository,
                tokenService,
                rateLimitTracker,
                () -> webClientBuilder,
                projectAdmission);
    }

    @Test
    void shouldNotFetchIssueLinksForACollidingMergeRequest() {
        Repository repository = TestEntities.repository(REPO_ID, "acme/widgets");
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);

        // The project's only artifact is a merge request; the type-scoped read returns no issues.
        PullRequest mergeRequest = TestEntities.pullRequest(9L, 9, "MR 9");
        assertThat(mergeRequest).isInstanceOf(Issue.class); // shares the issue table; must be excluded
        when(issueRepository.findAllIssuesByRepositoryId(REPO_ID)).thenReturn(List.of());

        SyncResult result = service.syncDependenciesForRepository(SCOPE_ID, repository, null);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        // No merge request processed, so the issue-links API call path is never entered.
        verify(tokenService, never()).resolveServerUrl(anyLong());
        verify(tokenService, never()).getAccessToken(anyLong());
        verifyNoInteractions(rateLimitTracker);
        verifyNoInteractions(webClient);
    }
}
