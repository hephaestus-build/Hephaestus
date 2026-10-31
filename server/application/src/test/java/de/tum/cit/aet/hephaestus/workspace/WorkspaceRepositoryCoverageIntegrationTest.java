package de.tum.cit.aet.hephaestus.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryAlreadyMonitoredException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryManagementNotAllowedException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryProviderNotConnectedException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class WorkspaceRepositoryCoverageIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    private static final long INSTALLATION_ID = 9912345L;

    @Autowired
    private WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private StubInstallationRepositoryEnumerator repositoryEnumerator;

    @Autowired
    private GitHubAppTokenService gitHubAppTokenService;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private WorkspaceSyncTargetProvider workspaceSyncTargetProvider;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ScmWorkspaceContentEraser scmWorkspaceContentEraser;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ReviewScopeRepositoryRepository reviewScopeRepositoryRepository;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    /** GitHub itself is not reachable here; a token workspace's Add asks it with the token its connection stores. */
    @MockitoBean
    private GitHubGraphQlClientProvider gitHubClients;

    private static final String PAT = "synthetic-pat-token";

    private static final String SHARED_NAME = "shared-org/shared-repo";
    private static final String GITLAB_SERVER_URL = "https://gitlab.example.com";

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        repositoryEnumerator.reset();
        // Ensure GitHub IdentityProvider exists - required by
        // WorkspaceRepositoryMonitorService.ensureRepositoryFromSnapshot
        if (gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .isEmpty()) {
            gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com"));
        }
        // Clear any suspended installation state from previous tests
        // This prevents test pollution when async syncs mark installations as suspended
        gitHubAppTokenService.markInstallationActive(INSTALLATION_ID);
        // GitHub confirms the shared repository, at its mirror's id, to the token a token workspace stores.
        when(gitHubClients.withToken(PAT))
                .thenReturn(HttpGraphQlClient.builder(WebClient.builder()
                                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                        .body("""
                                        {"data":{"repository":{"databaseId":9001,"nameWithOwner":"%s"}}}
                                        """.formatted(SHARED_NAME))
                                        .build())))
                        .url("https://api.github.com/graphql")
                        .documentSource(new FragmentMergingDocumentSource(
                                List.of(
                                        new ClassPathResource("graphql/github/operations/"),
                                        new ClassPathResource("graphql/github/fragments/")),
                                List.of(".graphql", ".gql"),
                                List.of(
                                        new ClassPathResource(GitHubGraphQlFragments.ACTOR_FRAGMENTS_RESOURCE),
                                        new ClassPathResource(
                                                GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE))))
                        .build());
    }

    @Test
    void shouldReconcileMonitorsForInstallation() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        repositoryToMonitorRepository.save(buildMonitor(workspace, "HephaestusTest/Orphaned"));

        repositoryEnumerator.returns(List.of(
                snapshot(1L, "HephaestusTest/demo-repository", "demo-repository", true),
                snapshot(2L, "HephaestusTest/payload-fixture-repo-renamed", "payload-fixture-repo-renamed", false)));

        // Use deferSync=true to prevent async syncs from running during test
        // Async syncs can mark installations as suspended on 403 errors, affecting subsequent calls
        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
        assertThat(monitors)
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactlyInAnyOrder(
                        "HephaestusTest/demo-repository", "HephaestusTest/payload-fixture-repo-renamed");
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/demo-repository"))
                .isPresent();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/payload-fixture-repo-renamed"))
                .isPresent();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/Orphaned"))
                .isEmpty();
    }

    @Test
    void shouldRespectSelectedInstallations() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);

        repositoryEnumerator.returns(List.of(snapshot(3L, "HephaestusTest/HelloWorld", "HelloWorld", true)));

        // Use deferSync=true to prevent async syncs from running during test
        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        List<RepositoryToMonitor> monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
        assertThat(monitors)
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactly("HephaestusTest/HelloWorld");
    }

    @Test
    void caseChangedRepositoryKeepsItsMonitorAndMirrorAcrossReconciliations() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        Repository mirror = persistGitHubRepository(77L, "HephaestusTest/explainer");
        RepositoryToMonitor monitor = monitorWithScope(workspace, "HephaestusTest/explainer", 77L);
        repositoryEnumerator.returns(List.of(snapshot(77L, "HephaestusTest/explAIner", "explAIner", false)));

        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);
        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        assertRenamedInPlace(workspace, monitor, mirror, "HephaestusTest/explAIner");
    }

    @Test
    void renamedRepositoryKeepsItsMonitorMirrorAndReviewScope() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        Repository mirror = persistGitHubRepository(78L, "HephaestusTest/old-name");
        RepositoryToMonitor monitor = monitorWithScope(workspace, "HephaestusTest/old-name", 78L);
        repositoryEnumerator.returns(List.of(snapshot(78L, "HephaestusTest/new-name", "new-name", false)));

        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        assertRenamedInPlace(workspace, monitor, mirror, "HephaestusTest/new-name");
    }

    @Test
    void monitorWithoutAnIdAdoptsTheListedRepositoryAtItsPath() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        RepositoryToMonitor monitor =
                repositoryToMonitorRepository.saveAndFlush(buildMonitor(workspace, "HephaestusTest/Legacy-Case"));
        repositoryEnumerator.returns(List.of(snapshot(79L, "HephaestusTest/legacy-case", "legacy-case", false)));

        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .singleElement()
                .satisfies(adopted -> {
                    assertThat(adopted.getId()).isEqualTo(monitor.getId());
                    assertThat(adopted.getNativeId()).isEqualTo(79L);
                    assertThat(adopted.getNameWithOwner()).isEqualTo("HephaestusTest/legacy-case");
                });
    }

    @Test
    void pathMirroredForAnotherRepositoryIsNeitherTakenOverNorMonitored() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        Repository holder = persistGitHubRepository(85L, "HephaestusTest/taken");
        repositoryEnumerator.returns(List.of(
                snapshot(86L, "HephaestusTest/taken", "taken", false),
                snapshot(87L, "HephaestusTest/free", "free", false)));

        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .extracting(RepositoryToMonitor::getNativeId)
                .containsExactly(87L);
        assertThat(repositoryRepository.findById(holder.getId())).hasValueSatisfying(repository -> {
            assertThat(repository.getNativeId()).isEqualTo(85L);
            assertThat(repository.getNameWithOwner()).isEqualTo("HephaestusTest/taken");
        });
    }

    @Test
    void reusedPathReplacesTheMonitorOfTheRepositoryThatLeftIt() {
        Workspace workspace = persistWorkspace(RepositorySelection.ALL);
        Repository departed = persistGitHubRepository(80L, "HephaestusTest/reused");
        RepositoryToMonitor previous = buildMonitor(workspace, "HephaestusTest/reused");
        previous.setNativeId(80L);
        previous = repositoryToMonitorRepository.saveAndFlush(previous);
        repositoryEnumerator.returns(List.of(snapshot(81L, "HephaestusTest/reused", "reused", false)));

        workspaceRepositoryMonitorService.ensureAllInstallationRepositoriesCovered(INSTALLATION_ID, null, true);

        assertThat(repositoryToMonitorRepository.findById(previous.getId())).isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .singleElement()
                .satisfies(current -> {
                    assertThat(current.getNameWithOwner()).isEqualTo("HephaestusTest/reused");
                    assertThat(current.getNativeId()).isEqualTo(81L);
                });
        assertThat(repositoryRepository.findById(departed.getId())).isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/reused"))
                .hasValueSatisfying(current -> assertThat(current.getNativeId()).isEqualTo(81L));
    }

    @Test
    void repositoryRemovalNamesTheMonitorByItsId() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);
        RepositoryToMonitor renamedAway = buildMonitor(workspace, "HephaestusTest/stale-name");
        renamedAway.setNativeId(82L);
        renamedAway = repositoryToMonitorRepository.saveAndFlush(renamedAway);
        RepositoryToMonitor reusedPath = buildMonitor(workspace, "HephaestusTest/shared-path");
        reusedPath.setNativeId(83L);
        reusedPath = repositoryToMonitorRepository.saveAndFlush(reusedPath);

        workspaceRepositoryMonitorService.removeRepositoryMonitorForInstallation(
                INSTALLATION_ID, "HephaestusTest/current-name", 82L);
        workspaceRepositoryMonitorService.removeRepositoryMonitorForInstallation(
                INSTALLATION_ID, "HephaestusTest/shared-path", 84L);

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .extracting(RepositoryToMonitor::getId)
                .containsExactly(reusedPath.getId());
        assertThat(repositoryToMonitorRepository.findById(renamedAway.getId())).isEmpty();
    }

    @Test
    void identityReconciliationDoesNotTakeAnIdOrPathAnotherMonitorHolds() {
        Workspace workspace = persistGitHubTokenWorkspace();
        RepositoryToMonitor holder = buildMonitor(workspace, "acme/held");
        holder.setNativeId(90L);
        holder = repositoryToMonitorRepository.saveAndFlush(holder);
        RepositoryToMonitor other = buildMonitor(workspace, "acme/other");
        other.setNativeId(91L);
        other = repositoryToMonitorRepository.saveAndFlush(other);
        RepositoryToMonitor unresolved =
                repositoryToMonitorRepository.saveAndFlush(buildMonitor(workspace, "acme/new"));

        workspaceSyncTargetProvider.reconcileSyncTargetIdentity(other.getId(), 91L, "acme/held");
        workspaceSyncTargetProvider.reconcileSyncTargetIdentity(unresolved.getId(), 90L, "acme/new");

        assertThat(repositoryToMonitorRepository.findById(other.getId()))
                .hasValueSatisfying(
                        monitor -> assertThat(monitor.getNameWithOwner()).isEqualTo("acme/other"));
        assertThat(repositoryToMonitorRepository.findById(unresolved.getId()))
                .hasValueSatisfying(monitor -> assertThat(monitor.getNativeId()).isNull());
        assertThat(repositoryToMonitorRepository.findById(holder.getId()))
                .hasValueSatisfying(monitor -> assertThat(monitor.getNativeId()).isEqualTo(90L));
    }

    @Test
    void tokenWorkspaceRefusesToMonitorARepositoryItAlreadyMonitorsUnderAnotherPath() {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITHUB, "https://github.com", 9001L);
        RepositoryToMonitor existing = buildMonitor(workspace, "shared-org/previous-name");
        existing.setNativeId(9001L);
        existing = repositoryToMonitorRepository.saveAndFlush(existing);

        assertThatThrownBy(() -> workspaceRepositoryMonitorService.addRepositoryToMonitor(
                        workspace.getWorkspaceSlug(), SHARED_NAME))
                .isInstanceOf(RepositoryAlreadyMonitoredException.class);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .extracting(RepositoryToMonitor::getId)
                .containsExactly(existing.getId());
    }

    @Test
    void replayedRepositoryAddCannotRepopulateAPurgedWorkspace() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspaceRepository.saveAndFlush(workspace);

        workspaceRepositoryMonitorService.ensureRepositoryAndMonitorFromSnapshot(
                INSTALLATION_ID,
                new ProvisioningListener.RepositorySnapshot(4L, "HephaestusTest/replayed", "replayed", true));

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner("HephaestusTest/replayed"))
                .isEmpty();
    }

    @Test
    void replayedRepositoryRemovalCannotMutateAPurgedWorkspace() {
        Workspace workspace = persistWorkspace(RepositorySelection.SELECTED);
        repositoryToMonitorRepository.saveAndFlush(buildMonitor(workspace, "HephaestusTest/retained"));
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspaceRepository.saveAndFlush(workspace);

        workspaceRepositoryMonitorService.removeRepositoryMonitorForInstallation(
                INSTALLATION_ID, "HephaestusTest/retained", null);

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactly("HephaestusTest/retained");
    }

    @Test
    void tokenWorkspaceDoesNotTakeTheIdOfAGitLabRepositoryOfTheSameName() {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITLAB, GITLAB_SERVER_URL, 701L);

        assertThatThrownBy(() -> workspaceRepositoryMonitorService.addRepositoryToMonitor(
                        workspace.getWorkspaceSlug(), SHARED_NAME))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
    }

    @Test
    void reconcileReplacesAnIdTakenFromAGitLabRepositoryOfTheSameName() {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITLAB, GITLAB_SERVER_URL, 701L);
        persistRepository(IdentityProviderType.GITHUB, "https://github.com", 9001L);
        RepositoryToMonitor monitor = buildMonitor(workspace, SHARED_NAME);
        monitor.setNativeId(701L);
        monitor = repositoryToMonitorRepository.saveAndFlush(monitor);

        workspaceSyncTargetProvider.reconcileSyncTargetIdentity(monitor.getId(), 9001L, SHARED_NAME);

        assertThat(repositoryToMonitorRepository.findById(monitor.getId()))
                .get()
                .extracting(RepositoryToMonitor::getNativeId)
                .isEqualTo(9001L);
    }

    @Test
    void shouldRefusePatMonitorCreationWhenDisconnectCommitsWhileItWaits() throws Exception {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITHUB, "https://github.com", 9001L);
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("ws-other-pat"));
        repositoryToMonitorRepository.saveAndFlush(buildMonitor(other, SHARED_NAME));
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        assertThatThrownBy(() -> holdLockWhile(() -> disconnect(connection), List.of(status -> {
                    workspaceRepositoryMonitorService.addRepositoryToMonitor(workspace.getWorkspaceSlug(), SHARED_NAME);
                    return true;
                })))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RepositoryProviderNotConnectedException.class);
        assertThat(connectionRepository
                        .findById(connection.getId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNameWithOwner(other.getId(), SHARED_NAME))
                .isPresent();
        assertThat(repositoryRepository.findByNameWithOwner(SHARED_NAME)).isPresent();
    }

    @Test
    void shouldErasePatMonitorWhenCreationCommitsBeforeDisconnect() throws Exception {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITHUB, "https://github.com", 9001L);
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        holdLockWhile(
                () -> workspaceRepositoryMonitorService.addRepositoryToMonitor(
                        workspace.getWorkspaceSlug(), SHARED_NAME),
                List.of(status -> {
                    disconnect(connection);
                    return true;
                }));
        assertThat(connectionRepository
                        .findById(connection.getId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(SHARED_NAME)).isEmpty();
    }

    @Test
    void shouldRefuseManualCreationWhenPatConnectionBecomesAnAppWhileItWaits() throws Exception {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITHUB, "https://github.com", 9001L);
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        assertThatThrownBy(() -> holdLockWhile(
                        () -> {
                            connectionRepository.acquireLifecycleLock(connection.getId(), workspace.getId());
                            var current = connectionRepository
                                    .findById(connection.getId())
                                    .orElseThrow();
                            current.setConfig(new ConnectionConfig.GitHubAppConfig(
                                    INSTALLATION_ID, workspace.getAccountLogin(), null, Set.of()));
                            connectionRepository.saveAndFlush(current);
                        },
                        List.of(status -> {
                            workspaceRepositoryMonitorService.addRepositoryToMonitor(
                                    workspace.getWorkspaceSlug(), SHARED_NAME);
                            return true;
                        })))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RepositoryManagementNotAllowedException.class);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
    }

    @Test
    void shouldCreateOnePatMonitorWhenConcurrentCreatorsWaitOnTheLifecycleLock() throws Exception {
        Workspace workspace = persistGitHubTokenWorkspace();
        persistRepository(IdentityProviderType.GITHUB, "https://github.com", 9001L);
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        Function<TransactionStatus, Boolean> create = status -> {
            try {
                workspaceRepositoryMonitorService.addRepositoryToMonitor(workspace.getWorkspaceSlug(), SHARED_NAME);
                return true;
            } catch (RepositoryAlreadyMonitoredException exception) {
                status.setRollbackOnly();
                return false;
            }
        };
        assertThat(holdLockWhile(
                        () -> connectionRepository.acquireLifecycleLock(connection.getId(), workspace.getId()),
                        List.of(create, create)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .singleElement()
                .satisfies(monitor -> assertThat(monitor.getNativeId()).isEqualTo(9001L));
    }

    private void disconnect(Connection connection) {
        ConnectionStrategy strategy = mock(ConnectionStrategy.class);
        doAnswer(invocation -> {
                    scmWorkspaceContentEraser.eraseWorkspaceScmMirror(
                            connection.getWorkspace().getId());
                    return null;
                })
                .when(strategy)
                .eraseLocalData(any());
        connectionService.disconnect(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.UNINSTALLED,
                        "DISCONNECT",
                        "SYSTEM",
                        "test",
                        UUID.randomUUID().toString(),
                        null),
                strategy);
    }

    private <T> List<T> holdLockWhile(Runnable holder, List<Function<TransactionStatus, T>> contenders)
            throws Exception {
        var release = new CountDownLatch(1);
        var blocker = new CompletableFuture<Integer>();
        try (var threads = Executors.newFixedThreadPool(contenders.size() + 1)) {
            var holding = threads.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        holder.run();
                        blocker.complete(
                                Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                        awaitRelease(release);
                    });
                } catch (RuntimeException error) {
                    blocker.completeExceptionally(error);
                    throw error;
                }
            });
            try {
                int holderPid = blocker.get(20, SECONDS);
                var pending = new ArrayList<Future<T>>();
                for (var operation : contenders) {
                    var contender = new CompletableFuture<Integer>();
                    pending.add(threads.submit(() -> Objects.requireNonNull(transactionTemplate.execute(status -> {
                        contender.complete(
                                Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                        return operation.apply(status);
                    }))));
                    int contenderPid = contender.get(20, SECONDS);
                    await().atMost(Duration.ofSeconds(20))
                            .untilAsserted(
                                    () -> assertThat(jdbc.queryForObject("""
                                            WITH RECURSIVE blockers(pid) AS (
                                                SELECT unnest(pg_blocking_pids(?))
                                                UNION
                                                SELECT unnest(pg_blocking_pids(b.pid)) FROM blockers b
                                            )
                                            SELECT EXISTS(SELECT 1 FROM blockers WHERE pid=?)
                                            """, Boolean.class, contenderPid, holderPid))
                                            .isTrue());
                }
                release.countDown();
                holding.get(20, SECONDS);
                var results = new ArrayList<T>();
                for (var future : pending) results.add(future.get(20, SECONDS));
                return results;
            } finally {
                release.countDown();
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(20, SECONDS)) {
                throw new IllegalStateException("Lifecycle transaction was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lifecycle transaction interrupted", e);
        }
    }

    private Workspace persistGitHubTokenWorkspace() {
        Workspace saved = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("ws-github-token"));
        Connection connection = new Connection(
                saved,
                IntegrationKind.GITHUB,
                null,
                new ConnectionConfig.GitHubPatConfig(saved.getAccountLogin(), null, Set.of()));
        connection.setCredentials(new BearerToken(PAT, null), credentialConverter);
        connection.setDisplayName(saved.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
        return saved;
    }

    private void persistRepository(IdentityProviderType type, String serverUrl, long nativeId) {
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> gitProviderRepository.save(new IdentityProvider(type, serverUrl)));
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setName("shared-repo");
        repository.setNameWithOwner(SHARED_NAME);
        repository.setHtmlUrl(serverUrl + "/" + SHARED_NAME);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setProvider(provider);
        repositoryRepository.save(repository);
    }

    private Workspace persistWorkspace(RepositorySelection selection) {
        var builder = WorkspaceTestFixtures.installationWorkspace(INSTALLATION_ID, "HephaestusTest")
                .withRepositorySelection(selection)
                .withSlug(
                        "ws-install-" + INSTALLATION_ID + "-" + selection.name().toLowerCase(Locale.ENGLISH));
        // the provider classification used by
        // WorkspaceRepositoryMonitorService.isGitHubAppWorkspace pulls from the
        // Connection registry, so the fixture must persist the GitHub App connection
        // alongside the Workspace.
        return WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaceRepository, connectionRepository, builder, INSTALLATION_ID);
    }

    private void assertRenamedInPlace(
            Workspace workspace, RepositoryToMonitor monitor, Repository mirror, String canonicalName) {
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .singleElement()
                .satisfies(kept -> {
                    assertThat(kept.getId()).isEqualTo(monitor.getId());
                    assertThat(kept.getNameWithOwner()).isEqualTo(canonicalName);
                });
        assertThat(repositoryRepository.findById(mirror.getId())).hasValueSatisfying(repository -> {
            assertThat(repository.getNameWithOwner()).isEqualTo(canonicalName);
            assertThat(repository.getNativeId()).isEqualTo(mirror.getNativeId());
            assertThat(repository.getDefaultBranch()).isEqualTo("develop");
        });
        assertThat(repositoryToMonitorRepository.findSourceRepositoryIdsOfMonitor(monitor.getId()))
                .containsExactly(mirror.getId());
        assertThat(reviewScopeRepositoryRepository.findByWorkspaceId(workspace.getId()))
                .extracting(ReviewScopeRepository::getRepositoryMonitorId)
                .containsExactly(monitor.getId());
    }

    private Repository persistGitHubRepository(long nativeId, String nameWithOwner) {
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow();
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("develop");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setProvider(provider);
        return repositoryRepository.saveAndFlush(repository);
    }

    private RepositoryToMonitor monitorWithScope(Workspace workspace, String nameWithOwner, long nativeId) {
        RepositoryToMonitor monitor = buildMonitor(workspace, nameWithOwner);
        monitor.setNativeId(nativeId);
        monitor = repositoryToMonitorRepository.saveAndFlush(monitor);
        reviewScopeRepositoryRepository.saveAndFlush(
                new ReviewScopeRepository(workspace.getId(), monitor.getId(), List.of("main")));
        return monitor;
    }

    private RepositoryToMonitor buildMonitor(Workspace workspace, String nameWithOwner) {
        return WorkspaceTestFixtures.repositoryMonitor(workspace, nameWithOwner);
    }

    private InstallationRepositoryEnumerator.InstallationRepository snapshot(
            long id, String nameWithOwner, String name, boolean isPrivate) {
        return new InstallationRepositoryEnumerator.InstallationRepository(id, nameWithOwner, name, isPrivate);
    }
}
