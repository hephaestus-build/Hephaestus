package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEventRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.events.RepositoryAboutToBeDeletedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class GitLabStoppedProjectServiceIntegrationTest extends BaseIntegrationTest {
    private static final String GITLAB_URL = "https://gitlab.example.test";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private ApplicationEventMulticaster multicaster;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private GitLabStoppedProjectService stoppedProjects;

    @Autowired
    private ScmItemEventRecorder recorder;

    @Autowired
    private WorkspaceRepositoryMonitorService monitorService;

    @Autowired
    private ScmWorkspaceContentEraser eraser;

    @Autowired
    private SyncTargetProvider syncTargets;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pulls;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    /** GitLab itself: a manual Add asks it whether the workspace's own stored token reads the project. */
    @MockitoBean
    private GitLabGraphQlClientProvider gitLabClients;

    @Test
    void shouldReactivateTheKeptItemsAndClearTheReceiptWhenAStoppedProjectIsAddedAgain() {
        var own = gitLabWorkspace();
        var repository = repository(own.getAccountLogin() + "/kept");
        long pullId = pull(repository);
        long root = monitorAndIndex(own, repository);
        long pullItem = member(own, pullId, root);

        stop(own, repository);
        assertThat(stoppedProjects.getStoppedProjects(own.getId()))
                .singleElement()
                .satisfies(project -> {
                    assertThat(project.repositoryItemId()).isEqualTo(String.valueOf(root));
                    assertThat(project.nameWithOwner()).isEqualTo(repository.getNameWithOwner());
                });
        confirmTo(own, repository);
        monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), repository.getNameWithOwner());

        assertThat(stoppedProjects.getStoppedProjects(own.getId())).isEmpty();
        var exclusions = gitLabConfig(own).projectExclusions();
        assertThat(exclusions.excludes(repository.getNativeId())).isFalse();
        assertThat(exclusions.stopReceipt(repository.getNativeId())).isEmpty();
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), repository.getNameWithOwner()))
                .isPresent();
        assertThat(active(own, root)).isTrue();
        assertThat(member(own, pullId, root)).isEqualTo(pullItem);
    }

    @Test
    void shouldRefuseTheRemovalWhenItsReceiptNamesAnEarlierStopOfTheSameProject() {
        var own = gitLabWorkspace();
        var repository = repository(own.getAccountLogin() + "/restopped");
        long root = monitorAndIndex(own, repository);
        long actor = actor();

        stop(own, repository);
        UUID first = receipt(own, root);
        confirmTo(own, repository);
        monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), repository.getNameWithOwner());
        assertStatus(() -> stoppedProjects.removeStoppedProject(own.getId(), root, first, actor), HttpStatus.NOT_FOUND);
        stop(own, repository);
        UUID second = receipt(own, root);

        assertThat(second).isNotEqualTo(first);
        assertStatus(() -> stoppedProjects.removeStoppedProject(own.getId(), root, first, actor), HttpStatus.CONFLICT);
        assertThat(active(own, root)).isTrue();
        assertThat(repositories.findById(repository.getId())).isPresent();

        stoppedProjects.removeStoppedProject(own.getId(), root, second, actor);

        assertThat(active(own, root)).isFalse();
        assertThat(repositories.findById(repository.getId())).isEmpty();
        assertThat(gitLabConfig(own).projectExclusions().excludes(repository.getNativeId()))
                .isTrue();
        assertThat(stoppedProjects.getStoppedProjects(own.getId())).isEmpty();
    }

    /**
     * An Add of a stopped project adds nothing unless GitLab confirms the project to the connection's current token:
     * GitLab names no project to the token, or it answers for a token that was replaced while the answer was on the
     * way. Either way the stop, its receipt and the kept items stay, and that receipt still removes them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DENIED", "TOKEN_REPLACED"})
    void shouldKeepTheStopAndItsReceiptWhenTheAddIsNotConfirmedToTheCurrentToken(String answer) throws Exception {
        boolean replaced = answer.equals("TOKEN_REPLACED");
        var own = gitLabWorkspace();
        var repository = repository(own.getAccountLogin() + "/unconfirmed");
        long pullId = pull(repository);
        long root = monitorAndIndex(own, repository);
        long pullItem = member(own, pullId, root);
        stop(own, repository);
        UUID stopReceipt = receipt(own, root);
        Connection before =
                connections.findActive(own.getId(), IntegrationKind.GITLAB).orElseThrow();
        String noProject = "{\"data\":{\"project\":null}}";
        var asked = new CountDownLatch(1);
        var answered = new CompletableFuture<String>();
        // Only the client built from the token this connection stores, for its stored server, reaches the transport.
        when(gitLabClients.forScope(own.getId(), tokenOf(own), GITLAB_URL)).thenReturn(client(request -> {
            asked.countDown();
            return Mono.fromFuture(answered).map(GitLabStoppedProjectServiceIntegrationTest::ok);
        }));
        if (!replaced) answered.complete(noProject);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var add = CompletableFuture.runAsync(
                    () -> monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), repository.getNameWithOwner()),
                    executor);
            assertThat(asked.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("the Add's lookup reached GitLab")
                    .isTrue();
            if (replaced) {
                var replacement = new BearerToken(tokenOf(own) + "-replaced", null);
                assertThat(connectionService.rotateBearerToken(own.getId(), IntegrationKind.GITLAB, replacement, null))
                        .isPresent();
                assertThat(connectionService.findActiveBearerToken(own.getId(), IntegrationKind.GITLAB))
                        .contains(replacement);
                answered.complete(project(repository));
            }
            await().atMost(TIMEOUT).until(add::isDone);
            var failure = assertThrows(CompletionException.class, add::join);
            assertThat(failure.getCause())
                    .isInstanceOf(replaced ? RepositorySelectionChangedException.class : EntityNotFoundException.class);
        } finally {
            answered.complete(noProject);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
        }
        if (replaced) {
            assertThat(connections
                            .findById(Objects.requireNonNull(before.getId()))
                            .orElseThrow()
                            .getVersion())
                    .as("the token was replaced on the same row")
                    .isNotEqualTo(before.getVersion());
        }
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), repository.getNameWithOwner()))
                .isEmpty();
        var exclusions = gitLabConfig(own).projectExclusions();
        assertThat(exclusions.excludes(repository.getNativeId())).isTrue();
        assertThat(exclusions.stopReceipt(repository.getNativeId())).contains(stopReceipt);
        assertThat(receipt(own, root)).isEqualTo(stopReceipt);
        assertThat(active(own, root)).isTrue();
        assertThat(member(own, pullId, root)).isEqualTo(pullItem);

        stoppedProjects.removeStoppedProject(own.getId(), root, stopReceipt, actor());

        assertThat(active(own, root)).isFalse();
        assertThat(repositories.findById(repository.getId())).isEmpty();
    }

    @Test
    void shouldRefuseTheRemovalWhenTheWorkspaceDoesNotKeepTheProjectOrHasNoGitLabConnection() {
        var own = gitLabWorkspace();
        var repository = repository(own.getAccountLogin() + "/foreign-request");
        long root = monitorAndIndex(own, repository);
        stop(own, repository);
        UUID receipt = receipt(own, root);
        var other = gitLabWorkspace();
        long installation = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String slug = "stopped-github-" + UUID.randomUUID();
        var github = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installation, slug).withSlug(slug),
                installation);
        long actor = actor();

        assertStatus(
                () -> stoppedProjects.removeStoppedProject(other.getId(), root, receipt, actor), HttpStatus.NOT_FOUND);
        assertStatus(
                () -> stoppedProjects.removeStoppedProject(github.getId(), root, receipt, actor), HttpStatus.CONFLICT);
        assertThat(stoppedProjects.getStoppedProjects(other.getId())).isEmpty();
        assertThat(stoppedProjects.getStoppedProjects(github.getId())).isEmpty();
        assertThat(active(own, root)).isTrue();
        assertThat(receipt(own, root)).isEqualTo(receipt);
    }

    @Test
    void shouldKeepTheSharedSourceUntilItsLastReleaseAndRestoreItsItemWhenTheProjectIsAddedAgain() {
        var keeping = gitLabWorkspace();
        var removing = gitLabWorkspace();
        var monitoring = gitLabWorkspace();
        var repository = repository(keeping.getAccountLogin() + "/shared");
        long kept = monitorAndIndex(keeping, repository);
        long removed = monitorAndIndex(removing, repository);
        long monitored = monitorAndIndex(monitoring, repository);

        stop(keeping, repository);
        stop(removing, repository);
        stoppedProjects.removeStoppedProject(removing.getId(), removed, receipt(removing, removed), actor());
        assertThat(active(removing, removed)).isFalse();
        confirmTo(removing, repository);
        monitorService.addRepositoryToMonitor(removing.getWorkspaceSlug(), repository.getNameWithOwner());
        assertThat(active(removing, removed)).isTrue();

        eraser.eraseWorkspaceScmMirror(monitoring.getId());
        eraser.eraseWorkspaceScmMirror(removing.getId());
        assertThat(active(monitoring, monitored)).isFalse();
        assertThat(active(removing, removed)).isFalse();
        assertThat(active(keeping, kept)).isTrue();
        assertThat(repositories.findById(repository.getId())).isPresent();

        eraser.eraseWorkspaceScmMirror(keeping.getId());
        assertThat(active(keeping, kept)).isFalse();
        assertThat(repositories.findById(repository.getId())).isEmpty();
    }

    /**
     * Another workspace adds the project while a removal decides whether the shared source is still needed. The
     * removal is held where it publishes that it deletes the source, after its checks; the claim is held before its
     * commit. Whether the removal waits there or on a lock the claim holds, the source must outlive it.
     */
    @Test
    void shouldKeepBothWorkspacesWorkAndTheStopReceiptWhenAConcurrentClaimRefusesRemoval() throws Exception {
        var removing = gitLabWorkspace();
        var claiming = gitLabWorkspace();
        var repository = repository(removing.getAccountLogin() + "/claimed");
        long repositoryId = Objects.requireNonNull(repository.getId());
        long pullId = pull(repository);
        long root = monitorAndIndex(removing, repository);
        stop(removing, repository);
        UUID stopReceipt = receipt(removing, root);
        long actor = actor();
        confirmTo(claiming, repository);
        var claimed = new CountDownLatch(1);
        var releaseClaim = new CountDownLatch(1);
        var claimedRoot = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var claim = CompletableFuture.runAsync(
                    () -> transactions.executeWithoutResult(status -> {
                        monitorService.addRepositoryToMonitor(
                                claiming.getWorkspaceSlug(), repository.getNameWithOwner());
                        claimedRoot.set(activeRoot(claiming, repositoryId));
                        claimed.countDown();
                        hold(releaseClaim);
                    }),
                    executor);
            await().atMost(TIMEOUT).until(() -> claimed.getCount() == 0 || claim.isDone());
            if (claim.isDone()) claim.join();
            var removal = CompletableFuture.runAsync(
                    () -> transactions.executeWithoutResult(
                            status -> stoppedProjects.removeStoppedProject(removing.getId(), root, stopReceipt, actor)),
                    executor);
            await().atMost(TIMEOUT).until(removal::isDone);
            var failure = assertThrows(CompletionException.class, removal::join);
            assertThat(failure.getCause())
                    .isInstanceOfSatisfying(
                            ConcurrencyFailureException.class,
                            GitLabStoppedProjectServiceIntegrationTest::assertLockConflict);
            releaseClaim.countDown();
            claim.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
        }
        assertThat(active(removing, root)).isTrue();
        assertThat(receipt(removing, root)).isEqualTo(stopReceipt);
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(claiming.getId(), repository.getNameWithOwner()))
                .isPresent();
        assertThat(activeRoot(claiming, repositoryId)).isEqualTo(claimedRoot.get());
        assertThat(repositories.findById(repositoryId)).isPresent();
        assertThat(pulls.findById(pullId)).isPresent();
        member(claiming, pullId, claimedRoot.get());
    }

    @Test
    void shouldAllowConcurrentSourceClaimsWhenTwoWorkspacesAddTheSameProject() throws Exception {
        var owner = gitLabWorkspace();
        var first = gitLabWorkspace();
        var second = gitLabWorkspace();
        var repository = repository(owner.getAccountLogin() + "/shared");
        long repositoryId = Objects.requireNonNull(repository.getId());
        long pullId = pull(repository);
        monitorAndIndex(owner, repository);
        confirmTo(first, repository);
        confirmTo(second, repository);
        var claimed = new CountDownLatch(1);
        var releaseClaim = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstClaim = CompletableFuture.runAsync(
                    () -> transactions.executeWithoutResult(status -> {
                        monitorService.addRepositoryToMonitor(first.getWorkspaceSlug(), repository.getNameWithOwner());
                        claimed.countDown();
                        hold(releaseClaim);
                    }),
                    executor);
            await().atMost(TIMEOUT).until(() -> claimed.getCount() == 0 || firstClaim.isDone());
            if (firstClaim.isDone()) firstClaim.join();
            var secondClaim = CompletableFuture.runAsync(
                    () -> monitorService.addRepositoryToMonitor(
                            second.getWorkspaceSlug(), repository.getNameWithOwner()),
                    executor);
            secondClaim.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            member(second, pullId, activeRoot(second, repositoryId));
            releaseClaim.countDown();
            firstClaim.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
        }
        member(first, pullId, activeRoot(first, repositoryId));
        member(second, pullId, activeRoot(second, repositoryId));
    }

    @Test
    void shouldRollBackANewClaimWhenDeletionAlreadyOwnsTheSource() throws Exception {
        var removing = gitLabWorkspace();
        var claiming = gitLabWorkspace();
        var repository = repository(removing.getAccountLogin() + "/deleting");
        long repositoryId = Objects.requireNonNull(repository.getId());
        long pullId = pull(repository);
        long root = monitorAndIndex(removing, repository);
        stop(removing, repository);
        UUID stopReceipt = receipt(removing, root);
        long actor = actor();
        // GitLab confirms the project to the claim, so the claim reaches the deletion's lock.
        confirmTo(claiming, repository);
        var deletionStarted = new CountDownLatch(1);
        var releaseDeletion = new CountDownLatch(1);
        ApplicationListener<ApplicationEvent> deletionBarrier = event -> {
            if (event instanceof PayloadApplicationEvent<?> published
                    && published.getPayload() instanceof RepositoryAboutToBeDeletedEvent deleted
                    && deleted.repositoryId() == repositoryId) {
                deletionStarted.countDown();
                hold(releaseDeletion);
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        multicaster.addApplicationListener(deletionBarrier);
        try {
            var removal = CompletableFuture.runAsync(
                    () -> stoppedProjects.removeStoppedProject(removing.getId(), root, stopReceipt, actor), executor);
            await().atMost(TIMEOUT).until(() -> deletionStarted.getCount() == 0 || removal.isDone());
            if (removal.isDone()) removal.join();
            var failure = assertThrows(
                    ConcurrencyFailureException.class,
                    () -> monitorService.addRepositoryToMonitor(
                            claiming.getWorkspaceSlug(), repository.getNameWithOwner()));
            assertLockConflict(failure);
            assertThat(monitors.findByWorkspaceIdAndNameWithOwner(claiming.getId(), repository.getNameWithOwner()))
                    .isEmpty();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM item WHERE workspace_id=? AND kind='scm.repository' AND source_id=?",
                            Long.class,
                            claiming.getId(),
                            repositoryId))
                    .isZero();
            releaseDeletion.countDown();
            removal.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            releaseDeletion.countDown();
            multicaster.removeApplicationListener(deletionBarrier);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
        }
        assertThat(active(removing, root)).isFalse();
        assertThat(stoppedProjects.getStoppedProjects(removing.getId())).isEmpty();
        assertThat(repositories.findById(repositoryId)).isEmpty();
        assertThat(pulls.findById(pullId)).isEmpty();
    }

    @Test
    void shouldRefuseToPinTheEventWhenItsSourceWasRemoved() {
        var workspace = gitLabWorkspace();
        var repository = repository(workspace.getAccountLogin() + "/removed");
        long pullId = pull(repository);
        long root = monitorAndIndex(workspace, repository);
        var captured = Objects.requireNonNull(transactions.execute(status -> {
            var snapshot =
                    ScmEventPayload.PullRequestData.from(pulls.findById(pullId).orElseThrow());
            return new ScmDomainEvent.PullRequestUpdated(
                    snapshot,
                    Set.of("title"),
                    EventContext.forSync(
                            workspace.getId(), snapshot.repository(), IdentityProviderType.GITLAB, Instant.now()));
        }));
        stop(workspace, repository);
        stoppedProjects.removeStoppedProject(workspace.getId(), root, receipt(workspace, root), actor());
        assertThat(repositories.findById(repository.getId())).isEmpty();

        assertThrows(
                ObjectOptimisticLockingFailureException.class,
                () -> transactions.executeWithoutResult(status -> recorder.onPullRequest(captured)));
        assertThat(active(workspace, root)).isFalse();
    }

    private static void assertLockConflict(ConcurrencyFailureException conflict) {
        assertThat(conflict.getMostSpecificCause())
                .isInstanceOfSatisfying(
                        SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo("55P03"));
    }

    private static void hold(CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting for the other transaction");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the other transaction", interrupted);
        }
    }

    private long activeRoot(Workspace workspace, long repositoryId) {
        var rows = jdbc.queryForList(
                "SELECT id FROM item WHERE workspace_id=? AND kind='scm.repository' AND parent_id IS NULL"
                        + " AND source_id=? AND removed_at IS NULL",
                Long.class,
                workspace.getId(),
                repositoryId);
        assertThat(rows).hasSize(1);
        return Objects.requireNonNull(rows.getFirst());
    }

    private void stop(Workspace workspace, Repository repository) {
        monitorService.removeRepositoryFromMonitor(workspace.getWorkspaceSlug(), repository.getNameWithOwner());
    }

    private UUID receipt(Workspace workspace, long root) {
        return stoppedProjects.getStoppedProjects(workspace.getId()).stream()
                .filter(project -> project.repositoryItemId().equals(String.valueOf(root)))
                .findFirst()
                .orElseThrow()
                .stopReceipt();
    }

    private static void assertStatus(Runnable request, HttpStatus status) {
        assertThatThrownBy(request::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(status));
    }

    private Workspace gitLabWorkspace() {
        String slug = "stopped-" + UUID.randomUUID();
        Workspace workspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                GITLAB_URL);
        // The connection stores its own token, which a manual Add sends to GitLab to confirm the project.
        Connection connection = connections
                .findActive(workspace.getId(), IntegrationKind.GITLAB)
                .orElseThrow();
        connection.setCredentials(new BearerToken(tokenOf(workspace), null), credentialConverter);
        connections.saveAndFlush(connection);
        return workspace;
    }

    /** The synthetic token stored with the workspace's connection; no real credential is read anywhere here. */
    private static String tokenOf(Workspace workspace) {
        return "synthetic-token-" + workspace.getWorkspaceSlug();
    }

    /** GitLab confirms {@code repository}, at its stored id and path, to the workspace's own stored token. */
    private void confirmTo(Workspace workspace, Repository repository) {
        when(gitLabClients.forScope(workspace.getId(), tokenOf(workspace), GITLAB_URL))
                .thenReturn(client(request -> Mono.just(ok(project(repository)))));
    }

    private static String project(Repository repository) {
        String path = repository.getNameWithOwner();
        return """
                {"data":{"project":{"id":"gid://gitlab/Project/%s","fullPath":"%s","name":"%s",
                  "webUrl":"%s/%s","visibility":"public","group":null}}}
                """.formatted(
                        repository.getNativeId(), path, path.substring(path.lastIndexOf('/') + 1), GITLAB_URL, path);
    }

    private static ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    /** A GitLab client for {@link #GITLAB_URL} whose requests {@code exchange} answers; nothing leaves the test. */
    private static HttpGraphQlClient client(ExchangeFunction exchange) {
        return HttpGraphQlClient.builder(WebClient.builder().exchangeFunction(exchange))
                .url(GITLAB_URL + "/api/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/gitlab/operations/"),
                                new ClassPathResource("graphql/gitlab/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"))))
                .build();
    }

    private Repository repository(String nameWithOwner) {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, GITLAB_URL)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, GITLAB_URL)));
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName(nameWithOwner.substring(nameWithOwner.lastIndexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl(GITLAB_URL + "/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository);
    }

    private long pull(Repository repository) {
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(repository.getProvider());
        pull.setRepository(repository);
        pull.setNumber(1);
        pull.setTitle("Kept pull request");
        pull.setState(Issue.State.OPEN);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/1");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        return pulls.saveAndFlush(pull).getId();
    }

    /** Binds a monitor the way sync does and returns the workspace's repository item. */
    private long monitorAndIndex(Workspace workspace, Repository repository) {
        var monitor = monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId()));
        syncTargets.reconcileSyncTargetIdentity(
                monitor.getId(), repository.getNativeId(), repository.getNameWithOwner());
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM item WHERE workspace_id=? AND kind='scm.repository' AND source_id=?",
                Long.class,
                workspace.getId(),
                repository.getId()));
    }

    private long member(Workspace workspace, long pullId, long root) {
        var rows = jdbc.queryForList(
                "SELECT id FROM item WHERE workspace_id=? AND kind='scm.pull_request' AND source_id=? AND parent_id=?"
                        + " AND removed_at IS NULL",
                Long.class,
                workspace.getId(),
                pullId,
                root);
        assertThat(rows).hasSize(1);
        return Objects.requireNonNull(rows.getFirst());
    }

    private boolean active(Workspace workspace, long itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT removed_at IS NULL FROM item WHERE workspace_id=? AND id=?",
                Boolean.class,
                workspace.getId(),
                itemId));
    }

    private long actor() {
        return Objects.requireNonNull(accounts.save(new Account("stopped-project-admin-" + UUID.randomUUID()))
                .getId());
    }

    private ConnectionConfig.GitLabConfig gitLabConfig(Workspace workspace) {
        var config = connections
                .findActive(workspace.getId(), IntegrationKind.GITLAB)
                .orElseThrow()
                .getConfig();
        if (config instanceof ConnectionConfig.GitLabConfig gitLab) return gitLab;
        throw new IllegalStateException("Test workspace has no GitLab connection");
    }
}
