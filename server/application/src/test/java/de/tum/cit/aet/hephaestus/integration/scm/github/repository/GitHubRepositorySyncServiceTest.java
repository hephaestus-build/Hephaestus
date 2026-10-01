package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
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
        return spy(new GitHubRepositorySyncService(
                clients,
                mock(RepositoryRepository.class),
                mock(OrganizationRepository.class),
                properties,
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class),
                webClient));
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
