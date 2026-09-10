package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitLabCommitMergeRequestLinkerTest extends BaseUnitTest {

    private static final Long SCOPE_ID = 7L;
    private static final Long REPO_ID = 42L;
    private static final String PROJECT_PATH = "org/proj";
    private static final OffsetDateTime UPDATED_AFTER = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitContributorRepository commitContributorRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final GitLabProperties gitLabProperties = new GitLabProperties(
            "https://gitlab.com",
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofMillis(1), // fast throttle for tests
            Duration.ofMinutes(5));

    private GitLabCommitMergeRequestLinker linker;
    private Repository repository;

    @BeforeEach
    void setUp() {
        lenient()
                .when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
        lenient().when(graphQlClientProvider.getRateLimitRemaining(anyLong())).thenReturn(100);

        linker = new GitLabCommitMergeRequestLinker(
                commitRepository,
                commitContributorRepository,
                userRepository,
                graphQlClientProvider,
                responseHandler,
                gitLabProperties,
                eventPublisher);

        repository = new Repository();
        repository.setId(REPO_ID);
        repository.setNameWithOwner(PROJECT_PATH);
        IdentityProvider provider = new IdentityProvider();
        provider.setId(2L);
        provider.setType(IdentityProviderType.GITLAB);
        repository.setProvider(provider);
    }

    @Test
    void singlePage_linksCommitsToMergeRequests() {
        ClientGraphQlResponse page = mockMrsPage(
                List.of(
                        mrNode(101, List.of("sha-a1", "sha-a2"), false, null),
                        mrNode(102, List.of("sha-b1"), false, null)),
                new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(101), eq(List.of("sha-a1", "sha-a2"))))
                .thenReturn(2);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(102), eq(List.of("sha-b1"))))
                .thenReturn(1);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isEqualTo(3);
        verify(commitRepository).linkPullRequestToCommits(REPO_ID, 101, List.of("sha-a1", "sha-a2"));
        verify(commitRepository).linkPullRequestToCommits(REPO_ID, 102, List.of("sha-b1"));
        verify(graphQlClientProvider).acquirePermission();
        verify(graphQlClientProvider).recordSuccess();
    }

    @Test
    void mrWithNoCommits_isSkipped() {
        ClientGraphQlResponse page =
                mockMrsPage(List.of(mrNode(55, List.of(), false, null)), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        verify(commitRepository, never()).linkPullRequestToCommits(anyLong(), anyInt(), any());
    }

    @Test
    void multiPagePagination_visitsAllPages() {
        ClientGraphQlResponse page1 =
                mockMrsPage(List.of(mrNode(1, List.of("sha-1"), false, null)), new GitLabPageInfo(true, "cursor-1"));
        ClientGraphQlResponse page2 =
                mockMrsPage(List.of(mrNode(2, List.of("sha-2"), false, null)), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page1, page2);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), anyInt(), any()))
                .thenReturn(1);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isEqualTo(2);
        verify(commitRepository).linkPullRequestToCommits(REPO_ID, 1, List.of("sha-1"));
        verify(commitRepository).linkPullRequestToCommits(REPO_ID, 2, List.of("sha-2"));
        verify(graphQlClientProvider, atLeast(2)).acquirePermission();
    }

    @Test
    void abortAction_recordsFailureAndAbortsWithError() {
        ClientGraphQlResponse invalid = mock(ClientGraphQlResponse.class);
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, invalid);
        when(responseHandler.handle(eq(invalid), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_ERROR);
        assertThat(result.count()).isZero();
        verify(graphQlClientProvider).recordFailure(any());
        verify(commitRepository, never()).linkPullRequestToCommits(anyLong(), anyInt(), any());
    }

    @Test
    void retryAction_repeatsRequestOnSameCursor() {
        ClientGraphQlResponse transientFailure = mock(ClientGraphQlResponse.class);
        ClientGraphQlResponse good =
                mockMrsPage(List.of(mrNode(7, List.of("sha-7"), false, null)), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, transientFailure, good);
        // First call -> RETRY, second call -> CONTINUE
        when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(
                        new HandleResult(HandleResult.Action.RETRY, null),
                        new HandleResult(HandleResult.Action.CONTINUE, null));
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(7), eq(List.of("sha-7"))))
                .thenReturn(1);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isEqualTo(1);
        verify(graphQlClientProvider, atLeast(2)).acquirePermission();
        // recordSuccess only after the CONTINUE response, not after RETRY
        verify(graphQlClientProvider).recordSuccess();
    }

    @Test
    void paginationLoop_abortsWithError() {
        ClientGraphQlResponse page1 = mockMrsPage(
                List.of(mrNode(1, List.of("sha-1"), false, null)), new GitLabPageInfo(true, "stuck-cursor"));
        ClientGraphQlResponse page2 = mockMrsPage(
                List.of(mrNode(2, List.of("sha-2"), false, null)), new GitLabPageInfo(true, "stuck-cursor"));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page1, page2);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), anyInt(), any()))
                .thenReturn(1);
        // Loop detector fires on second page (same cursor returned).
        // Lenient because strict stubbing otherwise throws PotentialStubbingProblem
        // on iteration 1 where previousCursor is null (non-matching args).
        lenient()
                .when(responseHandler.isPaginationLoop(eq("stuck-cursor"), eq("stuck-cursor"), anyString(), any()))
                .thenReturn(true);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_ERROR);
        // Both pages' MRs were persisted before the loop was detected on the second pageInfo.
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void mrWithMoreCommits_followUpPaginationMerges() {
        ClientGraphQlResponse outerPage = mockMrsPage(
                List.of(mrNode(9, List.of("head-1", "head-2"), true, "nested-cursor-1")),
                new GitLabPageInfo(false, null));
        ClientGraphQlResponse nestedPage =
                mockNestedMrCommitsPage(List.of("tail-1", "tail-2"), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, outerPage, nestedPage);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(9), any()))
                .thenReturn(4);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isEqualTo(4);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> shasCaptor = ArgumentCaptor.forClass(List.class);
        verify(commitRepository).linkPullRequestToCommits(eq(REPO_ID), eq(9), shasCaptor.capture());
        assertThat(shasCaptor.getValue()).containsExactly("head-1", "head-2", "tail-1", "tail-2");
    }

    @Test
    void nestedPaginationAbort_skipsMrWithoutPartialLinks() {
        ClientGraphQlResponse outerPage = mockMrsPage(
                List.of(mrNode(11, List.of("head-1"), true, "nested-cursor-1")), new GitLabPageInfo(false, null));
        ClientGraphQlResponse nestedInvalid = mock(ClientGraphQlResponse.class);
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, outerPage, nestedInvalid);
        // Outer page handled as CONTINUE; nested page handled as ABORT.
        when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(
                        new HandleResult(HandleResult.Action.CONTINUE, null),
                        new HandleResult(HandleResult.Action.ABORT, null));

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        // Outer sync itself completed; just the one MR was skipped.
        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        verify(commitRepository, never()).linkPullRequestToCommits(anyLong(), anyInt(), any());
    }

    @Test
    void unexpectedException_recordsFailureAndAbortsWithError() {
        HttpGraphQlClient client = mockClient();
        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        when(requestSpec.execute()).thenThrow(new RuntimeException("boom"));

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_ERROR);
        assertThat(result.count()).isZero();
        verify(graphQlClientProvider).recordFailure(any());
    }

    @Test
    void harvestedAuthorPairs_backfillCommitContributorUserId() {
        Map<String, Object> commit1 = commitNode("sha-1", "go35kin@mytum.de", "00000000014C41E0");
        Map<String, Object> commit2 = commitNode("sha-2", "go35kin@tum.de", null);
        Map<String, Object> mr = mrNodeWithCommits(42, List.of(commit1, commit2));

        ClientGraphQlResponse page = mockMrsPage(List.of(mr), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(42), any()))
                .thenReturn(2);

        User user = new User();
        user.setId(901L);
        when(userRepository.findByLoginAndProviderId(eq("00000000014C41E0"), eq(2L)))
                .thenReturn(Optional.of(user));
        when(commitContributorRepository.backfillUserIdByEmail(anyString(), eq(901L)))
                .thenReturn(1);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        // Direct pair (sha-1) plus dominant-login fallback (sha-2) reach the repository.
        verify(commitContributorRepository).backfillUserIdByEmail(eq("go35kin@mytum.de"), eq(901L));
        verify(commitContributorRepository).backfillUserIdByEmail(eq("go35kin@tum.de"), eq(901L));
    }

    @Test
    void shouldFallBackToMergeRequestCommitsWhenLinkRowsEmpty() {
        Map<String, Object> mergedMr = mrNode(500, List.of(), false, null);
        mergedMr.put("state", "merged");
        ClientGraphQlResponse outerPage = mockMrsPage(List.of(mergedMr), new GitLabPageInfo(false, null));
        ClientGraphQlResponse fallbackPage =
                mockFallbackMrCommitsPage(List.of("merge-commit-1", "merge-commit-2"), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, outerPage, fallbackPage);
        // Primary link path is skipped when shas is empty; only the fallback call fires.
        when(commitRepository.linkPullRequestToCommits(
                        eq(REPO_ID), eq(500), eq(List.of("merge-commit-1", "merge-commit-2"))))
                .thenReturn(2);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isEqualTo(2);
        verify(commitRepository).linkPullRequestToCommits(REPO_ID, 500, List.of("merge-commit-1", "merge-commit-2"));
    }

    @Test
    void shouldSkipMissingCommitShasWhenNotInGitCommit() {
        Map<String, Object> closedMr = mrNode(501, List.of(), false, null);
        closedMr.put("state", "closed");
        ClientGraphQlResponse outerPage = mockMrsPage(List.of(closedMr), new GitLabPageInfo(false, null));
        ClientGraphQlResponse fallbackPage =
                mockFallbackMrCommitsPage(List.of("unknown-sha-1"), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, outerPage, fallbackPage);
        // Primary returns 0 (empty shas); fallback also returns 0 (SHAs not yet in git_commit).
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(501), any()))
                .thenReturn(0);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        // Loop completes without error; caller logs debug but does not abort.
        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        verify(commitRepository).linkPullRequestToCommits(REPO_ID, 501, List.of("unknown-sha-1"));
    }

    @Test
    void shouldNotInvokeFallbackWhenMrIsOpen() {
        Map<String, Object> openMr = mrNode(502, List.of(), false, null);
        openMr.put("state", "opened");
        ClientGraphQlResponse outerPage = mockMrsPage(List.of(openMr), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, outerPage);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        verify(commitRepository, never()).linkPullRequestToCommits(anyLong(), anyInt(), any());
    }

    @Test
    void shouldPublishReconciledEventWhenCommitAuthorsUpdated() {
        Map<String, Object> commit = commitNode("sha-1", "dev@example.com", "devlogin");
        Map<String, Object> mr = mrNodeWithCommits(42, List.of(commit));

        ClientGraphQlResponse page = mockMrsPage(List.of(mr), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(42), any()))
                .thenReturn(1);

        User user = new User();
        user.setId(901L);
        when(userRepository.findByLoginAndProviderId(eq("devlogin"), eq(2L))).thenReturn(Optional.of(user));
        when(commitContributorRepository.backfillUserIdByEmail(anyString(), eq(901L)))
                .thenReturn(1);
        when(commitRepository.bulkUpdateAuthorIdByEmail(eq("dev@example.com"), eq(REPO_ID), eq(901L)))
                .thenReturn(1);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);

        ArgumentCaptor<ScmDomainEvent.CommitAuthorsReconciled> captor =
                ArgumentCaptor.forClass(ScmDomainEvent.CommitAuthorsReconciled.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ScmDomainEvent.CommitAuthorsReconciled event = captor.getValue();
        assertThat(event.repositoryId()).isEqualTo(REPO_ID);
        assertThat(event.context().scopeId()).isEqualTo(SCOPE_ID);
        assertThat(event.context().providerType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(event.context().repository()).isNotNull();
        assertThat(event.context().repository().id()).isEqualTo(REPO_ID);
        assertThat(event.context().repository().nameWithOwner()).isEqualTo(PROJECT_PATH);
    }

    @Test
    void shouldNotPublishWhenNoCommitAuthorsUpdated() {
        Map<String, Object> commit = commitNode("sha-1", "dev@example.com", "devlogin");
        Map<String, Object> mr = mrNodeWithCommits(42, List.of(commit));

        ClientGraphQlResponse page = mockMrsPage(List.of(mr), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        mockSequentialExecute(client, page);
        when(commitRepository.linkPullRequestToCommits(eq(REPO_ID), eq(42), any()))
                .thenReturn(1);

        User user = new User();
        user.setId(901L);
        when(userRepository.findByLoginAndProviderId(eq("devlogin"), eq(2L))).thenReturn(Optional.of(user));
        // Contributor row is backfilled but no commit_author row is actually updated
        // (e.g. git_commit.author_id was already populated). Event must NOT be published.
        when(commitContributorRepository.backfillUserIdByEmail(anyString(), eq(901L)))
                .thenReturn(1);
        when(commitRepository.bulkUpdateAuthorIdByEmail(anyString(), anyLong(), anyLong()))
                .thenReturn(0);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, UPDATED_AFTER);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.CommitAuthorsReconciled.class));
    }

    @Test
    void nullUpdatedAfter_passesNullVariable() {
        ClientGraphQlResponse page = mockMrsPage(List.of(), new GitLabPageInfo(false, null));
        HttpGraphQlClient client = mockClient();
        HttpGraphQlClient.RequestSpec requestSpec = mockSequentialExecute(client, page);

        SyncResult result = linker.linkCommits(SCOPE_ID, repository, null);

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        verify(requestSpec).variable(eq("updatedAfter"), eq(null));
    }

    // Helpers

    private HttpGraphQlClient mockClient() {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        when(graphQlClientProvider.forScope(any())).thenReturn(client);
        return client;
    }

    private HttpGraphQlClient.RequestSpec mockSequentialExecute(
            HttpGraphQlClient client, ClientGraphQlResponse first, ClientGraphQlResponse... rest) {
        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

        var stubbing = when(requestSpec.execute()).thenReturn(Mono.just(first));
        for (ClientGraphQlResponse response : rest) {
            stubbing = stubbing.thenReturn(Mono.just(response));
        }
        return requestSpec;
    }

    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockMrsPage(List<Map<String, Object>> nodes, @Nullable GitLabPageInfo pageInfo) {
        assertVendorCouldReturn(GITLAB, "LinkCommitsToMergeRequests", "project.mergeRequests.nodes", nodes);
        ClientGraphQlResponse resp = mock(ClientGraphQlResponse.class);
        lenient().when(resp.isValid()).thenReturn(true);

        ClientResponseField nodesField = mock(ClientResponseField.class);
        lenient().doReturn(nodes).when(nodesField).toEntityList(Map.class);
        lenient().when(resp.field("project.mergeRequests.nodes")).thenReturn(nodesField);

        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        lenient().when(pageInfoField.<GitLabPageInfo>toEntity(any(Class.class))).thenReturn(pageInfo);
        lenient().when(resp.field("project.mergeRequests.pageInfo")).thenReturn(pageInfoField);

        return resp;
    }

    /**
     * Mocks the fallback {@code GetMergeRequestAllCommits} response shape:
     * {@code project.mergeRequests.nodes[0].commits.{nodes,pageInfo}}.
     */
    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockFallbackMrCommitsPage(List<String> shas, GitLabPageInfo pageInfo) {
        ClientGraphQlResponse resp = mock(ClientGraphQlResponse.class);
        lenient().when(resp.isValid()).thenReturn(true);

        Map<String, Object> pageInfoMap = new LinkedHashMap<>();
        pageInfoMap.put("hasNextPage", pageInfo.hasNextPage());
        pageInfoMap.put("endCursor", pageInfo.endCursor());

        Map<String, Object> commitsMap = new LinkedHashMap<>();
        commitsMap.put(
                "nodes",
                shas.stream().map(sha -> Map.<String, Object>of("sha", sha)).toList());
        commitsMap.put("pageInfo", pageInfoMap);

        Map<String, Object> mrNode = new LinkedHashMap<>();
        mrNode.put("commits", commitsMap);
        assertVendorCouldReturn(GITLAB, "GetMergeRequestAllCommits", "project.mergeRequests.nodes", List.of(mrNode));

        ClientResponseField nodesField = mock(ClientResponseField.class);
        org.mockito.Mockito.doReturn(List.of(mrNode)).when(nodesField).toEntityList(Map.class);
        when(resp.field("project.mergeRequests.nodes")).thenReturn(nodesField);

        return resp;
    }

    /**
     * Mocks the follow-up {@code GetMergeRequestCommits} response shape:
     * {@code project.mergeRequests.nodes[0].commitsWithoutMergeCommits.{nodes,pageInfo}}.
     */
    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockNestedMrCommitsPage(List<String> shas, GitLabPageInfo pageInfo) {
        ClientGraphQlResponse resp = mock(ClientGraphQlResponse.class);
        lenient().when(resp.isValid()).thenReturn(true);

        Map<String, Object> pageInfoMap = new LinkedHashMap<>();
        pageInfoMap.put("hasNextPage", pageInfo.hasNextPage());
        pageInfoMap.put("endCursor", pageInfo.endCursor());

        Map<String, Object> commitsMap = new LinkedHashMap<>();
        commitsMap.put(
                "nodes",
                shas.stream().map(sha -> Map.<String, Object>of("sha", sha)).toList());
        commitsMap.put("pageInfo", pageInfoMap);

        Map<String, Object> mrNode = new LinkedHashMap<>();
        mrNode.put("commitsWithoutMergeCommits", commitsMap);
        assertVendorCouldReturn(GITLAB, "GetMergeRequestCommits", "project.mergeRequests.nodes", List.of(mrNode));

        ClientResponseField nodesField = mock(ClientResponseField.class);
        org.mockito.Mockito.doReturn(List.of(mrNode)).when(nodesField).toEntityList(Map.class);
        when(resp.field("project.mergeRequests.nodes")).thenReturn(nodesField);

        return resp;
    }

    /**
     * Builds the map shape that the linker reads from {@code project.mergeRequests.nodes[*]}.
     *
     * <p>{@code MergeRequest.iid} is {@code String!} in the checked-in schema, so GitLab sends {@code "42"} and
     * never {@code 42}. Stubbing it as a number would exercise only the {@code Number} branch of the production
     * extractor — the branch GitLab can never trigger.
     */
    private static Map<String, Object> mrNode(
            int iid, List<String> shas, boolean nestedHasNextPage, @Nullable String nestedEndCursor) {
        Map<String, Object> pageInfoMap = new LinkedHashMap<>();
        pageInfoMap.put("hasNextPage", nestedHasNextPage);
        pageInfoMap.put("endCursor", nestedEndCursor);

        Map<String, Object> commitsMap = new LinkedHashMap<>();
        commitsMap.put(
                "nodes",
                shas.stream().map(sha -> Map.<String, Object>of("sha", sha)).toList());
        commitsMap.put("pageInfo", pageInfoMap);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("iid", String.valueOf(iid));
        node.put("commitsWithoutMergeCommits", commitsMap);
        return node;
    }

    /**
     * Builds an MR node with fully populated commit fields (sha + author fields)
     * so that the author-attribution harvest can be exercised.
     */
    private static Map<String, Object> mrNodeWithCommits(int iid, List<Map<String, Object>> commitNodes) {
        Map<String, Object> pageInfoMap = new LinkedHashMap<>();
        pageInfoMap.put("hasNextPage", false);
        pageInfoMap.put("endCursor", null);

        Map<String, Object> commitsMap = new LinkedHashMap<>();
        commitsMap.put("nodes", commitNodes);
        commitsMap.put("pageInfo", pageInfoMap);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("iid", String.valueOf(iid));
        node.put("commitsWithoutMergeCommits", commitsMap);
        return node;
    }

    private static Map<String, Object> commitNode(String sha, String authorEmail, @Nullable String authorUsername) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("sha", sha);
        node.put("authorEmail", authorEmail);
        if (authorUsername != null) {
            Map<String, Object> author = new LinkedHashMap<>();
            author.put("id", "gid://gitlab/User/1");
            author.put("username", authorUsername);
            node.put("author", author);
        } else {
            node.put("author", null);
        }
        return node;
    }
}
