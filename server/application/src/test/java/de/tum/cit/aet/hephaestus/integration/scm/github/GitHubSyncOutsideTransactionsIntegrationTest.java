package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlConfig;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.GitHubLabelProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.GitHubLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.GitHubMilestoneProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.GitHubMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.GitHubRepositorySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator.GitHubCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * A repository sync asks GitHub with no transaction open, then writes what it received. A transaction open across the
 * request would hold a pooled connection and its row locks for as long as GitHub and the rate-limit waits take.
 */
@DisplayName("GitHub sync outside transactions")
class GitHubSyncOutsideTransactionsIntegrationTest extends BaseIntegrationTest {

    private static final String RATE_LIMIT =
            "\"rateLimit\":{\"cost\":1,\"limit\":5000,\"remaining\":4999,\"resetAt\":\"2030-01-01T00:00:00Z\"}";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    // A plain mock, not a @MockitoBean: the services below are built over it, so the shared context stays unchanged.
    private final GitHubGraphQlClientProvider clients = mock(GitHubGraphQlClientProvider.class);

    /** Each request to GitHub: the thread that sent it, and whether that thread had a transaction open. */
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private record Request(String thread, boolean inTransaction) {}

    /** GitHub's answers, in the order the sync asks. */
    private final Queue<String> answers = new ConcurrentLinkedQueue<>();

    private long workspaceId;
    private long repositoryId;

