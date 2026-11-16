package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect.GitLabResourceAdapter;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The project hooks of a GitLab connection on a tier without group hooks, through the real adapter and PostgreSQL
 * coordination, with GitLab's project hook API faked in memory.
 */
class GitLabProjectHookRepairIntegrationTest extends BaseIntegrationTest {
    private static final PublicApiUrls URLS =
            new PublicApiUrls(AuthPropertiesFixture.withIssuer(URI.create("https://receiver.example.test")));
    private static final String ORIGIN = "https://gitlab.example.test";
    private static final String MARKER = "marker";
    private static final String URL =
            "https://receiver.example.test/webhooks/gitlab/connections/1/key/route?hephaestus_resource=" + MARKER;
    private static final String HASH = ProviderResourceAdapter.fingerprint(URL);

    @Autowired
    private ProviderResourceRepairRepository rows;

    @Autowired
    private ConnectionProviderRepairRepository references;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private CredentialReader credentials;

    @Autowired
    private PlatformTransactionManager manager;

    @PersistenceContext
    private @Nullable EntityManager entities;

    /** GitLab's hooks by project, each a map from hook id to URL. */
    private final Map<Long, Map<Long, String>> gitlab = new ConcurrentHashMap<>();

    private final List<UUID> created = new ArrayList<>();
    private ProviderResourceRepairs repairs;
    private ProviderResourceRepairDispatcher dispatcher;
    private TransactionTemplate transactions;
    private long groupId;

    @BeforeEach
    void setUp() {
        groupId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 1;
        GitLabWebhookClient client = mock(GitLabWebhookClient.class);
        when(client.listProjectWebhooksWithCredentials(anyString(), anyString(), anyLong()))
                .thenAnswer(call -> gitlab.getOrDefault((long) call.getArgument(2), Map.of()).entrySet().stream()
                        .map(hook -> new WebhookInfo(hook.getKey(), hook.getValue()))
                        .toList());
        when(client.deregisterProjectWebhookWithCredentials(anyString(), anyString(), anyLong(), anyLong()))
                .thenAnswer(call -> {
                    Map<Long, String> hooks = gitlab.get((long) call.getArgument(2));
                    return hooks != null && hooks.remove((long) call.getArgument(3)) != null;
                });
        var clients = new StaticListableBeanFactory();
        clients.addBean("client", client);
        var adapters = new StaticListableBeanFactory();
        adapters.addBean("gitlab", new GitLabResourceAdapter(clients.getBeanProvider(GitLabWebhookClient.class), URLS));

        ProviderResourceRepairs coordination = new ProviderResourceRepairs(
                rows,
                references,
                connections,
                credentials,
                adapters.getBeanProvider(ProviderResourceAdapter.class),
                event -> {},
                Clock.systemUTC());
        ReflectionTestUtils.setField(coordination, "entityManager", Objects.requireNonNull(entities));
        var transactionsAdvice = new TransactionInterceptor();
        transactionsAdvice.setTransactionManager(manager);
        transactionsAdvice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(coordination);
        proxy.addAdvice(transactionsAdvice);
        repairs = (ProviderResourceRepairs) proxy.getProxy();
        dispatcher = new ProviderResourceRepairDispatcher(
                rows, references, repairs, manager, Clock.systemUTC(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(dispatcher, "entityManager", Objects.requireNonNull(entities));
        transactions = new TransactionTemplate(manager);
    }

    @AfterEach
    void removeOwnFences() {
        transactions.executeWithoutResult(status -> {
            for (UUID id : created) {
                references.deleteAll(references.findByRepair_Id(id));
                rows.deleteById(id);
            }
        });
    }

    private Connection owner(Map<Long, Long> hooks) {
        var config = new ConnectionConfig.GitLabConfig(ORIGIN, groupId, null, Set.of(), null);
        for (var hook : hooks.entrySet()) {
            config = config.withProjectHook(hook.getKey(), hook.getValue(), HASH, MARKER);
            gitlab.computeIfAbsent(hook.getKey(), ignored -> new ConcurrentHashMap<>())
                    .put(hook.getValue(), URL);
        }
        var stored = config;
        return Objects.requireNonNull(transactions.execute(status -> {
            var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                    "project-hooks-" + UUID.randomUUID().toString().substring(0, 8)));
            Connection connection = new Connection(workspace, IntegrationKind.GITLAB, Long.toString(groupId), stored);
            connection.setCredentials(new BearerToken("project-hook-token", null), converter);
            ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
            return connections.saveAndFlush(connection);
        }));
    }

    /** A captured removal, read inside its transaction: the repair behind a reference is a lazy proxy. */
    private record Captured(UUID id, Resource resource) {}

