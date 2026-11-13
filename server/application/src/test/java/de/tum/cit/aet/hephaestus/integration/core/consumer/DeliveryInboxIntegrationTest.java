package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class DeliveryInboxIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Autowired
    private WorkspaceRepository inboxWorkspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DeliveryInbox inbox;

    @Autowired
    private ProviderEventStore store;

    @Autowired
    private ConsumerInboxPurge purge;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private Workspace other;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        var owner = persistUser("admin");
        workspace = createWorkspace("inbox-a-" + unique, "A", "inbox-a", AccountType.ORG, owner);
        other = createWorkspace("inbox-b-" + unique, "B", "inbox-b", AccountType.ORG, owner);
    }

    @Test
    void shouldKeepCommittedItemsAndRetryOnlyTheRolledBackItemAfterPartialFailure() {
        long event = event(workspace.getId());
        assertThatThrownBy(() -> inbox.dispatch(event, 1, NOW, () -> {
                    append(workspace, "item.41", "1");
                    transactions.executeWithoutResult(status -> {
                        append(workspace, "item.82", "2");
                        throw new IllegalStateException("Lost the second step before commit");
                    });
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(name(workspace)).isEqualTo("A1");
        assertThat(store.hasStep(event, "item.41")).isTrue();
        assertThat(store.hasStep(event, "item.82")).isFalse();

        // Enumeration order can change: the stable item identity, rather than its position, is the key.
        inbox.dispatch(event, 1, NOW, () -> {
            append(workspace, "item.82", "2");
            append(workspace, "item.41", "1");
        });
        assertThat(name(workspace)).isEqualTo("A12");
    }

    @Test
    void shouldClaimOnTheActualWorkerThreadAndCommitOnlyOneConcurrentEffect() throws Exception {
        long event = event(workspace.getId());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Runnable work = () -> {
                ready.countDown();
                await(start);
                inbox.dispatch(event, 1, NOW, () -> append(workspace, "persist", "!"));
            };
            var first = workers.submit(work);
            var second = workers.submit(work);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertThat(name(workspace)).isEqualTo("A!");
    }

    @Test
    void shouldKeepTheStepsOfEachEventApart() {
        long first = event(workspace.getId());
        long second = event(workspace.getId());
        inbox.dispatch(first, 1, NOW, () -> append(workspace, "persist", "!"));
        inbox.dispatch(second, 1, NOW, () -> append(workspace, "persist", "?"));
        assertThat(name(workspace)).isEqualTo("A!?");
    }

    @Test
    void shouldEraseOnlyThePurgedWorkspacesEventsAndKeepInstallationEvents() {
        long purged = event(workspace.getId());
        long kept = event(other.getId());
        long installation = event(null);
        inbox.dispatch(purged, 1, NOW, () -> append(workspace, "persist", "!"));

        purge.deleteWorkspaceData(workspace.getId());

        assertThat(exists(purged)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event_step WHERE event_id = ?", Long.class, purged))
                .isZero();
        assertThat(exists(kept)).isTrue();
        assertThat(exists(installation)).isTrue();
    }

    @Test
    void shouldRefuseClaimsOutsideTheBusinessTransactionAndClearTheScopeAfterFailure() {
        long event = event(workspace.getId());
        assertThatThrownBy(() -> inbox.dispatch(event, 1, NOW, () -> DeliveryInbox.claim("persist")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DeliveryInbox.claim("ordinary-sync")).isTrue();
        assertThat(store.hasStep(event, "persist")).isFalse();
    }

    private long event(@Nullable Long workspaceId) {
        String delivery = UUID.randomUUID().toString();
        store.store(List.of(new ProviderEventStore.Arrival(
                workspaceId,
                "github",
                "github.acme.repo-" + delivery + ".issues",
                delivery,
                Map.of(),
                "{}".getBytes(StandardCharsets.UTF_8))));
        long id = Objects.requireNonNull(
                jdbc.queryForObject("SELECT id FROM provider_event WHERE delivery_key = ?", Long.class, delivery));
        jdbc.update(
                "UPDATE provider_event SET state='LEASED', lease_epoch=1, leased_until=clock_timestamp()+interval '5 minutes' WHERE id=?",
                id);
        return id;
    }

    private boolean exists(long event) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM provider_event WHERE id = ?)", Boolean.class, event));
    }

    private void append(Workspace target, String step, String suffix) {
        transactions.executeWithoutResult(status -> {
            if (!DeliveryInbox.claim(step)) return;
            Workspace current = inboxWorkspaces.findById(target.getId()).orElseThrow();
            current.setDisplayName(current.getDisplayName() + suffix);
            inboxWorkspaces.saveAndFlush(current);
        });
    }

    private String name(Workspace target) {
        return Objects.requireNonNull(transactions.execute(
                status -> inboxWorkspaces.findById(target.getId()).orElseThrow().getDisplayName()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker did not start");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
