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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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

    private final Repository repository = repositoryWithNativeId();

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

    private static Repository repositoryWithNativeId() {
        Repository repository = TestEntities.repository(REPO_ID, "acme/widgets");
        repository.setNativeId(4242L);
        return repository;
    }

    @Test
    void shouldNotFetchIssueLinksForACollidingMergeRequest() {
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);

        // The project's only artifact is a merge request; the type-scoped read returns no issues.
        PullRequest mergeRequest = TestEntities.pullRequest(9L, 9, "MR 9");
        assertThat(mergeRequest).isInstanceOf(Issue.class); // shares the issue table; must be excluded
        when(issueRepository.findAllIssuesByRepositoryId(REPO_ID)).thenReturn(List.of());

        SyncResult result = service.syncDependenciesForRepository(SCOPE_ID, repository);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        // No merge request processed, so the issue-links API call path is never entered.
        verify(tokenService, never()).resolveServerUrl(anyLong());
        verify(tokenService, never()).getAccessToken(anyLong());
        verifyNoInteractions(rateLimitTracker);
        verifyNoInteractions(webClient);
    }

    @Test
    void shouldReadEveryIssueWhenAProjectHasMoreIssuesThanOneRunUsedToRead() {
        List<Issue> issues = issues(250);
        AtomicInteger reads = new AtomicInteger();
        GitLabIssueDependencySyncService reading = serviceAnswering(issues, request -> {
            reads.incrementAndGet();
            return links("[]");
        });

        SyncResult result = reading.syncDependenciesForRepository(SCOPE_ID, repository);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(reads).hasValue(250);
    }

    @Test
    void shouldReportWarningsWhenSomeIssueLinksCannotBeRead() {
        List<Issue> issues = issues(3);
        GitLabIssueDependencySyncService reading = serviceAnswering(
                issues,
                request -> request.url().getPath().endsWith("/issues/2/links")
                        ? ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                                .build()
                        : links("[]"));

        SyncResult result = reading.syncDependenciesForRepository(SCOPE_ID, repository);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED_WITH_WARNINGS);
    }

    private List<Issue> issues(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number -> {
                    Issue issue = new Issue();
                    issue.setId(1000L + number);
                    issue.setNumber(number);
                    issue.setRepository(repository);
                    return issue;
                })
                .toList();
    }

    private GitLabIssueDependencySyncService serviceAnswering(
            List<Issue> issues, Function<ClientRequest, ClientResponse> answer) {
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);
        when(issueRepository.findAllIssuesByRepositoryId(REPO_ID)).thenReturn(issues);
        when(issueRepository.findByIdWithBlockedBy(anyLong())).thenReturn(Optional.empty());
        when(tokenService.resolveServerUrl(SCOPE_ID)).thenReturn("https://gitlab.example");
        when(tokenService.getAccessToken(SCOPE_ID)).thenReturn("token");
        return new GitLabIssueDependencySyncService(
                issueRepository,
                repositoryRepository,
                tokenService,
                rateLimitTracker,
                () -> WebClient.builder().exchangeFunction(request -> Mono.just(answer.apply(request))),
                projectAdmission);
    }

    private static ClientResponse links(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
