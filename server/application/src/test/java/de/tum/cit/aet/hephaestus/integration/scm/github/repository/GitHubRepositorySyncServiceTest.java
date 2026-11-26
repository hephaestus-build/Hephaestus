package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.RepositoryNotFoundOnGitProviderException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore.Result;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator.GraphQlClassificationContext;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestMapper;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.support.ResourceDocumentSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.scheduler.VirtualTimeScheduler;

class GitHubRepositorySyncServiceTest extends BaseUnitTest {
    private static ReadersRead unmonitoredRead() {
        return new ReadersRead(7L, null, 123L, 100L, -1L, Instant.EPOCH);
    }

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
                mock(RepositoryObservationStore.class),
                mock(OrganizationRepository.class),
                properties,
                new GitHubExceptionClassifier(new SimpleMeterRegistry()),
                mock(GitHubGraphQlSyncCoordinator.class),
                webClient,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                List.of(),
                List.of(),
                VirtualTimeScheduler.create());
    }

    @ParameterizedTest
    @CsvSource({"RATE_LIMITED, true", "FORBIDDEN, true", "FORBIDDEN, false", "NOT_FOUND, true", "NOT_FOUND, false"})
    void shouldClassifyFieldErrorsBeforeTreatingRepositoryAsUnavailable(String errorType, boolean repositoryMissing) {
        String body = """
                {"data":{"repository":%s},"errors":[{"message":"Unavailable",
                "path":%s,"extensions":{"type":"%s"}}]}
                """.formatted(
                        repositoryMissing ? "null" : "{\"id\":\"R_test\"}",
                        repositoryMissing ? "[\"repository\"]" : "[\"repository\",\"defaultBranchRef\"]",
                        errorType);
        var service = graphQlService(body, mock(RepositoryObservationStore.class), mock(OrganizationRepository.class));

        if ((errorType.equals("NOT_FOUND") || errorType.equals("FORBIDDEN")) && repositoryMissing) {
            assertThatThrownBy(() -> service.syncRepository(
                            7L, "course/project", new IdentityProvider(), null, unmonitoredRead()))
                    .isInstanceOf(RepositoryNotFoundOnGitProviderException.class);
        } else {
            assertThat(service.syncRepository(7L, "course/project", new IdentityProvider(), null, unmonitoredRead()))
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "FORBIDDEN", "UNAUTHORIZED"})
    void shouldReportUnavailableWhenGitHubRefusesRepositoryAccess(String type) {
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        var service = graphQlService("""
                {"data":{"repository":null},"errors":[{"type":"%s","path":["repository"],"message":"Synthetic access refusal"}]}
                """.formatted(type), repositories, organizations);
        assertThatThrownBy(() -> service.syncRepository(
                        7L, "example/repository", new IdentityProvider(), null, unmonitoredRead()))
                .isInstanceOf(RepositoryNotFoundOnGitProviderException.class);
        verifyNoInteractions(repositories, organizations);
        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "FORBIDDEN"})
    void shouldWithholdUnavailableStateWhenAChildErrorNullsItsParent(String type) {
        var service = graphQlService(
                """
                {"data":{"repository":null},"errors":[{"type":"%s","path":["repository","defaultBranchRef"],"message":"Synthetic child refusal"}]}
                """.formatted(type), mock(RepositoryObservationStore.class), mock(OrganizationRepository.class));
        assertThat(service.syncRepository(7L, "example/repository", new IdentityProvider(), null, unmonitoredRead()))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "FORBIDDEN", "UNAUTHORIZED"})
    void shouldReportUnavailableWhenTheSingleRepositoryQueryHasAPathlessRefusal(String type) {
        var service = graphQlService(
                """
                {"data":null,"errors":[{"type":"%s","message":"Synthetic access refusal"}]}
                """.formatted(type), mock(RepositoryObservationStore.class), mock(OrganizationRepository.class));
        assertThatThrownBy(() -> service.syncRepository(
                        7L, "example/repository", new IdentityProvider(), null, unmonitoredRead()))
                .isInstanceOf(RepositoryNotFoundOnGitProviderException.class);
    }

    @Test
    void shouldWithholdUnavailableStateWhenForbiddenMeansRateLimited() {
        var service = graphQlService("""
                {"data":{"repository":null},"errors":[{"type":"FORBIDDEN","path":["repository"],"message":"API rate limit exceeded"}]}
                """, mock(RepositoryObservationStore.class), mock(OrganizationRepository.class));
        assertThat(service.syncRepository(7L, "example/repository", new IdentityProvider(), null, unmonitoredRead()))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "FORBIDDEN"})
    void shouldWithholdUnavailableStateWhenARefusalHasATransientSibling(String type) {
        var service = graphQlService(
                """
                {"data":{"repository":null},"errors":[
                  {"type":"%s","path":["repository"],"message":"Synthetic refusal"},
                  {"type":"RATE_LIMITED","path":["repository","defaultBranchRef"],"message":null}]}
                """.formatted(type), mock(RepositoryObservationStore.class), mock(OrganizationRepository.class));
        assertThat(service.syncRepository(7L, "example/repository", new IdentityProvider(), null, unmonitoredRead()))
                .isEmpty();
    }

    @Test
    void shouldWithholdUnavailableStateWhenOnlyAChildHasAKnownRefusal() {
        var service = graphQlService("""
                {"data":{"repository":null},"errors":[
                  {"type":"SOMETHING_NEW","path":["repository"],"message":"Synthetic root failure"},
                  {"type":"NOT_FOUND","path":["repository","defaultBranchRef"],"message":"Synthetic missing child"}]}
                """, mock(RepositoryObservationStore.class), mock(OrganizationRepository.class));
        assertThat(service.syncRepository(7L, "example/repository", new IdentityProvider(), null, unmonitoredRead()))
                .isEmpty();
    }

    @Test
    void shouldPersistRepositoryWhenMonitoredIdentityMatches() {
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        when(repositories.observe(any(), any())).thenReturn(new Result(true, new Repository()));
        var service = graphQlService("""
                {"data":{"repository":{"databaseId":123,"name":"project","nameWithOwner":"course/project",
                "url":"https://github.com/course/project","isPrivate":false,"isArchived":false,
                "isDisabled":false,
                "owner":{"__typename":"Organization","databaseId":33,"login":"course"}}}}
                """, repositories, organizations);
        var provider = TestEntities.gitProvider(100L, IdentityProviderType.GITHUB);

        assertThat(service.syncRepository(7L, "course/project", provider, 123L, unmonitoredRead()))
                .isPresent();

        var observation = ArgumentCaptor.forClass(RepositoryObservationStore.Observation.class);
        verify(repositories).observe(eq(unmonitoredRead()), observation.capture());
        assertThat(observation.getValue().patch()).containsEntry("name_with_owner", "course/project");
        verify(organizations).upsert(33L, 100L, "course", "course", null, null);
    }

    @Test
    void shouldLetAFailedWriteReachTheCallerInsteadOfTreatingItAsAGitHubError() {
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        when(repositories.observe(any(), any())).thenThrow(new DataIntegrityViolationException("duplicate repository"));
        var service = graphQlService("""
                {"data":{"repository":{"databaseId":123,"name":"project","nameWithOwner":"course/project",
                "url":"https://github.com/course/project","isPrivate":false,"isArchived":false,
                "isDisabled":false,
                "owner":{"__typename":"Organization","databaseId":33,"login":"course"}}}}
                """, repositories, organizations);
        var provider = TestEntities.gitProvider(100L, IdentityProviderType.GITHUB);

        assertThatThrownBy(() -> service.syncRepository(7L, "course/project", provider, 123L, unmonitoredRead()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectReassignedNameBeforePersistingRepositoryOrOrganization() {
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        var service = graphQlService("""
                {"data":{"repository":{"databaseId":124,
                "owner":{"__typename":"Organization","databaseId":33,"login":"course"}}}}
                """, repositories, organizations);

        assertThatThrownBy(() -> service.syncRepository(
                        7L,
                        "course/project",
                        TestEntities.gitProvider(100L, IdentityProviderType.GITHUB),
                        123L,
                        unmonitoredRead()))
                .isInstanceOf(RepositoryIdentityMismatchException.class);
        verifyNoInteractions(repositories, organizations);
    }

    private GitHubRepositorySyncService graphQlService(
            String body, RepositoryObservationStore repositories, OrganizationRepository organizations) {
        return graphQlService(HttpStatus.OK, body, repositories, organizations);
    }

    private GitHubRepositorySyncService graphQlService(
            HttpStatus status,
            String body,
            RepositoryObservationStore repositories,
            OrganizationRepository organizations) {
        return graphQlService(status, body, repositories, organizations, List.of(), List.of());
    }

    @Test
    void shouldWithdrawRenamedExcludedRepositoryBeforeStoringItsMetadata() {
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        var targets = mock(SyncTargetProvider.class);
        var filter = mock(RepositoryScopeFilter.class);
        when(filter.isRepositoryAllowed("course/reports")).thenReturn(false);
        var service =
                graphQlService(HttpStatus.OK, """
                {"data":{"repository":{"databaseId":123,"name":"reports","nameWithOwner":"course/reports",
                "owner":{"__typename":"Organization","databaseId":33,"login":"course"}}}}
                """, repositories, organizations, List.of(filter), List.of(targets));

        assertThat(service.syncRepository(
                        7L,
                        "course/old-reports",
                        TestEntities.gitProvider(100L, IdentityProviderType.GITHUB),
                        123L,
                        unmonitoredRead()))
                .isEmpty();

        verifyNoInteractions(repositories, organizations);
        verify(targets).reconcileSyncTargetsForRepository(100L, 123L, "course/reports");
    }

    private GitHubRepositorySyncService graphQlService(
            HttpStatus status,
            String body,
            RepositoryObservationStore repositories,
            OrganizationRepository organizations,
            List<RepositoryScopeFilter> filters,
            List<SyncTargetProvider> targets) {
        upstream.enqueue(new MockResponse.Builder()
                .code(status.value())
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
                clients,
                repositories,
                organizations,
                properties,
                classifier,
                coordinator,
                webClient,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                filters,
                targets,
                VirtualTimeScheduler.create());
    }

    /** The timeout counts on {@code timeouts}: a virtual clock fires it only when a test advances it. */
    private static GitHubRepositorySyncService syncService(
            GitHubGraphQlClientProvider clients,
            GitHubGraphQlSyncCoordinator coordinator,
            RepositoryObservationStore repositories,
            OrganizationRepository organizations,
            WebClient webClient,
            Scheduler timeouts) {
        var properties = mock(GitHubSyncProperties.class);
        lenient().when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
        return new GitHubRepositorySyncService(
                clients,
                repositories,
                organizations,
                properties,
                new GitHubExceptionClassifier(new SimpleMeterRegistry()),
                coordinator,
                webClient,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                List.of(),
                List.of(),
                timeouts);
    }

    @Test
    void shouldRetryAFailureOutsideTheResponseAsItsPolicySaysThenSkipTheRound() {
        var clients = mock(GitHubGraphQlClientProvider.class);
        when(clients.forScope(7L))
                .thenThrow(new UncheckedIOException(new SocketTimeoutException("installation token request")));
        var coordinator = mock(GitHubGraphQlSyncCoordinator.class);
        // The policy, not this test, owns the backoff: it allows two retries and then gives up.
        when(coordinator.handleGraphQlClassification(any())).thenReturn(true, true, false);
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        var service = syncService(
                clients, coordinator, repositories, organizations, WebClient.create(), VirtualTimeScheduler.create());

        assertThat(service.syncRepository(
                        7L,
                        "course/project",
                        TestEntities.gitProvider(100L, IdentityProviderType.GITHUB),
                        123L,
                        unmonitoredRead()))
                .isEmpty();

        var asked = ArgumentCaptor.forClass(GraphQlClassificationContext.class);
        verify(coordinator, times(3)).handleGraphQlClassification(asked.capture());
        assertThat(asked.getAllValues())
                .extracting(GraphQlClassificationContext::retryAttempt)
                .containsExactly(0, 1, 2);
        assertThat(asked.getAllValues())
                .extracting(context -> context.classification().category())
                .containsOnly(GitHubExceptionClassifier.Category.RETRYABLE);
        verify(clients, times(3)).forScope(7L);
        verifyNoInteractions(repositories, organizations);
    }

    @Test
    void shouldTreatAGitHubThatDoesNotAnswerWithinTheTimeoutAsRetryable() throws Exception {
        var requested = new CountDownLatch(1);
        var webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requested.countDown();
                    return Mono.never();
                })
                .build();
        var clients = mock(GitHubGraphQlClientProvider.class);
        when(clients.forScope(7L))
                .thenReturn(HttpGraphQlClient.builder(webClient)
                        .documentSource(new ResourceDocumentSource(new ClassPathResource("graphql/github/operations/")))
                        .build());
        var coordinator = mock(GitHubGraphQlSyncCoordinator.class);
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        var clock = VirtualTimeScheduler.create();
        var service = syncService(clients, coordinator, repositories, organizations, webClient, clock);

        var synced = CompletableFuture.supplyAsync(() -> service.syncRepository(
                7L,
                "course/project",
                TestEntities.gitProvider(100L, IdentityProviderType.GITHUB),
                123L,
                unmonitoredRead()));
        // The exchange is under way: only the virtual clock can end it now. The waits keep a broken test from hanging.
        assertThat(requested.await(1, TimeUnit.MINUTES)).isTrue();
        clock.advanceTimeBy(Duration.ofSeconds(2));

        assertThat(synced.get(1, TimeUnit.MINUTES)).isEmpty();
        var asked = ArgumentCaptor.forClass(GraphQlClassificationContext.class);
        verify(coordinator).handleGraphQlClassification(asked.capture());
        assertThat(asked.getValue().classification().category())
                .isEqualTo(GitHubExceptionClassifier.Category.RETRYABLE);
        verifyNoInteractions(repositories, organizations);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429})
    void shouldClassifyHttpErrorsWithoutRetryingThemAsNetworkFailures(int status) {
        var repositories = mock(RepositoryObservationStore.class);
        var organizations = mock(OrganizationRepository.class);
        var service = graphQlService(HttpStatus.valueOf(status), "{}", repositories, organizations);
        if (status == 429) {
            assertThat(service.syncRepository(7L, "course/project", new IdentityProvider(), null, unmonitoredRead()))
                    .isEmpty();
        } else {
            assertThatThrownBy(() -> service.syncRepository(
                            7L, "course/project", new IdentityProvider(), null, unmonitoredRead()))
                    .isInstanceOf(RepositoryNotFoundOnGitProviderException.class);
        }
        assertThat(upstream.getRequestCount()).isEqualTo(1);
        verifyNoInteractions(repositories, organizations);
    }

    @Test
    void shouldResolveCurrentNameWhenRepositoryWasRenamed() {
        var service = service(HttpStatus.OK, "{\"id\":123,\"full_name\":\"course/renamed\"}");
        assertThat(service.resolveRepositoryNameById(7L, 123L)).isEqualTo("course/renamed");
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void shouldReportUnavailableWithoutClaimingDeletionWhenIdCannotBeRead(int status) {
        var service = service(HttpStatus.valueOf(status), "{}");
        assertThatThrownBy(() -> service.resolveRepositoryNameById(7L, 123L))
                .isInstanceOf(RepositoryNotFoundOnGitProviderException.class)
                .hasCauseInstanceOf(WebClientResponseException.class);
    }

    @Test
    void shouldPreserveRateLimitClassificationWhenIdentityLookupIsRateLimited() {
        var service = service(HttpStatus.TOO_MANY_REQUESTS, "{}");
        assertThatThrownBy(() -> service.resolveRepositoryNameById(7L, 123L))
                .isInstanceOf(WebClientResponseException.TooManyRequests.class);
    }
}
