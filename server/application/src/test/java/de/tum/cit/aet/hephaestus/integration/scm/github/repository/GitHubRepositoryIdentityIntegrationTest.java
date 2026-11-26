package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static de.tum.cit.aet.hephaestus.testconfig.RepositoryObservationTestFixtures.confirm;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class GitHubRepositoryIdentityIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final String NAME = "shared-org/shared-repo";
    private static final long OLD_ID = 771L;

    @Autowired
    private RepositoryObservationStore observations;

    @Autowired
    private GitHubRepositoryMessageHandler handler;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceSyncTargetProvider syncTargets;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldChangeOnlyTheAttestedProvidersRepositoryAcrossArchiveAndVisibilityReverseStates() {
        Repository github = repository(ensureGitHubProvider(), OLD_ID);
        Repository gitlab = repository(ensureGitLabProvider(), OLD_ID);
        deliver(event("archived", OLD_ID));
        deliver(event("privatized", OLD_ID));
        assertThat(repositories.findById(github.getId()).orElseThrow()).satisfies(row -> {
            assertThat(row.isArchived()).isTrue();
            assertThat(row.getVisibility()).isEqualTo(Repository.Visibility.PRIVATE);
        });
        assertThat(repositories.findById(gitlab.getId()).orElseThrow()).satisfies(row -> {
            assertThat(row.isArchived()).isFalse();
            assertThat(row.getVisibility()).isEqualTo(Repository.Visibility.PUBLIC);
        });
        deliver(event("unarchived", OLD_ID));
        deliver(event("publicized", OLD_ID));
        assertThat(repositories.findById(github.getId()).orElseThrow()).satisfies(row -> {
            assertThat(row.isArchived()).isFalse();
            assertThat(row.getVisibility()).isEqualTo(Repository.Visibility.PRIVATE);
            assertThat(row.isPrivate()).isTrue();
        });
    }

    @Test
    void shouldLeaveANameReplacementIntactWhenAnOldRepositoryEventArrives() {
        Repository replacement = repository(ensureGitHubProvider(), OLD_ID + 1);
        deliver(event("archived", OLD_ID));
        deliver(event("privatized", OLD_ID));
        deliver(event("deleted", OLD_ID));
        assertThat(repositories.findById(replacement.getId()).orElseThrow()).satisfies(row -> {
            assertThat(row.getNameWithOwner()).isEqualTo(NAME);
            assertThat(row.isArchived()).isFalse();
            assertThat(row.getVisibility()).isEqualTo(Repository.Visibility.PUBLIC);
        });
        deliver(event("archived", null));
        assertThat(repositories.findById(replacement.getId()).orElseThrow().isArchived())
                .isTrue();
    }

    @Test
    void shouldDeleteOnlyTheExactProviderRepositoryWhenTheInstallationIsNotInThePayload() {
        Repository github = repository(ensureGitHubProvider(), OLD_ID);
        Repository gitlab = repository(ensureGitLabProvider(), OLD_ID);
        deliver(event("deleted", OLD_ID));
        assertThat(repositories.findById(github.getId())).isEmpty();
        assertThat(repositories.findById(gitlab.getId())).isPresent();
    }

    @Test
    void shouldNarrowEveryWorkspacesMonitorWhenTheRepositoryTurnsPrivate() {
        repository(ensureGitHubProvider(), OLD_ID);
        long first = publicMonitor("privatized-first");
        long second = publicMonitor("privatized-second");
        long foreign = gitLabMonitor("privatized-foreign", ensureGitLabProvider());
        long foreignGeneration = proofGeneration(foreign);
        assertThat(List.of(proof(first), proof(second))).containsOnly("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");

        deliver(event("privatized", OLD_ID));

        assertThat(List.of(proof(first), proof(second)))
                .as("GitHub's event narrows every workspace that monitors the repository before its next sync")
                .containsOnly("UNPROVEN PENDING AWAITING_READERS");
        assertThat(proofGeneration(foreign)).isEqualTo(foreignGeneration);
    }

    /**
     * One workspace's sync stores the repository as private. Whether a repository is public is a fact about the
     * repository, so the same write narrows every workspace's monitor, and a monitor that waits for another reason keeps
     * it.
     */
    @Test
    void shouldNarrowEveryWorkspacesMonitorWhenTheStoredRepositoryIsNoLongerPublic() {
        Repository stored = repository(ensureGitHubProvider(), OLD_ID);
        long reader = publicMonitor("stored-private-reader");
        long other = publicMonitor("stored-private-other");
        long unavailable = publicMonitor("stored-private-unavailable");
        var lostMonitor = monitors.findById(unavailable).orElseThrow();
        syncTargets.recordRepositoryLoss(syncTargets.beginReadersProof(
                Objects.requireNonNull(lostMonitor.getWorkspace()).getId(),
                unavailable,
                OLD_ID,
                Objects.requireNonNull(ensureGitHubProvider().getId())));

        transactions.executeWithoutResult(status -> {
            Repository row = repositories.findById(stored.getId()).orElseThrow();
            row.setVisibility(Repository.Visibility.PRIVATE);
            row.setPrivate(true);
            repositories.saveAndFlush(row);
        });

        assertThat(List.of(proof(reader), proof(other))).containsOnly("UNPROVEN PENDING AWAITING_READERS");
        assertThat(proof(unavailable)).isEqualTo("UNPROVEN FAILED RESOURCE_UNAVAILABLE");
    }

    @Test
    void shouldRefreshPrivateMirrorsConcurrentlyWhenProvidersShareANativeId() throws Exception {
        var github = repository(ensureGitHubProvider(), OLD_ID);
        var gitLabProvider = ensureGitLabProvider();
        var gitlab = repository(gitLabProvider, OLD_ID);
        long githubMonitor = publicMonitor("concurrent-private-github");
        long gitlabMonitor = gitLabMonitor("concurrent-private-gitlab", gitLabProvider);
        long githubGeneration = proofGeneration(githubMonitor);
        long gitlabGeneration = proofGeneration(gitlabMonitor);
        var monitorsLocked = new CountDownLatch(2);

        var first = CompletableFuture.runAsync(() -> refreshPrivate(github, githubMonitor, monitorsLocked));
        var second = CompletableFuture.runAsync(() -> refreshPrivate(gitlab, gitlabMonitor, monitorsLocked));
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        for (Repository mirror : List.of(github, gitlab)) {
            assertThat(repositories.findById(mirror.getId()).orElseThrow().getVisibility())
                    .isEqualTo(Repository.Visibility.PRIVATE);
        }
        assertThat(proof(githubMonitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(proofGeneration(githubMonitor)).isEqualTo(githubGeneration + 1);
        assertThat(proofGeneration(gitlabMonitor)).isEqualTo(gitlabGeneration + 1);
    }

    private void refreshPrivate(Repository mirror, long monitor, CountDownLatch monitorsLocked) {
        transactions.executeWithoutResult(status -> {
            // Metadata persistence holds its own monitor before the mirror write fires the trigger.
            jdbc.queryForObject("SELECT id FROM repository_to_monitor WHERE id = ? FOR UPDATE", Long.class, monitor);
            monitorsLocked.countDown();
            try {
                assertThat(monitorsLocked.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            jdbc.update("UPDATE repository SET visibility = 'PRIVATE', is_private = true WHERE id = ?", mirror.getId());
        });
    }

    private long gitLabMonitor(String slug, IdentityProvider provider) {
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, persistUser(slug + "-owner"));
        connectActivitySource(workspace, provider);
        return monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, NAME, OLD_ID))
                .getId();
    }

    @Test
    void shouldNarrowEveryWorkspacesMonitorWhenTheStoredRepositoryGoes() {
        Repository stored = repository(ensureGitHubProvider(), OLD_ID);
        long first = publicMonitor("stored-gone-first");
        long second = publicMonitor("stored-gone-second");

        transactions.executeWithoutResult(status -> repositories.deleteById(stored.getId()));

        assertThat(List.of(proof(first), proof(second))).containsOnly("UNPROVEN PENDING AWAITING_READERS");
    }

    /**
     * A mirror from before checked visibility writes can hold public visibility with the private flag set.
     */
    @Test
    void shouldRefuseAPublicAnswerWhoseReadBeganBeforeAPrivatizedEventOnAHalfPublicRow() {
        Repository stored = repository(ensureGitHubProvider(), OLD_ID);
        transactions.executeWithoutResult(status -> {
            Repository row = repositories.findById(stored.getId()).orElseThrow();
            row.setPrivate(true);
            repositories.saveAndFlush(row);
        });
        long monitor = publicMonitor("half-public");
        var readOfThePublicRepository = syncTargets.beginReadersProof(
                monitorScope(monitor),
                monitor,
                OLD_ID,
                Objects.requireNonNull(ensureGitHubProvider().getId()));

        deliver(event("privatized", OLD_ID));
        transactions.executeWithoutResult(status -> {
            Repository row = repositories.findById(stored.getId()).orElseThrow();
            row.setVisibility(Repository.Visibility.PUBLIC);
            row.setPrivate(false);
            repositories.saveAndFlush(row);
        });
        confirm(observations, readOfThePublicRepository, true);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /**
     * GitHub reports the repository deleted for an installation. The mirror row stays while other workspaces monitor
     * the repository, and none of their monitors may keep a public proof of a repository that no one can see.
     */
    @Test
    void shouldNarrowEveryWorkspacesMonitorWhenGitHubReportsTheRepositoryDeletedForOneInstallation() {
        repository(ensureGitHubProvider(), OLD_ID);
        long first = publicMonitor("deleted-first");
        long second = publicMonitor("deleted-second");

        deliver(new GitHubRepositoryEventDTO(
                "deleted",
                new GitHubRepositoryRefDTO(OLD_ID, "R_fixture", "shared-repo", NAME, false, null, "main"),
                null,
                null,
                new GitHubRepositoryEventDTO.InstallationRef(1L, "I_unknown")));

        assertThat(List.of(proof(first), proof(second))).containsOnly("UNPROVEN PENDING AWAITING_READERS");
    }

    /**
     * GitHub reports the repository private while the mirror row already holds it private, so the row's write changes
     * nothing, or while no row is stored. A public answer from a read that began before the event still does not apply.
     */
    @ParameterizedTest(name = "mirror row {0}")
    @ValueSource(strings = {"already private", "not stored"})
    void shouldRefuseAnEarlierPublicAnswerWhenGitHubReportsTheRepositoryPrivate(String mirror) {
        if (mirror.equals("already private")) {
            Repository stored = repository(ensureGitHubProvider(), OLD_ID);
            storeAsPrivate(stored);
        }
        long monitor = publicMonitor("privatized-" + mirror.replace(' ', '-'));
        var earlierRead = syncTargets.beginReadersProof(
                monitorScope(monitor),
                monitor,
                OLD_ID,
                Objects.requireNonNull(ensureGitHubProvider().getId()));

        deliver(event("privatized", OLD_ID));
        confirm(observations, earlierRead, true);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /** A sync stores a private repository as private again: that is an answer too, whatever the row held before. */
    @Test
    void shouldRefuseAnEarlierPublicAnswerWhenASyncStoresThePrivateRepositoryAgain() {
        Repository stored = repository(ensureGitHubProvider(), OLD_ID);
        long monitor = publicMonitor("private-again");
        storeAsPrivate(stored);
        var earlierRead = syncTargets.beginReadersProof(
                monitorScope(monitor),
                monitor,
                OLD_ID,
                Objects.requireNonNull(ensureGitHubProvider().getId()));

        var privateRead = syncTargets.beginReadersProof(
                monitorScope(monitor),
                monitor,
                OLD_ID,
                Objects.requireNonNull(ensureGitHubProvider().getId()));
        confirm(observations, privateRead, false);
        confirm(observations, earlierRead, true);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    private void storeAsPrivate(Repository stored) {
        transactions.executeWithoutResult(status -> {
            Repository row = repositories.findById(stored.getId()).orElseThrow();
            row.setVisibility(Repository.Visibility.PRIVATE);
            row.setPrivate(true);
            repositories.saveAndFlush(row);
        });
    }

    /** A monitor of {@link #OLD_ID} in a new GitHub workspace, which GitHub last showed public. */
    private long publicMonitor(String slug) {
        long installation = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
        Workspace workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces, connections, WorkspaceTestFixtures.installationWorkspace(installation, slug), installation);
        long id = Objects.requireNonNull(
                monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, slug + "/" + NAME, OLD_ID))
                        .getId());
        confirm(
                observations,
                syncTargets.beginReadersProof(
                        workspace.getId(),
                        id,
                        OLD_ID,
                        Objects.requireNonNull(ensureGitHubProvider().getId())),
                true);
        return id;
    }

    private long monitorScope(long monitorId) {
        return Objects.requireNonNull(monitors.findById(monitorId).orElseThrow().getWorkspace())
                .getId();
    }

    private String proof(long monitorId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT visibility_class || ' ' || proof_state || ' ' || proof_reason FROM repository_to_monitor"
                        + " WHERE id = ?",
                String.class,
                monitorId));
    }

    private long proofGeneration(long monitorId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT proof_generation FROM repository_to_monitor WHERE id = ?", Long.class, monitorId));
    }

    private Repository repository(IdentityProvider provider, long nativeId) {
        Repository repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(nativeId);
        repository.setName("shared-repo");
        repository.setNameWithOwner(NAME);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + NAME);
        repository.setVisibility(Repository.Visibility.PUBLIC);
        return repositories.saveAndFlush(repository);
    }

    private static GitHubRepositoryEventDTO event(String action, @Nullable Long nativeId) {
        return new GitHubRepositoryEventDTO(
                action,
                new GitHubRepositoryRefDTO(nativeId, "R_fixture", "shared-repo", NAME, false, null, "main"),
                null,
                null,
                null);
    }

    private void deliver(GitHubRepositoryEventDTO event) {
        handler.dispatchEvent(event, observations.databaseTime());
    }
}