    private List<Captured> repairsOf(Connection owner) {
        var found = Objects.requireNonNull(transactions.execute(status ->
                references
                        .findByWorkspaceIdAndConnection_Id(owner.getWorkspace().getId(), owner.getId())
                        .stream()
                        .map(ConnectionProviderRepair::getRepair)
                        .map(repair -> new Captured(repair.getId(), repair.resource()))
                        .toList()));
        found.forEach(repair -> created.add(repair.id()));
        return found;
    }

    private ConnectionConfig.GitLabConfig configOf(Connection owner) {
        return (ConnectionConfig.GitLabConfig) connections
                .findByIdAndWorkspaceId(owner.getId(), owner.getWorkspace().getId())
                .orElseThrow()
                .getConfig();
    }

    private Resource projectHook(long projectId, long hookId) {
        return new Resource(
                IntegrationKind.GITLAB,
                ORIGIN,
                ResourceType.PROJECT_HOOK,
                GitLabResourceAdapter.projectParent(groupId, projectId),
                Long.toString(hookId),
                HASH);
    }

    @Test
    void shouldRemoveTheHookOfEveryProjectOnDisconnect() {
        Connection owner = owner(Map.of(7L, 40L, 8L, 41L));

        transactions.executeWithoutResult(status -> repairs.requestRemoval(
                connections
                        .findByIdAndWorkspaceId(
                                owner.getId(), owner.getWorkspace().getId())
                        .orElseThrow(),
                false));
        List<Captured> pending = repairsOf(owner);
        pending.forEach(repair -> dispatcher.dispatch(repair.id()));

        assertThat(pending)
                .extracting(repair -> repair.resource().parentId())
                .containsExactlyInAnyOrder(groupId + "/7", groupId + "/8");
        assertThat(gitlab.get(7L)).isEmpty();
        assertThat(gitlab.get(8L)).isEmpty();
        assertThat(pending)
                .allSatisfy(repair -> assertThat(rows.existsById(repair.id())).isFalse());
    }

    @Test
    void shouldReleaseOnlyTheHookOfAProjectThatIsNoLongerMonitored() {
        Connection owner = owner(Map.of(7L, 40L, 8L, 41L));

        repairs.releaseHooks(
                owner.getWorkspace().getId(),
                owner.getId(),
                List.of(projectHook(7L, 40L)),
                config -> ((ConnectionConfig.GitLabConfig) config).withoutProjectHooks(Set.of(7L)));
        List<Captured> pending = repairsOf(owner);
        pending.forEach(repair -> dispatcher.dispatch(repair.id()));

        assertThat(pending)
                .singleElement()
                .satisfies(repair -> assertThat(repair.resource().resourceId()).isEqualTo("40"));
        assertThat(gitlab.get(7L)).isEmpty();
        assertThat(gitlab.get(8L)).containsOnlyKeys(41L);
        assertThat(configOf(owner).projectHooks()).containsExactly(new ConnectionConfig.GitLabProjectHook(8L, 41L));
        assertThat(configOf(owner).webhookOwnershipHash()).isEqualTo(HASH);
    }

    @Test
    void shouldKeepTheHooksAnActiveConnectionHoldsWhenARegistrationIsSuperseded() {
        Connection owner = owner(Map.of(7L, 40L));
        Resource registration = new Resource(
                IntegrationKind.GITLAB,
                ORIGIN,
                ResourceType.REGISTRATION,
                GitLabResourceAdapter.projectParent(groupId, 8L),
                MARKER,
                HASH);
        UUID intent = repairs.beginRegistration(owner.getWorkspace().getId(), owner.getId(), registration);
        created.add(intent);
        gitlab.computeIfAbsent(8L, ignored -> new ConcurrentHashMap<>()).put(51L, URL);
        // A concurrent change to the connection supersedes the registration.
        transactions.executeWithoutResult(status -> {
            Connection current = connections
                    .findByIdAndWorkspaceId(owner.getId(), owner.getWorkspace().getId())
                    .orElseThrow();
            current.setConfig(((ConnectionConfig.GitLabConfig) current.getConfig())
                    .withProjectExclusions(new ConnectionConfig.GitLabProjectExclusions(Set.of(999L), Map.of())));
            connections.saveAndFlush(current);
        });

        boolean adopted = repairs.completeRegistration(
                owner.getWorkspace().getId(),
                owner.getId(),
                intent,
                config -> ((ConnectionConfig.GitLabConfig) config).withProjectHook(8L, 51L, HASH, MARKER));

        assertThat(adopted).isFalse();
        assertThat(repairsOf(owner))
                .singleElement()
                .satisfies(repair -> assertThat(repair.resource()).isEqualTo(projectHook(8L, 51L)));
        assertThat(configOf(owner).projectHooks()).containsExactly(new ConnectionConfig.GitLabProjectHook(7L, 40L));
    }
}
