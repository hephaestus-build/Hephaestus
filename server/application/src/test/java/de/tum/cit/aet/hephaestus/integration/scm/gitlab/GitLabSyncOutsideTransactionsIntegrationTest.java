package de.tum.cit.aet.hephaestus.integration.scm.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuetype.IssueTypeRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.List;
import java.util.Objects;
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

/**
 * A GitLab sync asks GitLab with no transaction open, then writes what it received. A transaction open across the
 * request would hold a pooled connection, also while the sync waits for GitLab's rate limit.
 */
@DisplayName("GitLab sync outside transactions")
class GitLabSyncOutsideTransactionsIntegrationTest extends BaseIntegrationTest {

    private static final String SERVER = "https://gitlab.outside.example.test";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private JdbcTemplate jdbc;

    // A plain mock, not a @MockitoBean: the service below is built over it, so the shared context stays unchanged.
    private final GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);

    /** Each request to GitLab: the thread that sent it, and whether that thread had a transaction open. */
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** GitLab's answers, in the order the sync asks. */
    private final Queue<String> answers = new ConcurrentLinkedQueue<>();

    private record Request(String thread, boolean inTransaction) {}

    private final long scopeId = (UUID.randomUUID().hashCode() & 0x7fffffffL) + 1L;
    private Organization group = new Organization();

    @BeforeEach
    void groupAndGitLab() {
        String unique = UUID.randomUUID().toString();
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, SERVER)));
        var organization = new Organization();
        organization.setNativeId(6_000_000L + (unique.hashCode() & 0xfffffL));
        organization.setLogin("outside-group-" + unique);
        organization.setHtmlUrl(SERVER + "/outside-group-" + unique);
        organization.setProvider(provider);
        group = organizations.saveAndFlush(organization);

        // A filter answers from the script, so no request leaves the test.
        WebClient gitlab = WebClient.builder()
                .baseUrl(SERVER + "/api/graphql")
                .filter((request, next) -> {
                    requests.add(new Request(
                            Thread.currentThread().getName(),
                            TransactionSynchronizationManager.isActualTransactionActive()));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(Objects.requireNonNull(answers.poll(), "GitLab was asked more than the test answers"))
                            .build());
                })
                .build();
        HttpGraphQlClient client = HttpGraphQlClient.builder(gitlab)
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/gitlab/operations/"),
                                new ClassPathResource("graphql/gitlab/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"))))
                .build();
        when(clients.forScope(scopeId)).thenReturn(client);
    }

    @Test
    void shouldAskGitLabWithNoTransactionOpenAndStoreTheIssueTypes() {
        String listed = "gid://gitlab/WorkItems::Type/listed-" + group.getId();
        String gone = "gid://gitlab/WorkItems::Type/gone-" + group.getId();
        jdbc.update(
                "INSERT INTO issue_type(id,name,color,is_enabled,organization_id,last_sync_at) VALUES (?,?,?,true,?,now())",
                gone,
                "Requirement",
                "GRAY",
                group.getId());
        answers.add("""
                {"data":{"group":{"workItemTypes":{"nodes":[{"id":"%s","name":"Task","iconName":"issue-type-task"}]}}}}
                """.formatted(listed));

        int synced = issueTypes().syncIssueTypesForGroup(scopeId, group.getLogin());

        assertThat(synced).isEqualTo(1);
        assertThat(requests).containsExactly(new Request(Thread.currentThread().getName(), false));
        assertThat(jdbc.queryForObject("SELECT is_enabled FROM issue_type WHERE id = ?", Boolean.class, listed))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT is_enabled FROM issue_type WHERE id = ?", Boolean.class, gone))
                .isFalse();
    }

    private GitLabIssueTypeSyncService issueTypes() {
        return transactional(
                GitLabIssueTypeSyncService.class,
                new GitLabIssueTypeSyncService(
                        context.getBean(IssueTypeRepository.class),
                        organizations,
                        clients,
                        context.getBean(GitLabGraphQlResponseHandler.class),
                        context.getBean(GitLabProperties.class),
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
