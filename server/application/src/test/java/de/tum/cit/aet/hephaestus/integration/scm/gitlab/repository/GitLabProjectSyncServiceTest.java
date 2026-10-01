package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitLabProjectSyncServiceTest extends BaseUnitTest {

    private static final Long TEST_PROVIDER_ID = 100L;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private GitLabProjectProcessor projectProcessor;

    @Mock
    private GitLabGroupProcessor groupProcessor;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    private final GitLabProperties gitLabProperties = new GitLabProperties(
            "https://gitlab.com",
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofMillis(10),
            Duration.ofMinutes(5));

    private GitLabProjectSyncService service;

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

        service = new GitLabProjectSyncService(
                graphQlClientProvider,
                responseHandler,
                projectProcessor,
                groupProcessor,
                gitLabProperties,
                gitProviderRepository,
                new GitLabExceptionClassifier(new SimpleMeterRegistry()));
    }

    @ParameterizedTest
    @CsvSource({"RATE_LIMITED, true", "UNAUTHORIZED, true", "NOT_FOUND, true", "NOT_FOUND, false"})
    void shouldClassifyFieldErrorsBeforeTreatingProjectAsUnavailable(String errorType, boolean projectMissing) {
        var webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {"data":{"project":%s},"errors":[{"message":"Unavailable",
                                "path":%s,"extensions":{"code":"%s"}}]}
                                """.formatted(
                                        projectMissing ? "null" : "{\"id\":\"gid://gitlab/Project/123\"}",
                                        projectMissing ? "[\"project\"]" : "[\"project\",\"group\"]",
                                        errorType))
                        .build()))
                .build();
        when(graphQlClientProvider.forScope(1L))
                .thenReturn(HttpGraphQlClient.builder(webClient)
                        .documentSource(name -> Mono.just("query { project(fullPath: \"course/project\") { id } }"))
                        .build());

        assertThatThrownBy(() -> service.fetchProject(1L, "course/project"))
                .isInstanceOf(
                        errorType.equals("NOT_FOUND") && projectMissing
                                ? RepositoryNotFoundOnGitProviderException.class
                                : GitLabSyncException.class);
    }

    @Nested
    class SyncProject {

        @Test
        void nullProjectPath_returnsEmpty() {
            Optional<Repository> result = service.syncProject(1L, null);
            assertThat(result).isEmpty();
            verify(graphQlClientProvider, never()).acquirePermission();
        }

        @Test
        void blankProjectPath_returnsEmpty() {
            Optional<Repository> result = service.syncProject(1L, "  ");
            assertThat(result).isEmpty();
            verify(graphQlClientProvider, never()).acquirePermission();
        }

        @Test
        void successfulSync_returnsRepositoryWithGroup() {
            var groupData = new GitLabGroupResponse(
                    "gid://gitlab/Group/42",
                    "my-org",
                    "My Org",
                    null,
                    "https://gitlab.com/my-org",
                    null,
                    "public",
                    null);
            var projectData = new GitLabProjectResponse(
                    "gid://gitlab/Project/123",
                    "my-org/my-project",
                    "my-project",
                    "https://gitlab.com/my-org/my-project",
                    "Description",
                    "public",
                    false,
                    "2024-01-15T10:30:00Z",
                    null,
                    groupData,
                    new GitLabProjectResponse.RepositoryInfo("main"));

            mockGraphQlProjectResponse(projectData);

            Organization org = new Organization();
            org.setId(42L);
            when(groupProcessor.process(any(GitLabGroupResponse.class), anyLong()))
                    .thenReturn(org);

            Repository repo = new Repository();
            repo.setId(123L);
            repo.setNameWithOwner("my-org/my-project");
            when(projectProcessor.processGraphQlResponse(any(), any(), any())).thenReturn(repo);

            Optional<Repository> result = service.syncProject(1L, "my-org/my-project");

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(123L);
            verify(graphQlClientProvider).acquirePermission();
            verify(graphQlClientProvider).recordSuccess();
            verify(groupProcessor).process(groupData, TEST_PROVIDER_ID);
            verify(projectProcessor).processGraphQlResponse(eq(projectData), eq(org), any());
        }

        @Test
        void projectWithoutGroup_syncsWithNullOrg() {
            var projectData = new GitLabProjectResponse(
                    "gid://gitlab/Project/456",
                    "user/personal-project",
                    "personal-project",
                    "https://gitlab.com/user/personal-project",
                    null,
                    "private",
                    false,
                    null,
                    null,
                    null, // no group
                    null);

            mockGraphQlProjectResponse(projectData);

            Repository repo = new Repository();
            repo.setId(456L);
            when(projectProcessor.processGraphQlResponse(any(), any(), any())).thenReturn(repo);

            Optional<Repository> result = service.syncProject(1L, "user/personal-project");

            assertThat(result).isPresent();
            verify(groupProcessor, never()).process(any(), anyLong());
            verify(projectProcessor).processGraphQlResponse(eq(projectData), eq(null), any());
        }

        @Test
        void projectNotFound_returnsEmpty() {
            mockGraphQlProjectResponse(null);

            Optional<Repository> result = service.syncProject(1L, "non-existent/project");

            assertThat(result).isEmpty();
            verify(projectProcessor, never()).processGraphQlResponse(any(), any(), any());
        }

        @Test
        void invalidResponse_returnsEmptyAndRecordsFailure() {
            HttpGraphQlClient client = mock(HttpGraphQlClient.class);
            when(graphQlClientProvider.forScope(any())).thenReturn(client);

            ClientGraphQlResponse invalidResponse = mock(ClientGraphQlResponse.class);
            lenient().when(invalidResponse.isValid()).thenReturn(false);
            lenient().when(invalidResponse.getErrors()).thenReturn(List.of());

            // Override the default CONTINUE with ABORT for this invalid response
            when(responseHandler.handle(eq(invalidResponse), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));

            HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
            when(client.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.just(invalidResponse));

            Optional<Repository> result = service.syncProject(1L, "my-org/my-project");

            assertThat(result).isEmpty();
            verify(graphQlClientProvider).recordFailure(any());
        }

        @Test
        void groupProcessorReturnsNull_abortsSync() {
            var groupData = new GitLabGroupResponse(
                    "gid://gitlab/Group/42",
                    "my-org",
                    "My Org",
                    null,
                    "https://gitlab.com/my-org",
                    null,
                    "public",
                    null);
            var projectData = new GitLabProjectResponse(
                    "gid://gitlab/Project/789",
                    "my-org/proj",
                    "proj",
                    "https://gitlab.com/my-org/proj",
                    null,
                    "public",
                    false,
                    null,
                    null,
                    groupData,
                    null);

            mockGraphQlProjectResponse(projectData);
            when(groupProcessor.process(any(), anyLong())).thenReturn(null); // group processor rejects

            Optional<Repository> result = service.syncProject(1L, "my-org/proj");

            assertThat(result).isEmpty();
            verify(projectProcessor, never()).processGraphQlResponse(any(), any(), any());
        }

        @Test
        void exception_returnsEmptyAndRecordsFailure() {
            when(graphQlClientProvider.forScope(any())).thenThrow(new RuntimeException("connection refused"));

            Optional<Repository> result = service.syncProject(1L, "my-org/my-project");

            assertThat(result).isEmpty();
            verify(graphQlClientProvider).recordFailure(any());
        }
    }

    // Helpers

    @SuppressWarnings("unchecked")
    private void mockGraphQlProjectResponse(@Nullable GitLabProjectResponse projectResponse) {
        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        when(graphQlClientProvider.forScope(any())).thenReturn(client);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.isValid()).thenReturn(true);

        ClientResponseField projectField = mock(ClientResponseField.class);
        when(projectField.toEntity(GitLabProjectResponse.class)).thenReturn(projectResponse);
        when(response.field("project")).thenReturn(projectField);

        HttpGraphQlClient.RequestSpec requestSpec = mock(HttpGraphQlClient.RequestSpec.class);
        when(client.documentName(anyString())).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        when(requestSpec.execute()).thenReturn(Mono.just(response));
    }
}
