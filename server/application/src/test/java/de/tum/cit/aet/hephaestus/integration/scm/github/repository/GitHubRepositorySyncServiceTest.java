package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

class GitHubRepositorySyncServiceTest extends BaseUnitTest {
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
        var webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("""
                                {"data":{"repository":%s},"errors":[{"message":"Unavailable",
                                "path":%s,"extensions":{"type":"%s"}}]}
                                """.formatted(
                                repositoryMissing ? "null" : "{\"id\":\"R_test\"}",
                                repositoryMissing ? "[\"repository\"]" : "[\"repository\",\"defaultBranchRef\"]",
                                errorType))
                        .build()))
                .build();
        var clients = mock(GitHubGraphQlClientProvider.class);
        when(clients.forScope(7L))
                .thenReturn(HttpGraphQlClient.builder(webClient)
                        .documentSource(
                                name -> Mono.just("query { repository(owner: \"course\", name: \"project\") { id } }"))
                        .build());
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
        var classifier = new GitHubExceptionClassifier(new SimpleMeterRegistry());
        var coordinator = mock(GitHubGraphQlSyncCoordinator.class);
        when(coordinator.classifyGraphQlErrors(any()))
                .thenAnswer(invocation -> classifier.classifyGraphQlResponse(invocation.getArgument(0)));
        var service = new GitHubRepositorySyncService(
                clients,
                mock(RepositoryRepository.class),
                mock(OrganizationRepository.class),
                properties,
                classifier,
                coordinator,
                webClient);

        if (errorType.equals("NOT_FOUND") && repositoryMissing) {
            assertThatThrownBy(() -> service.syncRepository(7L, "course/project", new IdentityProvider()))
                    .isInstanceOf(RepositoryNotFoundOnGitProviderException.class);
        } else {
            assertThat(service.syncRepository(7L, "course/project", new IdentityProvider()))
                    .isEmpty();
        }
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
