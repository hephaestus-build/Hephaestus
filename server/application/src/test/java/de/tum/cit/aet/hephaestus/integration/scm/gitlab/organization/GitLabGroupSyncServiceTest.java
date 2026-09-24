package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitLabGroupSyncServiceTest extends BaseUnitTest {

    private static final Long TEST_PROVIDER_ID = 100L;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private GitLabGroupProcessor groupProcessor;

    @Mock
    private GitLabProjectProcessor projectProcessor;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    private final GitLabProperties gitLabProperties = new GitLabProperties(
            "https://gitlab.com",
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofMillis(10), // fast throttle for tests
            Duration.ofMinutes(5));

    private GitLabGroupSyncService service;

    @BeforeEach
    void setUp() {
        IdentityProvider gitLabProvider = TestEntities.gitProvider(TEST_PROVIDER_ID, IdentityProviderType.GITLAB);
        lenient()
                .when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com"))
                .thenReturn(Optional.of(gitLabProvider));

        // Default: responseHandler.handle() returns CONTINUE (valid response)
        lenient()
                .when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));

        service = new GitLabGroupSyncService(
                graphQlClientProvider,
                responseHandler,
                groupProcessor,
                projectProcessor,
                gitLabProperties,
                gitProviderRepository);
    }

    @Nested
    class SyncGroup {

        @Test
        void nullGroupPath_returnsEmpty() {
            Optional<Organization> result = service.syncGroup(1L, null, null);
            assertThat(result).isEmpty();
            verify(graphQlClientProvider, never()).acquirePermission();
        }

        @Test
        void blankGroupPath_returnsEmpty() {
            Optional<Organization> result = service.syncGroup(1L, "   ", null);
            assertThat(result).isEmpty();
            verify(graphQlClientProvider, never()).acquirePermission();
        }

        @Test
        void successfulSync_returnsOrganization() {
            Organization org = new Organization();
            org.setId(42L);
            org.setLogin("my-org");

            mockGraphQlGroupResponse(new GitLabGroupResponse(
                    "gid://gitlab/Group/42",
                    "my-org",
                    "My Org",
                    null,
                    "https://gitlab.com/my-org",
                    null,
                    "public",
                    null));
            when(groupProcessor.process(any(GitLabGroupResponse.class), anyLong()))
                    .thenReturn(org);

            Optional<Organization> result = service.syncGroup(1L, "my-org", null);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(42L);
            verify(graphQlClientProvider).acquirePermission();
            verify(graphQlClientProvider).recordSuccess();
        }

        @Test
        void groupNotFound_returnsEmpty() {
            mockGraphQlGroupResponse(null);

            Optional<Organization> result = service.syncGroup(1L, "non-existent", null);

            assertThat(result).isEmpty();
        }

        @Test
        void invalidResponse_returnsEmptyAndRecordsFailure() {
            HttpGraphQlClient client = mockClient();
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            lenient().when(response.isValid()).thenReturn(false);
            lenient().when(response.getErrors()).thenReturn(List.of());

            // Invalid response → handler returns ABORT
            when(responseHandler.handle(eq(response), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

            mockRequestSpec(client, response);

            Optional<Organization> result = service.syncGroup(1L, "my-org", null);

            assertThat(result).isEmpty();
            verify(graphQlClientProvider).recordFailure(any());
        }

        @Test
        void exception_returnsEmptyAndRecordsFailure() {
            when(graphQlClientProvider.forScope(1L)).thenThrow(new RuntimeException("connection error"));

            Optional<Organization> result = service.syncGroup(1L, "my-org", null);

            assertThat(result).isEmpty();
            verify(graphQlClientProvider).recordFailure(any());
        }
    }

    @Nested
    class SyncGroupProjects {

        private Organization org;

        @BeforeEach
        void setUpOrg() {
            org = new Organization();
            org.setId(1L);
        }

        @Test
        void nullGroupPath_returnsAborted() {
            GitLabSyncResult result = service.syncGroupProjects(1L, null, null);
            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.ABORTED_ERROR);
            assertThat(result.synced()).isEmpty();
            verify(graphQlClientProvider, never()).acquirePermission();
        }

        @Test
        void blankGroupPath_returnsAborted() {
            GitLabSyncResult result = service.syncGroupProjects(1L, "   ", null);
            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.ABORTED_ERROR);
            assertThat(result.synced()).isEmpty();
            verify(graphQlClientProvider, never()).acquirePermission();
        }

        @Test
        void nullGroupOnFirstPage_abortsSyncWithEmptyList() {
            ClientGraphQlResponse resp = mock(ClientGraphQlResponse.class);
            when(resp.isValid()).thenReturn(true);

            ClientResponseField groupField = mock(ClientResponseField.class);
            when(groupField.toEntity(GitLabGroupResponse.class)).thenReturn(DEFAULT_GROUP);
            when(resp.field("group")).thenReturn(groupField);

            HttpGraphQlClient client = mockClient();
            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(resp));

            when(groupProcessor.process(any(), anyLong())).thenReturn(null);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.ABORTED_ERROR);
            assertThat(result.synced()).isEmpty();
            verify(projectProcessor, never()).processGraphQlResponse(any(), any(), any());
        }

        @Test
        void emptyGroup_returnsCompleted() {
            ClientGraphQlResponse projectsResp = mockProjectsPageWithGroup(List.of(), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, projectsResp);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).isEmpty();
        }

        @Test
        void singlePage_returnsAllProjects() {
            var proj1 = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var proj2 = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            ClientGraphQlResponse projectsResp = mockProjectsPageWithGroup(List.of(proj1, proj2), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, projectsResp);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repo1 = createTestRepository(10L);
            Repository repo2 = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(proj1), any(), any()))
                    .thenReturn(repo1);
            when(projectProcessor.processGraphQlResponse(eq(proj2), any(), any()))
                    .thenReturn(repo2);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(2);
            assertThat(result.synced()).extracting(Repository::getId).containsExactly(10L, 20L);
            assertThat(result.projectsSkipped()).isZero();
            assertThat(result.projectsRedacted()).isZero();
            assertThat(result.projectsReconciled()).isZero();
        }

        @Test
        void multiPage_fetchesAllPages() {
            var proj1 = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var proj2 = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            ClientGraphQlResponse page1 =
                    mockProjectsPageWithGroup(List.of(proj1), new GitLabPageInfo(true, "cursor1"));
            ClientGraphQlResponse page2 = mockProjectsPage(List.of(proj2), new GitLabPageInfo(false, null));

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, page1, page2);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repo1 = createTestRepository(10L);
            Repository repo2 = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(proj1), any(), any()))
                    .thenReturn(repo1);
            when(projectProcessor.processGraphQlResponse(eq(proj2), any(), any()))
                    .thenReturn(repo2);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(2);
            assertThat(result.pagesCompleted()).isEqualTo(2);
        }

        @Test
        void nullProcessorResult_countedAsSkipped() {
            var proj1 = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var proj2 = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            ClientGraphQlResponse projectsResp = mockProjectsPageWithGroup(List.of(proj1, proj2), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, projectsResp);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repo1 = createTestRepository(10L);
            when(projectProcessor.processGraphQlResponse(eq(proj1), any(), any()))
                    .thenReturn(repo1);
            when(projectProcessor.processGraphQlResponse(eq(proj2), any(), any()))
                    .thenReturn(null);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED_WITH_ERRORS);
            assertThat(result.synced()).hasSize(1);
            assertThat(result.projectsSkipped()).isEqualTo(1);
        }

        @Test
        void processorException_countedAsSkipped() {
            var proj1 = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var proj2 = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            ClientGraphQlResponse projectsResp = mockProjectsPageWithGroup(List.of(proj1, proj2), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, projectsResp);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repo2 = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(proj1), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));
            when(projectProcessor.processGraphQlResponse(eq(proj2), any(), any()))
                    .thenReturn(repo2);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED_WITH_ERRORS);
            assertThat(result.synced()).hasSize(1);
            assertThat(result.synced().get(0).getId()).isEqualTo(20L);
            assertThat(result.projectsSkipped()).isEqualTo(1);
        }

        @Test
        void nullNodes_countedAsRedacted_producesErrors() {
            var proj1 = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");

            // Simulate GitLab returning [proj1, null, null] — 2 null nodes due to access restrictions
            List<GitLabProjectResponse> nodesWithNulls = new ArrayList<>();
            nodesWithNulls.add(proj1);
            nodesWithNulls.add(null);
            nodesWithNulls.add(null);

            ClientGraphQlResponse projectsResp = mockProjectsPageWithGroup(nodesWithNulls, null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, projectsResp);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repo1 = createTestRepository(10L);
            when(projectProcessor.processGraphQlResponse(eq(proj1), any(), any()))
                    .thenReturn(repo1);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            // Redacted nodes indicate data loss and should produce COMPLETED_WITH_ERRORS
            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED_WITH_ERRORS);
            assertThat(result.synced()).hasSize(1);
            assertThat(result.projectsSkipped()).isZero();
            assertThat(result.projectsRedacted()).isEqualTo(2);
        }

        @Test
        void subgroupProject_usesOwnGroup() {
            var subGroupResponse = new GitLabGroupResponse(
                    "gid://gitlab/Group/99",
                    "my-org/sub-team",
                    "Sub Team",
                    null,
                    "https://gitlab.com/my-org/sub-team",
                    null,
                    "public",
                    null);

            // Project has its own group (from subgroup)
            var proj1 = new GitLabProjectResponse(
                    "gid://gitlab/Project/10",
                    "my-org/sub-team/proj-a",
                    "proj-a",
                    "https://gitlab.com/my-org/sub-team/proj-a",
                    null,
                    "public",
                    false,
                    null,
                    null,
                    subGroupResponse, // subgroup
                    null);

            ClientGraphQlResponse projectsResp = mockProjectsPageWithGroup(List.of(proj1), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, projectsResp);

            Organization subOrg = new Organization();
            subOrg.setId(99L);
            subOrg.setLogin("my-org/sub-team");

            // First call: top-level group; subsequent calls: subgroup
            when(groupProcessor.process(eq(DEFAULT_GROUP), anyLong())).thenReturn(org);
            when(groupProcessor.process(eq(subGroupResponse), anyLong())).thenReturn(subOrg);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repo1 = createTestRepository(10L);
            when(projectProcessor.processGraphQlResponse(eq(proj1), eq(subOrg), any()))
                    .thenReturn(repo1);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(1);
            // Verify processor was called with the subgroup org, not the top-level org
            verify(projectProcessor).processGraphQlResponse(eq(proj1), eq(subOrg), any());
        }

        @Test
        void invalidFirstPage_returnsEmptyList() {
            HttpGraphQlClient client = mockClient();
            ClientGraphQlResponse invalidResp = mock(ClientGraphQlResponse.class);
            lenient().when(invalidResp.isValid()).thenReturn(false);
            lenient().when(invalidResp.getErrors()).thenReturn(List.of());

            // Invalid response → handler returns ABORT
            when(responseHandler.handle(eq(invalidResp), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(invalidResp));

            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.synced()).isEmpty();
            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.ABORTED_ERROR);
        }

        // Reconciliation Tests (GitLab #33419 workaround)

        @Test
        void reconciliation_recoversDroppedDirectProjects() {
            // Subgroup query returns only proj-a (simulates bug dropping proj-b)
            var projA = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var projB = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            // Phase 1: includeSubgroups=true — returns only projA (bug drops projB)
            ClientGraphQlResponse mainPage = mockProjectsPageWithGroup(List.of(projA), null);
            // Phase 2: includeSubgroups=false — returns both projA and projB
            ClientGraphQlResponse reconPage = mockProjectsPage(List.of(projA, projB), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, mainPage, reconPage);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repoA = createTestRepository(10L);
            Repository repoB = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(projA), any(), any()))
                    .thenReturn(repoA);
            when(projectProcessor.processGraphQlResponse(eq(projB), any(), any()))
                    .thenReturn(repoB);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(2);
            assertThat(result.synced()).extracting(Repository::getNativeId).containsExactlyInAnyOrder(10L, 20L);
            assertThat(result.projectsReconciled()).isEqualTo(1);
        }

        @Test
        void noReconciliationNeeded_whenAllProjectsPresent() {
            var projA = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var projB = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            // Phase 1: returns both projects
            ClientGraphQlResponse mainPage = mockProjectsPageWithGroup(List.of(projA, projB), null);
            // Phase 2: returns same projects (all duplicates, nothing to reconcile)
            ClientGraphQlResponse reconPage = mockProjectsPage(List.of(projA, projB), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, mainPage, reconPage);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repoA = createTestRepository(10L);
            Repository repoB = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(projA), any(), any()))
                    .thenReturn(repoA);
            when(projectProcessor.processGraphQlResponse(eq(projB), any(), any()))
                    .thenReturn(repoB);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(2);
            assertThat(result.projectsReconciled()).isZero();
        }

        @Test
        void subgroupSucceeds_reconciliationFails_preservesSubgroupResults() {
            var projA = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");

            // Phase 1: succeeds
            ClientGraphQlResponse mainPage = mockProjectsPageWithGroup(List.of(projA), null);
            // Phase 2: invalid response
            ClientGraphQlResponse reconPage = mock(ClientGraphQlResponse.class);
            lenient().when(reconPage.isValid()).thenReturn(false);
            lenient().when(reconPage.getErrors()).thenReturn(List.of());

            // Invalid reconciliation response → handler returns ABORT
            when(responseHandler.handle(eq(reconPage), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, mainPage, reconPage);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repoA = createTestRepository(10L);
            when(projectProcessor.processGraphQlResponse(eq(projA), any(), any()))
                    .thenReturn(repoA);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            // Primary results preserved despite reconciliation failure
            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(1);
            assertThat(result.projectsReconciled()).isZero();
        }

        @Test
        void reconciliationWaitsForRateLimit_thenProceeds() throws InterruptedException {
            var projA = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var projB = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            // Phase 1: returns only projA (bug drops projB)
            ClientGraphQlResponse mainPage = mockProjectsPageWithGroup(List.of(projA), null);
            // Phase 2: reconciliation finds projB after rate limit wait
            ClientGraphQlResponse reconPage = mockProjectsPage(List.of(projA, projB), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, mainPage, reconPage);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);
            // waitIfRateLimitLow is called unconditionally — it handles the wait internally
            when(graphQlClientProvider.waitIfRateLimitLow(1L)).thenReturn(true);

            Repository repoA = createTestRepository(10L);
            Repository repoB = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(projA), any(), any()))
                    .thenReturn(repoA);
            when(projectProcessor.processGraphQlResponse(eq(projB), any(), any()))
                    .thenReturn(repoB);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(2);
            assertThat(result.projectsReconciled()).isEqualTo(1);
            // Verify canonical rate limit pattern: waitIfRateLimitLow called during reconciliation
            verify(graphQlClientProvider, atLeast(1)).waitIfRateLimitLow(1L);
            // Verify circuit breaker checked per page during reconciliation
            verify(graphQlClientProvider, atLeast(2)).acquirePermission();
        }

        @Test
        void allProjectsOnlyInDirect_extremeBugManifestation() {
            var projA = createMinimalProject("gid://gitlab/Project/10", "my-org/proj-a", "proj-a");
            var projB = createMinimalProject("gid://gitlab/Project/20", "my-org/proj-b", "proj-b");

            // Phase 1: includeSubgroups=true returns empty (extreme bug)
            ClientGraphQlResponse mainPage = mockProjectsPageWithGroup(List.of(), null);
            // Phase 2: includeSubgroups=false returns all direct projects
            ClientGraphQlResponse reconPage = mockProjectsPage(List.of(projA, projB), null);

            HttpGraphQlClient client = mockClient();
            mockSequentialExecute(client, mainPage, reconPage);
            when(groupProcessor.process(any(), anyLong())).thenReturn(org);
            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            Repository repoA = createTestRepository(10L);
            Repository repoB = createTestRepository(20L);
            when(projectProcessor.processGraphQlResponse(eq(projA), any(), any()))
                    .thenReturn(repoA);
            when(projectProcessor.processGraphQlResponse(eq(projB), any(), any()))
                    .thenReturn(repoB);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.COMPLETED);
            assertThat(result.synced()).hasSize(2);
            assertThat(result.projectsReconciled()).isEqualTo(2);
        }

        @Test
        void reconciliationSkipped_whenPrimarySyncHadApiFailure() {
            HttpGraphQlClient client = mockClient();
            ClientGraphQlResponse invalidResp = mock(ClientGraphQlResponse.class);
            lenient().when(invalidResp.isValid()).thenReturn(false);
            lenient().when(invalidResp.getErrors()).thenReturn(List.of());

            // Invalid response → handler returns ABORT
            when(responseHandler.handle(eq(invalidResp), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(invalidResp));

            when(graphQlClientProvider.getRateLimitRemaining(1L)).thenReturn(100);

            GitLabSyncResult result = service.syncGroupProjects(1L, "my-org", null);

            // API failure → hadApiFailure=true → reconciliation not attempted
            assertThat(result.status()).isEqualTo(GitLabSyncResult.Status.ABORTED_ERROR);
            assertThat(result.synced()).isEmpty();
            assertThat(result.projectsReconciled()).isZero();
        }

        // SyncGroupProjects Helpers

        private static Repository createTestRepository(long nativeId) {
            Repository repo = new Repository();
            repo.setId(nativeId);
            repo.setNativeId(nativeId);
            return repo;
        }

        private static final GitLabGroupResponse DEFAULT_GROUP = new GitLabGroupResponse(
                "gid://gitlab/Group/1", "my-org", "My Org", null, "https://gitlab.com/my-org", null, "public", null);

        private GitLabProjectResponse createMinimalProject(String gid, String fullPath, String name) {
            return new GitLabProjectResponse(
                    gid,
                    fullPath,
                    name,
                    "https://gitlab.com/" + fullPath,
                    null,
                    "public",
                    false,
                    null,
                    null,
                    null,
                    null);
        }

        /**
         * Mocks a projects page response that also includes inline group data
         * (used for first page where group is extracted).
         */
        @SuppressWarnings("unchecked")
        private ClientGraphQlResponse mockProjectsPageWithGroup(
                List<GitLabProjectResponse> projects, @Nullable GitLabPageInfo pageInfo) {
            ClientGraphQlResponse resp = mockProjectsPage(projects, pageInfo);

            // Add group field (inlined in GetGroupProjects query)
            ClientResponseField groupField = mock(ClientResponseField.class);
            when(groupField.toEntity(GitLabGroupResponse.class)).thenReturn(DEFAULT_GROUP);
            when(resp.field("group")).thenReturn(groupField);

            return resp;
        }

        @SuppressWarnings("unchecked")
        private ClientGraphQlResponse mockProjectsPage(
                List<GitLabProjectResponse> projects, @Nullable GitLabPageInfo pageInfo) {
            ClientGraphQlResponse resp = mock(ClientGraphQlResponse.class);
            when(resp.isValid()).thenReturn(true);

            ClientResponseField nodesField = mock(ClientResponseField.class);
            when(nodesField.<GitLabProjectResponse>toEntityList(any(Class.class)))
                    .thenReturn(projects);
            when(resp.field("group.projects.nodes")).thenReturn(nodesField);

            ClientResponseField pageInfoField = mock(ClientResponseField.class);
            when(pageInfoField.<GitLabPageInfo>toEntity(any(Class.class))).thenReturn(pageInfo);
            when(resp.field("group.projects.pageInfo")).thenReturn(pageInfoField);

            return resp;
        }

        private void mockSequentialExecute(
                HttpGraphQlClient client, ClientGraphQlResponse first, ClientGraphQlResponse... rest) {
            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            var responses = when(requestSpec.execute()).thenReturn(Mono.just(first));

            for (ClientGraphQlResponse response : rest) {

                responses = responses.thenReturn(Mono.just(response));
            }
        }
    }

    // Helpers

    private HttpGraphQlClient mockClient() {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        when(graphQlClientProvider.forScope(any())).thenReturn(client);
        return client;
    }

    @SuppressWarnings("unchecked")
    private void mockGraphQlGroupResponse(@Nullable GitLabGroupResponse groupResponse) {
        HttpGraphQlClient client = mockClient();
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.isValid()).thenReturn(true);

        ClientResponseField groupField = mock(ClientResponseField.class);
        when(groupField.toEntity(GitLabGroupResponse.class)).thenReturn(groupResponse);
        when(response.field("group")).thenReturn(groupField);

        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        when(requestSpec.execute()).thenReturn(Mono.just(response));
    }

    @SuppressWarnings("unchecked")
    private HttpGraphQlClient.RequestSpec mockRequestSpec(HttpGraphQlClient client, ClientGraphQlResponse response) {
        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        when(requestSpec.execute()).thenReturn(Mono.just(response));
        return requestSpec;
    }
}
