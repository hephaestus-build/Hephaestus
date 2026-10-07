package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.config.ResilienceConfig;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRestRateLimitSeeder;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class CommitEnrichmentCircuitPermitTest extends BaseUnitTest {

    private static final long REPO_ID = 1L;
    private static final long SCOPE_ID = 1L;
    private static final String SHA = "a".repeat(40);

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitContributorRepository contributorRepository;

    @Mock
    private CommitAuthorResolver authorResolver;

    @Mock
    private GitHubUserProcessor userProcessor;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private GitHubGraphQlSyncCoordinator coordinator;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private RateLimitTracker rateLimitTracker;

    private CircuitBreaker breaker;
    private GraphQlClient.RequestSpec requestSpec;
    private GitHubGraphQlClientProvider provider;

    @BeforeEach
    void setUp() {
        breaker = new ResilienceConfig(new SimpleMeterRegistry())
                .circuitBreakerRegistry()
                .circuitBreaker(ResilienceConfig.GITHUB_GRAPHQL_CIRCUIT_BREAKER);
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        requestSpec = mock(GraphQlClient.RequestSpec.class);
        // A call the breaker refuses never reaches the client.
        lenient().when(client.document(anyString())).thenReturn(requestSpec);
        provider = spy(new GitHubGraphQlClientProvider(
                mock(HttpGraphQlClient.class),
                mock(InstallationTokenProvider.class),
                mock(GitHubAppTokenService.class),
                breaker,
                rateLimitTracker,
                mock(GitHubRestRateLimitSeeder.class),
                mock(SilentModeGraphQlClientFactory.class)));
        lenient().doReturn(client).when(provider).forScope(SCOPE_ID);
    }

    @Test
    @DisplayName("refused metadata batches give their calls back, and three answered batches close the breaker")
    void shouldCloseTheBreakerAfterRefusedMetadataBatches() {
        var service = metadataService();
        when(commitRepository.findShasWithoutContributorsByRepositoryId(REPO_ID))
                .thenReturn(List.of(SHA));
        Commit commit = new Commit();
        commit.setId(42L);
        when(commitRepository.findByShaAndRepositoryId(SHA, REPO_ID)).thenReturn(Optional.of(commit));
        ClientGraphQlResponse unavailable = unavailableAndRetried();
        ClientGraphQlResponse answered = answered(commitData(Map.of("nodes", List.of(author("one@example.com")))));
        when(requestSpec.execute())
                .thenReturn(Mono.just(unavailable))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(answered));

        // An unavailable answer that is retried, then no answer; then no answer again.
        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isZero();
        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isZero();
        assertProbingWith(0);
        verify(contributorRepository, never()).upsertContributor(anyLong(), any(), any(), any(), any(), anyInt());

        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);
        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);
        assertProbingWith(2);
        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(contributorRepository, times(3))
                .upsertContributor(eq(42L), any(), eq("AUTHOR"), any(), eq("one@example.com"), eq(0));
    }

    @Test
    @DisplayName("a refused author follow-up gives its calls back, and later answered batches close the breaker")
    void shouldCloseTheBreakerAfterARefusedAuthorFollowUp() {
        var service = metadataService();
        when(commitRepository.findShasWithoutContributorsByRepositoryId(REPO_ID))
                .thenReturn(List.of(SHA));
        Commit commit = new Commit();
        commit.setId(42L);
        when(commitRepository.findByShaAndRepositoryId(SHA, REPO_ID)).thenReturn(Optional.of(commit));
        ClientGraphQlResponse overflowed = overflowed();
        ClientGraphQlResponse unavailable = unavailableAndRetried();
        ClientGraphQlResponse answered = answered(commitData(Map.of("nodes", List.of(author("one@example.com")))));
        when(requestSpec.execute())
                // The batch is answered with a truncated author list; its follow-up is unavailable and retried,
                // then unanswered. The next batch is unanswered too.
                .thenReturn(Mono.just(overflowed))
                .thenReturn(Mono.just(unavailable))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(answered));

        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);
        verify(contributorRepository)
                .upsertContributor(eq(42L), any(), eq("AUTHOR"), any(), eq("author1@example.com"), eq(0));
        verify(contributorRepository, times(CommitMetadataEnrichmentService.AUTHORS_PAGE_SIZE - 1))
                .upsertContributor(eq(42L), any(), eq("CO_AUTHOR"), any(), any(), anyInt());
        verify(contributorRepository, never())
                .upsertContributor(anyLong(), any(), any(), any(), eq("author11@example.com"), anyInt());
        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isZero();
        assertProbingWith(1);

        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);
        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("refused author batches give their calls back and resolve nobody; answered ones close the breaker")
    void shouldCloseTheBreakerAfterRefusedAuthorBatches() {
        var service = new CommitAuthorEnrichmentService(
                commitRepository,
                authorResolver,
                provider,
                coordinator,
                exceptionClassifier,
                userProcessor,
                eventPublisher);
        when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(REPO_ID))
                .thenReturn(List.of("dev@example.com"));
        when(authorResolver.resolveByEmail(eq("dev@example.com"), any())).thenReturn(null);
        when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(REPO_ID))
                .thenReturn(List.of());
        when(commitRepository.findRepresentativeShasByUnresolvedEmail(REPO_ID))
                .thenReturn(List.<Object[]>of(new Object[] {"dev@example.com", SHA}));
        ClientGraphQlResponse unavailable = unavailableAndRetried();
        // The commit is answered, with no GitHub account matched to its author.
        ClientGraphQlResponse answered = mock(ClientGraphQlResponse.class);
        when(answered.isValid()).thenReturn(true);
        when(answered.field("repository.commit0")).thenReturn(mock(ClientResponseField.class));
        when(requestSpec.execute())
                .thenReturn(Mono.just(unavailable))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(answered));

        service.enrichCommitAuthors(REPO_ID, "owner/repo", SCOPE_ID, 1L, null);
        service.enrichCommitAuthors(REPO_ID, "owner/repo", SCOPE_ID, 1L, null);
        assertProbingWith(0);
        verify(authorResolver, never()).resolveByLogin(any(), any());

        service.enrichCommitAuthors(REPO_ID, "owner/repo", SCOPE_ID, 1L, null);
        service.enrichCommitAuthors(REPO_ID, "owner/repo", SCOPE_ID, 1L, null);
        assertProbingWith(2);
        service.enrichCommitAuthors(REPO_ID, "owner/repo", SCOPE_ID, 1L, null);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(commitRepository, never()).bulkUpdateAuthorIdByEmail(any(), any(), any());
    }

    @Test
    @DisplayName("calls the saturated breaker refuses are neither sent nor counted, and enrich nothing")
    void shouldNeitherSendNorCountCallsTheSaturatedBreakerRefuses() {
        IntStream.range(0, 3).forEach(i -> breaker.acquirePermission());
        abortsUnclassifiedFailures();
        when(commitRepository.findShasWithoutContributorsByRepositoryId(REPO_ID))
                .thenReturn(List.of(SHA));
        when(commitRepository.findDistinctUnresolvedAuthorEmailsByRepositoryId(REPO_ID))
                .thenReturn(List.of("dev@example.com"));
        when(authorResolver.resolveByEmail(eq("dev@example.com"), any())).thenReturn(null);
        when(commitRepository.findDistinctUnresolvedCommitterEmailsByRepositoryId(REPO_ID))
                .thenReturn(List.of());
        when(commitRepository.findRepresentativeShasByUnresolvedEmail(REPO_ID))
                .thenReturn(List.<Object[]>of(new Object[] {"dev@example.com", SHA}));

        assertThat(metadataService().enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isZero();
        assertThat(authorService().enrichCommitAuthors(REPO_ID, "owner/repo", SCOPE_ID, 1L, null))
                .isZero();

        assertProbingWith(0);
        verify(requestSpec, never()).execute();
        verify(contributorRepository, never()).upsertContributor(anyLong(), any(), any(), any(), any(), anyInt());
        verify(commitRepository, never()).bulkUpdateAuthorIdByEmail(any(), any(), any());
    }

    @Test
    @DisplayName("a follow-up the breaker refuses is not counted, and the batch's first page stays as it was")
    void shouldNotCountAFollowUpTheBreakerRefuses() {
        IntStream.range(0, 2).forEach(i -> breaker.acquirePermission());
        abortsUnclassifiedFailures();
        var service = metadataServiceWithCommit();
        ClientGraphQlResponse firstPage = overflowed();
        when(requestSpec.execute()).thenReturn(Mono.just(firstPage));

        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);

        assertProbingWith(1);
        verify(requestSpec, times(1)).execute();
        assertOnlyFirstAuthorPageStored();
    }

    @Test
    @DisplayName("a follow-up answer that cannot be read counts once as answered, never as an outage")
    @SuppressWarnings("unchecked")
    void shouldCountAnUnreadableFollowUpAnswerOnceAsAnswered() {
        abortsUnclassifiedFailures();
        var service = metadataServiceWithCommit();
        ClientResponseField authors = mock(ClientResponseField.class);
        when(authors.getValue()).thenReturn(Map.of("nodes", List.of(author("author11@example.com"))));
        when(authors.toEntity(any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("Unsupported connection shape"));
        ClientGraphQlResponse followUp = mock(ClientGraphQlResponse.class);
        when(followUp.isValid()).thenReturn(true);
        when(followUp.field("repository.object.authors")).thenReturn(authors);
        ClientGraphQlResponse firstPage = overflowed();
        ClientGraphQlResponse nextBatch = answered(commitData(Map.of("nodes", List.of(author("one@example.com")))));
        when(requestSpec.execute())
                .thenReturn(Mono.just(firstPage))
                .thenReturn(Mono.just(followUp))
                .thenReturn(Mono.just(nextBatch));

        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);
        assertProbingWith(2);
        assertOnlyFirstAuthorPageStored();

        assertThat(service.enrichCommitMetadata(REPO_ID, "owner/repo", SCOPE_ID))
                .isEqualTo(1);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private CommitMetadataEnrichmentService metadataServiceWithCommit() {
        when(commitRepository.findShasWithoutContributorsByRepositoryId(REPO_ID))
                .thenReturn(List.of(SHA));
        Commit commit = new Commit();
        commit.setId(42L);
        when(commitRepository.findByShaAndRepositoryId(SHA, REPO_ID)).thenReturn(Optional.of(commit));
        return metadataService();
    }

    private CommitAuthorEnrichmentService authorService() {
        return new CommitAuthorEnrichmentService(
                commitRepository,
                authorResolver,
                provider,
                coordinator,
                exceptionClassifier,
                userProcessor,
                eventPublisher);
    }

    private void abortsUnclassifiedFailures() {
        when(exceptionClassifier.classifyWithDetails(any()))
                .thenReturn(ClassificationResult.of(Category.UNKNOWN, "Unclassified failure"));
    }

    private void assertOnlyFirstAuthorPageStored() {
        verify(contributorRepository)
                .upsertContributor(eq(42L), any(), eq("AUTHOR"), any(), eq("author1@example.com"), eq(0));
        verify(contributorRepository, times(CommitMetadataEnrichmentService.AUTHORS_PAGE_SIZE - 1))
                .upsertContributor(eq(42L), any(), eq("CO_AUTHOR"), any(), any(), anyInt());
        verify(contributorRepository, never())
                .upsertContributor(anyLong(), any(), any(), any(), eq("author11@example.com"), anyInt());
    }

    private static ClientGraphQlResponse overflowed() {
        return answered(commitData(Map.of(
                "totalCount",
                CommitMetadataEnrichmentService.AUTHORS_PAGE_SIZE + 1,
                "nodes",
                IntStream.rangeClosed(1, CommitMetadataEnrichmentService.AUTHORS_PAGE_SIZE)
                        .mapToObj(i -> author("author" + i + "@example.com"))
                        .toList(),
                "pageInfo",
                Map.of("hasNextPage", true, "endCursor", "cursor-1"))));
    }

    private CommitMetadataEnrichmentService metadataService() {
        return new CommitMetadataEnrichmentService(
                commitRepository, contributorRepository, provider, coordinator, exceptionClassifier);
    }

    private void assertProbingWith(int answeredCalls) {
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(answeredCalls);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /** A semantic error the coordinator classifies as worth another attempt, retried without waiting. */
    private ClientGraphQlResponse unavailableAndRetried() {
        ClientGraphQlResponse unavailable = mock(ClientGraphQlResponse.class);
        when(unavailable.isValid()).thenReturn(false);
        when(coordinator.classifyGraphQlErrors(unavailable))
                .thenReturn(ClassificationResult.of(Category.RETRYABLE, "Service unavailable"));
        when(coordinator.handleGraphQlClassification(any())).thenReturn(true);
        return unavailable;
    }

    @SuppressWarnings("unchecked")
    private static ClientGraphQlResponse answered(Map<String, Object> commitData) {
        ClientResponseField field = mock(ClientResponseField.class);
        when(field.getValue()).thenReturn(commitData);
        when(field.toEntity(any(ParameterizedTypeReference.class))).thenReturn(commitData);
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        when(response.field("repository.commit0")).thenReturn(field);
        return response;
    }

    private static Map<String, Object> commitData(Map<String, Object> authors) {
        Map<String, Object> data = new HashMap<>();
        data.put("oid", SHA);
        data.put("additions", 1);
        data.put("deletions", 0);
        data.put("authoredDate", "2025-01-15T10:30:00Z");
        data.put("committedDate", "2025-01-15T10:30:00Z");
        data.put("messageHeadline", "Add a feature");
        data.put("url", "https://github.com/owner/repo/commit/" + SHA);
        data.put("authoredByCommitter", true);
        data.put("committedViaWeb", false);
        data.put("parents", Map.of("totalCount", 1));
        data.put("authors", authors);
        data.put("committer", Map.of("name", "One", "email", "one@example.com"));
        data.put("associatedPullRequests", Map.of("nodes", List.of()));
        return data;
    }

    private static Map<String, Object> author(String email) {
        return Map.of("name", "One", "email", email);
    }
}
