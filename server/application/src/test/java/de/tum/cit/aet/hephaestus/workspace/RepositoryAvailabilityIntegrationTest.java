package de.tum.cit.aet.hephaestus.workspace;

import static de.tum.cit.aet.hephaestus.testconfig.RepositoryObservationTestFixtures.confirm;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryObservationStore;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectProcessor;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the persisted policy, including SQL isolation and concurrent recheck reservations. */
class RepositoryAvailabilityIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private RepositoryObservationStore observations;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceSyncTargetProvider provider;

    @Autowired
    private GitLabProjectProcessor gitLabProjects;

    private RepositoryToMonitor monitor(String slug) {
        return monitor(slug, ensureGitHubProvider());
    }

    private RepositoryToMonitor monitor(String slug, IdentityProvider source) {
        var owner = persistUser(slug + "-owner");
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, owner);
        connectActivitySource(workspace, source);
        jdbc.update("""
                INSERT INTO repository(provider_id, native_id, name, name_with_owner, html_url,
                    visibility, is_archived, is_disabled, is_private)
                VALUES (?, 123, 'project', 'course/project', 'https://example.test/course/project',
                    'UNKNOWN', false, false, false) ON CONFLICT DO NOTHING
                """, source.getId());
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner("course/project");
        monitor.setNativeId(123L);
        monitor.setIssueBackfillCheckpoint(17);
        return monitors.saveAndFlush(monitor);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private void lose(long scope, long monitor) {
        var target = monitors.findById(monitor).orElseThrow();
        provider.recordRepositoryLoss(provider.beginReadersProof(
                scope,
                monitor,
                target.getNativeId(),
                Objects.requireNonNull(ensureGitHubProvider().getId())));
    }

    private RepositoryToMonitor reload(RepositoryToMonitor monitor) {
        return monitors.findById(monitor.getId()).orElseThrow();
    }

    @Test
    void shouldRecoverWithAFreshGitLabAnswerAfterRejectingAnOverlappingAnswer() {
        var source = ensureGitLabProvider();
        var monitor = monitor("gitlab-recovery", source);
        long scope = Objects.requireNonNull(monitor.getWorkspace()).getId();
        long id = monitor.getId();
        long nativeId = monitor.getNativeId();
        long providerId = Objects.requireNonNull(source.getId());
        var project = new GitLabProjectResponse(
                "gid://gitlab/Project/" + nativeId,
                monitor.getNameWithOwner(),
                "project",
                source.getServerUrl() + "/" + monitor.getNameWithOwner(),
                null,
                "private",
                false,
                null,
                null,
                null,
                null);
        var stale = provider.beginReadersProof(scope, id, nativeId, providerId);
        provider.recordRepositoryLoss(stale);
        var lost = reload(monitor);

        assertThat(gitLabProjects.processGraphQlResponse(project, null, source, stale))
                .isNotNull();
        var afterStale = reload(monitor);
        assertThat(afterStale.getUnavailableSince()).isEqualTo(lost.getUnavailableSince());
        assertThat(afterStale.getUnavailableRetryAt()).isEqualTo(lost.getUnavailableRetryAt());
        assertThat(afterStale.getRepositoryVisibilityConfirmedAt()).isNull();

        var fresh = provider.beginReadersProof(scope, id, nativeId, providerId);
        assertThat(gitLabProjects.processGraphQlResponse(project, null, source, fresh))
                .isNotNull();
        var recovered = reload(monitor);
        assertThat(recovered.getUnavailableSince()).isNull();
        assertThat(recovered.getUnavailableRetryAt()).isNull();
        assertThat(recovered.getRepositoryVisibilityConfirmedAt()).isNull();
    }

    @Test
    void shouldRetryOnceThenWaitOneDayWhenRepositoryRemainsUnavailable() {
        var monitor = monitor("unavailable");
        long scope = Objects.requireNonNull(monitor.getWorkspace()).getId();
        long id = monitor.getId();
        assertThat(provider.deferUnavailableRepository(scope, id)).isFalse();
        lose(scope, id);
        assertThat(reload(monitor).getSyncErrorSummary()).isEqualTo("Repository not found or not accessible");
        assertThat(provider.deferUnavailableRepository(scope, id)).isFalse();
        lose(scope, id);
        var retryAt = Objects.requireNonNull(reload(monitor).getUnavailableRetryAt());
        assertThat(retryAt).isAfter(Instant.now().plus(Duration.ofHours(23)));
        assertThat(provider.deferUnavailableRepository(scope, id)).isTrue();
        assertThat(reload(monitor).getIssueBackfillCheckpoint()).isEqualTo(17);
    }

    @Test
    void shouldPermitOnlyOneDailyRecheckWhenTwoPassesCompete() {
        var monitor = monitor("concurrent");
        long scope = Objects.requireNonNull(monitor.getWorkspace()).getId();
        long id = monitor.getId();
        lose(scope, id);
        lose(scope, id);
        jdbc.update(
                "UPDATE repository_to_monitor SET unavailable_retry_at = statement_timestamp()"
                        + " - interval '1 second' WHERE workspace_id = ? AND id = ?",
                scope,
                id);
        var first = CompletableFuture.supplyAsync(() -> provider.deferUnavailableRepository(scope, id));
        var second = CompletableFuture.supplyAsync(() -> provider.deferUnavailableRepository(scope, id));
        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(false, true);
    }

    @Test
    void shouldKeepOtherWorkspaceUnchangedWhenAdminRechecksOrRepositoryRecovers() {
        var first = monitor("first");
        var second = monitor("second");
        long scope = Objects.requireNonNull(first.getWorkspace()).getId();
        long otherScope = Objects.requireNonNull(second.getWorkspace()).getId();
        lose(scope, first.getId());
        lose(scope, first.getId());
        lose(otherScope, second.getId());
        lose(otherScope, second.getId());
        provider.recheckUnavailableRepositories(scope);
        assertThat(provider.deferUnavailableRepository(scope, first.getId())).isFalse();
        assertThat(provider.deferUnavailableRepository(otherScope, second.getId()))
                .isTrue();
        long gitHubId = Objects.requireNonNull(ensureGitHubProvider().getId());
        confirm(observations, provider.beginReadersProof(scope, second.getId(), second.getNativeId(), gitHubId), true);
        assertThat(provider.isRepositoryUnavailable(otherScope, second.getId())).isTrue();
        confirm(observations, provider.beginReadersProof(scope, first.getId(), first.getNativeId(), gitHubId), true);
        assertThat(reload(first).getUnavailableSince()).isNull();
        assertThat(reload(first).getUnavailableRetryAt()).isNull();
        assertThat(reload(first).getSyncErrorSummary()).isNull();
        assertThat(reload(first).getNativeId()).isEqualTo(123L);
        assertThat(reload(first).getIssueBackfillCheckpoint()).isEqualTo(17);
        assertThat(provider.isRepositoryUnavailable(otherScope, second.getId())).isTrue();
    }

    @Test
    void shouldPreserveAvailabilityWhenAnOlderMonitorEntityIsEdited() {
        var monitor = monitor("stale-edit");
        long scope = Objects.requireNonNull(monitor.getWorkspace()).getId();
        lose(scope, monitor.getId());
        lose(scope, monitor.getId());
        var unavailable = reload(monitor);
        monitor.setNameWithOwner("course/renamed");
        monitors.saveAndFlush(monitor);
        var edited = reload(monitor);
        assertThat(edited.getNameWithOwner()).isEqualTo("course/renamed");
        assertThat(edited.getUnavailableSince()).isEqualTo(unavailable.getUnavailableSince());
        assertThat(edited.getUnavailableRetryAt()).isEqualTo(unavailable.getUnavailableRetryAt());
        assertThat(provider.deferUnavailableRepository(scope, monitor.getId())).isTrue();
    }

    @Test
    void shouldKeepNormalRetriesWhenDailyRecheckHasTransientFailure() {
        var monitor = monitor("transient");
        long scope = Objects.requireNonNull(monitor.getWorkspace()).getId();
        lose(scope, monitor.getId());
        assertThat(provider.deferUnavailableRepository(scope, monitor.getId())).isFalse();
        provider.retryUnavailableRepository(scope, monitor.getId());
        assertThat(provider.deferUnavailableRepository(scope, monitor.getId())).isFalse();
        assertThat(provider.isRepositoryUnavailable(scope, monitor.getId())).isTrue();
    }
}
