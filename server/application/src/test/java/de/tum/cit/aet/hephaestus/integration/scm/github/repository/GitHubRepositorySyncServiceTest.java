package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestMapper;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.support.ResourceDocumentSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

class GitHubRepositorySyncServiceTest extends BaseUnitTest {
    private MockWebServer upstream;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new MockWebServer();
        upstream.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        upstream.close();
    }

    private GitHubRepositorySyncService service(HttpStatus status, String body) {
        var clients = mock(GitHubGraphQlClientProvider.class);
        when(clients.getToken(7L)).thenReturn("test-token");
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
        var webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(request.url().getPath()).isEqualTo("/repositories/123");
                    assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION))
                            .isEqualTo("Bearer test-token");
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(body)
                            .build());
                })
                .build();
        return new GitHubRepositorySyncService(
                clients,
                mock(RepositoryRepository.class),
                mock(OrganizationRepository.class),
                properties,
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class),
                webClient);
    }

    @ParameterizedTest
    @CsvSource({"RATE_LIMITED, true", "FORBIDDEN, true", "NOT_FOUND, true", "NOT_FOUND, false"})
    void shouldClassifyFieldErrorsBeforeTreatingRepositoryAsUnavailable(String errorType, boolean repositoryMissing) {
        String body = """
                {"data":{"repository":%s},"errors":[{"message":"Unavailable",
                "path":%s,"extensions":{"type":"%s"}}]}
                """.formatted(
                        repositoryMissing ? "null" : "{\"id\":\"R_test\"}",
                        repositoryMissing ? "[\"repository\"]" : "[\"repository\",\"defaultBranchRef\"]",
                        errorType);
        var service = graphQlService(body, mock(RepositoryRepository.class), mock(OrganizationRepository.class));

        if (errorType.equals("NOT_FOUND") && repositoryMissing) {
            assertThatThrownBy(() -> service.syncRepository(7L, "course/project", new IdentityProvider(), null))
                    .isInstanceOf(RepositoryNotFoundOnGitProviderException.class);
        } else {
            assertThat(service.syncRepository(7L, "course/project", new IdentityProvider(), null))
                    .isEmpty();
        }
    }

    @Test
    void shouldPersistRepositoryWhenMonitoredIdentityMatches() {
        var repositories = mock(RepositoryRepository.class);
        var organizations = mock(OrganizationRepository.class);
        when(repositories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = graphQlService("""
                {"data":{"repository":{"databaseId":123,"name":"project","nameWithOwner":"course/project",
                "url":"https://github.com/course/project","isPrivate":false,"isArchived":false,
                "isDisabled":false,"hasDiscussionsEnabled":false,
                "owner":{"__typename":"Organization","databaseId":33,"login":"course"}}}}
                """, repositories, organizations);
        var provider = TestEntities.gitProvider(100L, IdentityProviderType.GITHUB);

        var repository =
                service.syncRepository(7L, "course/project", provider, 123L).orElseThrow();

        assertThat(repository.getNativeId()).isEqualTo(123L);
        assertThat(repository.getNameWithOwner()).isEqualTo("course/project");
        assertThat(repository.getProvider()).isSameAs(provider);
        verify(organizations).upsert(33L, 100L, "course", "course", null, null);
    }

    @Test
    void shouldRejectReassignedNameBeforePersistingRepositoryOrOrganization() {
        var repositories = mock(RepositoryRepository.class);
        var organizations = mock(OrganizationRepository.class);
        var service = graphQlService("""
                {"data":{"repository":{"databaseId":124,
                "owner":{"__typename":"Organization","databaseId":33,"login":"course"}}}}
                """, repositories, organizations);

        assertThatThrownBy(() -> service.syncRepository(
                        7L, "course/project", TestEntities.gitProvider(100L, IdentityProviderType.GITHUB), 123L))
                .isInstanceOf(RepositoryIdentityMismatchException.class);
        verifyNoInteractions(repositories, organizations);
    }

    private GitHubRepositorySyncService graphQlService(
            String body, RepositoryRepository repositories, OrganizationRepository organizations) {
        upstream.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build());
        var webClient =
                WebClient.builder().baseUrl(upstream.url("/graphql").toString()).build();
        var clients = mock(GitHubGraphQlClientProvider.class);
        when(clients.forScope(7L))
                .thenReturn(HttpGraphQlClient.builder(webClient)
                        .codecConfigurer(codecs -> {
                            var mapper = GitHubGraphQlTestMapper.create();
                            codecs.customCodecs().register(new JacksonJsonEncoder(mapper));
                            codecs.customCodecs().register(new JacksonJsonDecoder(mapper));
                        })
                        .documentSource(new ResourceDocumentSource(new ClassPathResource("graphql/github/operations/")))
                        .build());
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
        var classifier = new GitHubExceptionClassifier(new SimpleMeterRegistry());
        var coordinator = mock(GitHubGraphQlSyncCoordinator.class);
        lenient()
                .when(coordinator.classifyGraphQlErrors(any()))
                .thenAnswer(invocation -> classifier.classifyGraphQlResponse(invocation.getArgument(0)));
        return new GitHubRepositorySyncService(
                clients, repositories, organizations, properties, classifier, coordinator, webClient);
    }

    @Test
    void shouldResolveCurrentNameWhenRepositoryWasRenamed() {
        var service = service(HttpStatus.OK, "{\"id\":123,\"full_name\":\"course/renamed\"}");
        assertThat(service.resolveRepositoryNameById(7L, 123L)).isEqualTo("course/renamed");
    }

    @Test
    void shouldReportUnavailableWithoutClaimingDeletionWhenIdCannotBeRead() {
        var service = service(HttpStatus.NOT_FOUND, "{}");
        assertThatThrownBy(() -> service.resolveRepositoryNameById(7L, 123L))
                .isInstanceOf(RepositoryNotFoundOnGitProviderException.class)
                .hasCauseInstanceOf(WebClientResponseException.NotFound.class);
    }

    @Test
    void shouldPreserveRateLimitClassificationWhenIdentityLookupIsRateLimited() {
        var service = service(HttpStatus.TOO_MANY_REQUESTS, "{}");
        assertThatThrownBy(() -> service.resolveRepositoryNameById(7L, 123L))
                .isInstanceOf(WebClientResponseException.TooManyRequests.class);
    }
}
