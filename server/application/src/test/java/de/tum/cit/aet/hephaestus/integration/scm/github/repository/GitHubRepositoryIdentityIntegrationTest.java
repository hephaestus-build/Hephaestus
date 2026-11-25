package de.tum.cit.aet.hephaestus.integration.scm.github.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
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
            assertThat(row.getVisibility()).isEqualTo(Repository.Visibility.PUBLIC);
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
        assertThat(List.of(proof(first), proof(second))).containsOnly("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");

        deliver(event("privatized", OLD_ID));

        assertThat(List.of(proof(first), proof(second)))
                .as("GitHub's event narrows every workspace that monitors the repository before its next sync")
                .containsOnly("UNPROVEN PENDING AWAITING_READERS");
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
        syncTargets.recordRepositoryUnavailable(
                Objects.requireNonNull(
                                monitors.findById(unavailable).orElseThrow().getWorkspace())
                        .getId(),
                unavailable);

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
    void shouldNarrowEveryWorkspacesMonitorWhenTheStoredRepositoryGoes() {
        Repository stored = repository(ensureGitHubProvider(), OLD_ID);
        long first = publicMonitor("stored-gone-first");
        long second = publicMonitor("stored-gone-second");

        transactions.executeWithoutResult(status -> repositories.deleteById(stored.getId()));

        assertThat(List.of(proof(first), proof(second))).containsOnly("UNPROVEN PENDING AWAITING_READERS");
    }

    /**
     * The mirror row held "public, but flagged private" from a {@code publicized} event when a sync's read saw the
     * repository public. GitHub then reported it private before that sync stored its answer. The read began before
     * that narrowing, so its public answer does not apply.
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
                monitor, OLD_ID, Objects.requireNonNull(ensureGitHubProvider().getId()), Instant.now());

        deliver(event("privatized", OLD_ID));
        transactions.executeWithoutResult(status -> {
            Repository row = repositories.findById(stored.getId()).orElseThrow();
            row.setVisibility(Repository.Visibility.PUBLIC);
            row.setPrivate(false);
            repositories.saveAndFlush(row);
        });
        syncTargets.recordRepositoryPublic(readOfThePublicRepository);

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
                monitor, OLD_ID, Objects.requireNonNull(ensureGitHubProvider().getId()), Instant.now());

        deliver(event("privatized", OLD_ID));
        syncTargets.recordRepositoryPublic(earlierRead);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
    }

    /** A sync stores a private repository as private again: that is an answer too, whatever the row held before. */
    @Test
    void shouldRefuseAnEarlierPublicAnswerWhenASyncStoresThePrivateRepositoryAgain() {
        Repository stored = repository(ensureGitHubProvider(), OLD_ID);
        storeAsPrivate(stored);
        long monitor = publicMonitor("private-again");
        var earlierRead = syncTargets.beginReadersProof(
                monitor, OLD_ID, Objects.requireNonNull(ensureGitHubProvider().getId()), Instant.now());

        transactions.executeWithoutResult(status -> {
            Repository row = repositories.findById(stored.getId()).orElseThrow();
            row.setLastSyncAt(Instant.now());
            repositories.saveAndFlush(row);
        });
        syncTargets.recordRepositoryPublic(earlierRead);

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
        syncTargets.recordRepositoryPublic(syncTargets.beginReadersProof(
                id, OLD_ID, Objects.requireNonNull(ensureGitHubProvider().getId()), Instant.now()));
        return id;
    }

    private String proof(long monitorId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT visibility_class || ' ' || proof_state || ' ' || proof_reason FROM repository_to_monitor"
                        + " WHERE id = ?",
                String.class,
                monitorId));
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
        transactions.executeWithoutResult(status -> handler.handleEvent(event));
    }
}