    @BeforeEach
    void repositoryAndGitHub() {
        String unique = UUID.randomUUID().toString();
        long installation = (unique.hashCode() & 0x7fffffffL) + 1L;
        workspaceId = Objects.requireNonNull(WorkspaceTestFixtures.persistInstallationWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.installationWorkspace(installation, "outside-" + unique),
                        installation)
                .getId());
        long providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() ->
                        providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")))
                .getId());
        long nativeId = 2_000_000L + (unique.hashCode() & 0xfffffL) * 1_000L;
        repositoryId =
                Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,html_url,pushed_at,visibility)
                VALUES (?,?,'repo',?,false,false,false,'https://example.com/repo',now(),'PUBLIC') RETURNING id
                """, Long.class, nativeId, providerId, "outside/" + unique));

        // A filter answers from the script, so no request leaves the test. The WebClient has no exchange function of
        // its
        // own: with one, the GraphQL client would decode with default codecs instead of GitHub's mapper.
        WebClient github = WebClient.builder()
                .baseUrl("https://api.github.com/graphql")
                .filter((request, next) -> {
                    requests.add(new Request(
                            Thread.currentThread().getName(),
                            TransactionSynchronizationManager.isActualTransactionActive()));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(Objects.requireNonNull(answers.poll(), "GitHub was asked more than the test answers"))
                            .build());
                })
                .build();
        JsonMapper mapper = GitHubGraphQlConfig.gitHubGraphQlObjectMapper(context.getBean(JsonMapper.class));
        HttpGraphQlClient client = HttpGraphQlClient.builder(github)
                .codecConfigurer(codecs -> {
                    codecs.customCodecs().register(new JacksonJsonEncoder(mapper));
                    codecs.customCodecs().register(new JacksonJsonDecoder(mapper));
                })
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/github/operations/"),
                                new ClassPathResource("graphql/github/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(
                                new ClassPathResource(GitHubGraphQlFragments.ACTOR_FRAGMENTS_RESOURCE),
                                new ClassPathResource(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE))))
                .build();
        when(clients.forScope(workspaceId)).thenReturn(client);
        lenient().when(clients.getRateLimitRemaining(workspaceId)).thenReturn(5000);
    }

    @Test
    void shouldAskGitHubWithNoTransactionOpenAndStoreTheRepository() {
        long nativeId = 4_000_000L + repositoryId;
        String name = "outside-org-" + repositoryId + "/fresh";
        serve("""
                {"data":{%s,"repository":{"databaseId":%d,"name":"fresh","nameWithOwner":"%s",
                "url":"https://github.com/%s","isPrivate":false,"isArchived":false,"isDisabled":false,
                "owner":{"__typename":"Organization","databaseId":%d,"login":"outside-org-%d"}}}}
                """.formatted(RATE_LIMIT, nativeId, name, name, 5_000_000L + repositoryId, repositoryId));

        var synced = repositories()
                .syncRepository(
                        workspaceId,
                        name,
                        providers
                                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                                .orElseThrow(),
                        nativeId);

        assertThat(synced).isPresent();
        assertThat(requests).containsExactly(new Request(Thread.currentThread().getName(), false));
        assertThat(jdbc.queryForObject("""
                        SELECT o.login FROM repository r JOIN organization o ON o.id = r.organization_id
                        WHERE r.native_id = ? AND r.name_with_owner = ?
                        """, String.class, nativeId, name)).isEqualTo("outside-org-" + repositoryId);
    }

    @Test
    void shouldAskGitHubWithNoTransactionOpenAndStoreTheMilestones() {
        serve("""
                {"data":{%s,"repository":{"milestones":{"pageInfo":{"hasNextPage":false,"endCursor":null},"totalCount":1,
                "nodes":[{"id":"MI_7","number":7,"title":"Release 1.0","description":null,"state":"OPEN","dueOn":null,
                "closedAt":null,"createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-01-02T00:00:00Z",
                "url":"https://github.com/outside/repo/milestone/7","progressPercentage":0,"openIssueCount":0,
                "closedIssueCount":0,"creator":null}]}}}}
                """.formatted(RATE_LIMIT));

        int synced = milestones().syncMilestonesForRepository(workspaceId, repositoryId);

        assertThat(synced).isEqualTo(1);
        assertThat(requests).containsExactly(new Request(Thread.currentThread().getName(), false));
        assertThat(jdbc.queryForObject(
                        "SELECT title FROM milestone WHERE repository_id = ? AND number = 7",
                        String.class,
                        repositoryId))
                .isEqualTo("Release 1.0");
    }

    @Test
    void shouldAskGitHubWithNoTransactionOpenAndStoreTheLabels() {
        serve("""
                {"data":{%s,"repository":{"labels":{"pageInfo":{"hasNextPage":false,"endCursor":null},"totalCount":1,
                "nodes":[{"id":"LA_1","name":"bug","description":"Something is wrong","color":"d73a4a",
                "createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-01-02T00:00:00Z"}]}}}}
                """.formatted(RATE_LIMIT));

        int synced = labels().syncLabelsForRepository(workspaceId, repositoryId);

        assertThat(synced).isEqualTo(1);
        assertThat(requests).containsExactly(new Request(Thread.currentThread().getName(), false));
        assertThat(jdbc.queryForObject(
                        "SELECT color FROM label WHERE repository_id = ? AND name = 'bug'", String.class, repositoryId))
                .isEqualTo("d73a4a");
    }

    @Test
    void shouldAskGitHubWithNoTransactionOpenAndStoreTheCollaborators() {
        String login = "collaborator-" + repositoryId;
        serve("""
                {"data":{%s,"repository":{"collaborators":{"pageInfo":{"hasNextPage":false,"endCursor":null},
                "totalCount":1,"edges":[{"permission":"WRITE","node":{"id":"U_%d","databaseId":%d,"login":"%s",
                "avatarUrl":"https://avatars.example.com/u","name":"A Collaborator","email":null,
                "createdAt":"2025-01-01T00:00:00Z","updatedAt":"2025-01-02T00:00:00Z"}}]}}}}
                """.formatted(RATE_LIMIT, repositoryId, 9_000_000L + repositoryId, login));

        int synced = collaborators().syncCollaboratorsForRepository(workspaceId, repositoryId);

        assertThat(synced).isEqualTo(1);
        assertThat(requests).containsExactly(new Request(Thread.currentThread().getName(), false));
        assertThat(jdbc.queryForObject("""
                        SELECT rc.permission FROM repository_collaborator rc JOIN "user" u ON u.id = rc.user_id
                        WHERE rc.repository_id = ? AND u.login = ?
                        """, String.class, repositoryId, login)).isEqualTo("WRITE");
    }

    @Test
    void shouldAskGitHubWithNoTransactionOpenAndStoreTheIssueTypes() {
        Organization organization = organization();
        serve(issueTypePage(false, "IT_bug-" + organization.getId(), "Bug"));

        int synced = issueTypes(organization).syncIssueTypesForScope(workspaceId);

        assertThat(synced).isEqualTo(1);
        assertThat(requests).containsExactly(new Request(Thread.currentThread().getName(), false));
        assertThat(jdbc.queryForObject(
                        "SELECT name FROM issue_type WHERE id = ?", String.class, "IT_bug-" + organization.getId()))
                .isEqualTo("Bug");
    }

    @Test
    void shouldKeepTheIssueTypesThatAnAbortedListingDidNotReach() {
        Organization organization = organization();
        String unreached = "IT_task-" + organization.getId();
        jdbc.update(
                "INSERT INTO issue_type(id,name,color,is_enabled,organization_id,last_sync_at) VALUES (?,?,?,true,?,now())",
                unreached,
                "Task",
                "BLUE",
                organization.getId());
        serve(issueTypePage(true, "IT_bug-" + organization.getId(), "Bug"));
        serve("{\"errors\":[{\"message\":\"Something went wrong\"}],\"data\":null}");

        issueTypes(organization).syncIssueTypesForScope(workspaceId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM issue_type WHERE id = ?", Integer.class, unreached))
                .isEqualTo(1);
    }

    private static String issueTypePage(boolean more, String id, String name) {
        return """
                {"data":{%s,"organization":{"issueTypes":{"pageInfo":{"hasNextPage":%s,"endCursor":"c1"},"totalCount":2,
                "nodes":[{"id":"%s","name":"%s","description":null,"color":"RED","isEnabled":true}]}}}}
                """.formatted(RATE_LIMIT, more, id, name);
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setNativeId(3_000_000L + repositoryId);
        organization.setLogin("outside-org-" + repositoryId);
        organization.setHtmlUrl("https://github.com/outside-org-" + repositoryId);
        organization.setProvider(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow());
        return context.getBean(OrganizationRepository.class).save(organization);
    }

    private void serve(String body) {
        answers.add(body);
    }

    private GitHubRepositorySyncService repositories() {
        return transactional(
                GitHubRepositorySyncService.class,
                new GitHubRepositorySyncService(
                        clients,
                        context.getBean(RepositoryRepository.class),
                        context.getBean(OrganizationRepository.class),
                        context.getBean(GitHubSyncProperties.class),
                        context.getBean(GitHubExceptionClassifier.class),
                        context.getBean(GitHubGraphQlSyncCoordinator.class),
                        context.getBean("gitHubGraphQlWebClient", WebClient.class),
                        context.getBean(TransactionTemplate.class)));
    }

    private GitHubMilestoneSyncService milestones() {
        return transactional(
                GitHubMilestoneSyncService.class,
                new GitHubMilestoneSyncService(
                        context.getBean(MilestoneRepository.class),
                        context.getBean(RepositoryRepository.class),
                        clients,
                        context.getBean(GitHubMilestoneProcessor.class),
                        context.getBean(GitHubSyncProperties.class),
                        context.getBean(GitHubExceptionClassifier.class),
                        context.getBean(GitHubGraphQlSyncCoordinator.class),
                        context.getBean(TransactionTemplate.class)));
    }

    private GitHubLabelSyncService labels() {
        return transactional(
                GitHubLabelSyncService.class,
                new GitHubLabelSyncService(
                        context.getBean(RepositoryRepository.class),
                        clients,
                        context.getBean(GitHubLabelProcessor.class),
                        context.getBean(LabelRepository.class),
                        context.getBean(GitHubSyncProperties.class),
                        context.getBean(GitHubExceptionClassifier.class),
                        new GraphQlPaginationHelper(clients, context.getBean(GitHubGraphQlSyncCoordinator.class))));
    }

    private GitHubCollaboratorSyncService collaborators() {
        return transactional(
                GitHubCollaboratorSyncService.class,
                new GitHubCollaboratorSyncService(
                        context.getBean(RepositoryRepository.class),
                        context.getBean(RepositoryCollaboratorRepository.class),
                        clients,
                        context.getBean(GitHubUserProcessor.class),
                        context.getBean(GitHubSyncProperties.class),
                        context.getBean(GitHubExceptionClassifier.class),
                        context.getBean(GitHubGraphQlSyncCoordinator.class),
                        context.getBean(TransactionTemplate.class)));
    }

    private GitHubIssueTypeSyncService issueTypes(Organization organization) {
        SyncTargetProvider scopes = mock(SyncTargetProvider.class);
        when(scopes.getSyncMetadata(workspaceId))
                .thenReturn(Optional.of(new SyncTargetProvider.SyncMetadata(
                        workspaceId, "outside", organization.getLogin(), organization.getId(), null, null, null)));
        return transactional(
                GitHubIssueTypeSyncService.class,
                new GitHubIssueTypeSyncService(
                        context.getBean(IssueTypeRepository.class),
                        context.getBean(OrganizationRepository.class),
                        scopes,
                        clients,
                        context.getBean(GitHubSyncProperties.class),
                        context.getBean(GitHubExceptionClassifier.class),
                        context.getBean(SyncSchedulerProperties.class),
                        context.getBean(GitHubGraphQlSyncCoordinator.class),
                        context.getBean(TransactionTemplate.class)));
    }

    /** The service as Spring proxies it, so its own transaction annotations apply. */
    private <T> T transactional(Class<T> type, T service) {
        TransactionManager manager = transactionManager;
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }
}
