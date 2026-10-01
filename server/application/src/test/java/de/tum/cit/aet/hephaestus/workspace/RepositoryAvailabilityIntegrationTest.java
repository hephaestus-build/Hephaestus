package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Exercises the persisted policy, including SQL isolation and concurrent recheck reservations. */
class RepositoryAvailabilityIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceSyncTargetProvider provider;

    private RepositoryToMonitor monitor(String slug) {
        var owner = persistUser(slug + "-owner");
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, owner);
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner("course/project");
        monitor.setNativeId(123L);
        monitor.setIssueBackfillCheckpoint(17);
        return monitors.saveAndFlush(monitor);
    }

    private RepositoryToMonitor reload(RepositoryToMonitor monitor) {
        return monitors.findById(monitor.getId()).orElseThrow();
    }

    @Test
    void shouldRetryOnceThenWaitOneDayWhenRepositoryRemainsUnavailable() {
        var monitor = monitor("unavailable");
        long scope = Objects.requireNonNull(monitor.getWorkspace()).getId();
        long id = monitor.getId();
        assertThat(provider.deferUnavailableRepository(scope, id)).isFalse();
        provider.recordRepositoryUnavailable(scope, id);
        assertThat(reload(monitor).getSyncErrorSummary()).isEqualTo("Repository not found or not accessible");
        assertThat(provider.deferUnavailableRepository(scope, id)).isFalse();
        provider.recordRepositoryUnavailable(scope, id);
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
        provider.recordRepositoryUnavailable(scope, id);
        provider.recordRepositoryUnavailable(scope, id);
        monitors.recordUnavailable(scope, id, Instant.now(), Instant.now().minusSeconds(1));
        var first = CompletableFuture.supplyAsync(() -> provider.deferUnavailableRepository(scope, id));
        var second = CompletableFuture.supplyAsync(() -> provider.deferUnavailableRepository(scope, id));
        assertThat(java.util.List.of(first.join(), second.join())).containsExactlyInAnyOrder(false, true);
    }

    @Test
    void shouldKeepOtherWorkspaceUnchangedWhenAdminRechecksOrRepositoryRecovers() {
        var first = monitor("first");
        var second = monitor("second");
        long scope = Objects.requireNonNull(first.getWorkspace()).getId();
        long otherScope = Objects.requireNonNull(second.getWorkspace()).getId();
        provider.recordRepositoryUnavailable(scope, first.getId());
        provider.recordRepositoryUnavailable(scope, first.getId());
        provider.recordRepositoryUnavailable(otherScope, second.getId());
        provider.recordRepositoryUnavailable(otherScope, second.getId());
        provider.recheckUnavailableRepositories(scope);
        assertThat(provider.deferUnavailableRepository(scope, first.getId())).isFalse();
        assertThat(provider.deferUnavailableRepository(otherScope, second.getId()))
                .isTrue();
        provider.clearRepositoryUnavailable(scope, second.getId());
        assertThat(provider.isRepositoryUnavailable(otherScope, second.getId())).isTrue();
        provider.clearRepositoryUnavailable(scope, first.getId());
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
        provider.recordRepositoryUnavailable(scope, monitor.getId());
        provider.recordRepositoryUnavailable(scope, monitor.getId());
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
        provider.recordRepositoryUnavailable(scope, monitor.getId());
        assertThat(provider.deferUnavailableRepository(scope, monitor.getId())).isFalse();
        provider.retryUnavailableRepository(scope, monitor.getId());
        assertThat(provider.deferUnavailableRepository(scope, monitor.getId())).isFalse();
        assertThat(provider.isRepositoryUnavailable(scope, monitor.getId())).isTrue();
    }
}
