package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Commit enrichment on PostgreSQL, with only GitHub scripted: GitHub is asked with no transaction open, and each
 * commit's rows commit together or not at all.
 */
class CommitEnrichmentOutsideTransactionsIntegrationTest extends BaseIntegrationTest {
    private static final long SCOPE_ID = 1L;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    // A plain mock, not a @MockitoBean: the service below is built over it, so the shared context stays unchanged.
    private final GitHubGraphQlClientProvider clients = mock(GitHubGraphQlClientProvider.class);
    private final HttpGraphQlClient client = mock(HttpGraphQlClient.class);
    private final ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
    // Whether a transaction was open each time GitHub was asked.
    private final List<Boolean> askedInTransaction = new ArrayList<>();
    private CommitMetadataEnrichmentService service;
    private Repository repository;

    @BeforeEach
    void repositoryAndScriptedGitHub() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        String name = "enrichment-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        created.setName(name);
        created.setNameWithOwner("enrichment/" + name);
        created.setHtmlUrl("https://github.com/enrichment/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        repository = repositories.save(created);

        when(clients.forScope(SCOPE_ID)).thenReturn(client);
        var spec = mock(RequestSpec.class);
        when(client.document(anyString())).thenReturn(spec);
        when(spec.execute()).thenAnswer(invocation -> {
            askedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Mono.just(response);
        });
        when(response.isValid()).thenReturn(true);
        service = new CommitMetadataEnrichmentService(
                commits,
                context.getBean(CommitContributorRepository.class),
                clients,
                context.getBean(GitHubGraphQlSyncCoordinator.class),
                context.getBean(GitHubExceptionClassifier.class),
                new TransactionTemplate(transactionManager));
    }

    /** A pushed commit is enriched with no transaction open while GitHub answers, and its rows are stored. */
    @Test
    void shouldAskGitHubWithNoTransactionOpenWhenAPushedCommitIsEnriched() {
        String sha = sha();
        long commitId = commit(sha);
        answer(0, commitData(sha, "VALID"));
        var listener = transactional(new CommitEnrichmentEventListener(service));

        listener.onCommitCreated(pushed(commitId, sha));

        assertThat(askedInTransaction).containsExactly(false);
        assertThat(authors(commitId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT additions FROM git_commit WHERE id = ?", Integer.class, commitId))
                .isEqualTo(10);
    }

    /**
     * A commit whose write fails keeps no contributor row, so the next run enriches it again. The commit before it
     * in the same batch is stored whole.
     */
    @Test
    void shouldLeaveACommitForTheNextRunWhenItsWriteFails() {
        String stored = sha();
        String refused = sha();
        long storedId = commit(stored);
        long refusedId = commit(refused);
        answer(0, commitData(stored, "VALID"));
        // A signature state longer than its column: the metadata update of this commit fails.
        answer(1, commitData(refused, "X".repeat(40)));

        service.enrichCommitMetadata(repository.getId(), repository.getNameWithOwner(), SCOPE_ID);

        assertThat(authors(storedId)).isEqualTo(1);
        assertThat(authors(refusedId))
                .as("the author row rolled back with the failed metadata update")
                .isZero();
        assertThat(commits.findShasWithoutContributorsByRepositoryId(repository.getId()))
                .containsExactly(refused);
    }

    /** Applies the listener's own {@code @Transactional}, as its Spring proxy does. */
    private CommitEnrichmentEventListener transactional(CommitEnrichmentEventListener listener) {
        TransactionManager manager = transactionManager;
        var factory = new ProxyFactory(listener);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (CommitEnrichmentEventListener) factory.getProxy();
    }

    private ScmDomainEvent.CommitCreated pushed(long commitId, String sha) {
        var ref = new RepositoryRef(repository.getId(), repository.getNameWithOwner(), "main");
        var eventContext = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                SCOPE_ID,
                ref,
                DataSource.WEBHOOK,
                "push",
                UUID.randomUUID().toString(),
                IdentityProviderType.GITHUB,
                null);
        var data = new ScmEventPayload.CommitData(
                commitId, sha, "Work", Instant.now(), 0, 0, 0, null, null, null, repository.getId());
        return new ScmDomainEvent.CommitCreated(data, eventContext);
    }

    @SuppressWarnings("unchecked")
    private void answer(int index, Map<String, Object> commitData) {
        var field = mock(ClientResponseField.class);
        when(field.getValue()).thenReturn(commitData);
        when(field.toEntity(any(ParameterizedTypeReference.class))).thenReturn(commitData);
        when(response.field("repository.commit" + index)).thenReturn(field);
    }

    private static Map<String, Object> commitData(String sha, String signatureState) {
        return Map.ofEntries(
                Map.entry("oid", sha),
                Map.entry("additions", 10),
                Map.entry("deletions", 5),
                Map.entry("messageHeadline", "feat: add feature"),
                Map.entry("signature", Map.of("isValid", true, "state", signatureState)),
                Map.entry(
                        "authors",
                        Map.of("nodes", List.of(Map.of("name", "Test Author", "email", "author@example.com")))),
                Map.entry("associatedPullRequests", Map.of("nodes", List.of())));
    }

    private long commit(String sha) {
        Instant now = Instant.now();
        commits.upsertCommit(
                sha,
                "Work",
                null,
                repository.getHtmlUrl() + "/commit/" + sha,
                now,
                now,
                0,
                0,
                0,
                now,
                repository.getId(),
                null,
                null,
                "author@example.com",
                "author@example.com",
                null,
                false);
        return Objects.requireNonNull(commits.findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId());
    }

    private int authors(long commitId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM commit_contributor WHERE commit_id = ? AND role = 'AUTHOR'",
                Integer.class,
                commitId));
    }

    private static String sha() {
        byte[] bytes = new byte[20];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
