package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.testconfig.InboxTestMessages;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

class DeliveryInboxIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Autowired
    private ConsumerInboxStepRepository workspaceSteps;

    @Autowired
    private InstallationInboxStepRepository installationSteps;

    @Autowired
    private WorkspaceRepository inboxWorkspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private WebhookProperties configuredWebhook;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    private String consumerName;
    private DeliveryInbox inbox;
    private Workspace workspace;
    private Workspace other;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        consumerName = "inbox-" + unique;
        var owner = persistUser("admin");
        workspace = createWorkspace("inbox-a-" + unique, "A", "inbox-a", AccountType.ORG, owner);
        other = createWorkspace("inbox-b-" + unique, "B", "inbox-b", AccountType.ORG, owner);
        inbox = inboxAt(NOW);
    }

    @Test
    void shouldSkipTheHandlerWhenRedeliveredAfterCommit() {
        Message message = delivery(1, NOW);
        inbox.dispatch(workspace.getId(), message, () -> append(workspace, "persist", "!"));
        inbox.dispatch(workspace.getId(), message, () -> {
            throw new AssertionError("A completed delivery must not run its preparation again");
        });
        assertThat(name(workspace)).isEqualTo("A!");
        assertThat(hasStep(workspace, message, "persist")).isTrue();
        assertThat(hasStep(workspace, message, "complete")).isTrue();
    }

    @Test
    void shouldKeepCommittedItemsAndRetryOnlyTheRolledBackItemAfterPartialFailure() {
        Message message = delivery(2, NOW);
        assertThatThrownBy(() -> inbox.dispatch(workspace.getId(), message, () -> {
                    append(workspace, "item.41", "1");
                    transactions.executeWithoutResult(status -> {
                        append(workspace, "item.82", "2");
                        throw new IllegalStateException("Lost the second step before commit");
                    });
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(name(workspace)).isEqualTo("A1");
        assertThat(hasStep(workspace, message, "item.41")).isTrue();
        assertThat(hasStep(workspace, message, "item.82")).isFalse();
        assertThat(hasStep(workspace, message, "complete")).isFalse();

        // Enumeration order can change: the stable item identity, rather than its position, is the key.
        inbox.dispatch(workspace.getId(), message, () -> {
            append(workspace, "item.82", "2");
            append(workspace, "item.41", "1");
        });
        assertThat(name(workspace)).isEqualTo("A12");
        assertThat(hasStep(workspace, message, "complete")).isTrue();
    }

    @Test
    void shouldClaimOnTheActualWorkerThreadAndCommitOnlyOneConcurrentEffect() throws Exception {
        Message message = delivery(3, NOW);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Runnable work = () -> {
                ready.countDown();
                await(start);
                inbox.dispatch(workspace.getId(), message, () -> append(workspace, "persist", "!"));
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
    void shouldKeepWorkspaceAndInstallationDeliveriesIndependentAndRejectReusedSequenceIdentity() {
        Message message = delivery(4, NOW);
        inbox.dispatch(workspace.getId(), message, () -> append(workspace, "persist", "!"));
        inbox.dispatch(other.getId(), message, () -> append(other, "persist", "!"));
        inbox.dispatch(null, message, () -> append(workspace, "persist", "i"));
        inbox.dispatch(workspace.getId(), delivery(4, NOW.plusSeconds(1)), () -> append(workspace, "persist", "r"));
        assertThat(name(workspace)).isEqualTo("A!ir");
        assertThat(name(other)).isEqualTo("B!");
        assertThat(hasInstallationStep(message, "persist")).isTrue();
    }

    @Test
    void shouldRemoveExpiredKeysOnlyAfterTheLongestStreamRetentionAndMargin() throws Exception {
        Message retained = delivery(5, NOW.minus(Duration.ofDays(30)));
        Message expired = delivery(6, NOW.minus(Duration.ofDays(32)));
        for (Message message : new Message[] {retained, expired}) {
            DeliveryInbox earlier = inboxAt(BrokerDelivery.from(message).receivedAt());
            earlier.dispatch(workspace.getId(), message, () -> append(workspace, "persist", "!"));
            earlier.dispatch(null, message, () -> append(other, "persist", "!"));
        }
        var stream = new WebhookProperties.Stream(
                Duration.ofHours(3),
                Duration.ofDays(7),
                Map.of("outline", Duration.ofDays(30)),
                DataSize.ofGigabytes(1),
                Map.of(),
                DataSize.ofGigabytes(16),
                false,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60));
        var properties = new WebhookProperties(
                configuredWebhook.externalUrl(),
                configuredWebhook.secret(),
                configuredWebhook.tokenRotation(),
                configuredWebhook.publish(),
                stream,
                configuredWebhook.shutdown(),
                configuredWebhook.http(),
                configuredWebhook.routing());
        var connection = mock(Connection.class);
        var management = mock(JetStreamManagement.class);
        var info = mock(StreamInfo.class);
        when(connection.jetStreamManagement()).thenReturn(management);
        when(management.getStreamInfo(anyString())).thenReturn(info);
        when(info.getConfiguration())
                .thenReturn(StreamConfiguration.builder()
                        .name("github")
                        .maxAge(Duration.ofDays(7))
                        .build());
        var retention = new DeliveryInboxRetention(
                inbox, properties, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(), connection);
        retention.prune();
        assertThat(hasStep(workspace, retained, "persist")).isTrue();
        assertThat(hasStep(workspace, expired, "persist")).isFalse();
        assertThat(hasInstallationStep(retained, "persist")).isTrue();
        assertThat(hasInstallationStep(expired, "persist")).isFalse();
    }

    @Test
    void shouldCascadeOnlyTheDeletedWorkspacesKeys() {
        Message message = delivery(7, NOW);
        inbox.dispatch(workspace.getId(), message, () -> append(workspace, "persist", "!"));
        inbox.dispatch(other.getId(), message, () -> append(other, "persist", "!"));
        transactions.executeWithoutResult(status -> {
            workspaceMembershipRepository.deleteAllByWorkspaceId(workspace.getId());
            inboxWorkspaces.deleteById(workspace.getId());
        });
        assertThat(hasStep(workspace, message, "persist")).isFalse();
        assertThat(hasStep(other, message, "persist")).isTrue();
    }

    @Test
    void shouldEraseOnlyThePurgedWorkspacesKeysAndKeepInstallationKeys() {
        Message message = delivery(9, NOW);
        inbox.dispatch(workspace.getId(), message, () -> append(workspace, "persist", "!"));
        inbox.dispatch(other.getId(), message, () -> append(other, "persist", "!"));
        inbox.dispatch(null, message, () -> append(other, "persist", "i"));

        lifecycle.suspendWorkspace(workspace.getWorkspaceSlug());
        lifecycle.purgeWorkspace(workspace.getWorkspaceSlug());

        assertThat(hasStep(workspace, message, "persist")).isFalse();
        assertThat(hasStep(other, message, "persist")).isTrue();
        assertThat(hasInstallationStep(message, "persist")).isTrue();
    }

    @Test
    void shouldRefuseClaimsOutsideTheBusinessTransactionAndClearTheScopeAfterFailure() {
        Message message = delivery(8, NOW);
        assertThatThrownBy(() -> inbox.dispatch(workspace.getId(), message, () -> DeliveryInbox.claim("persist")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DeliveryInbox.claim("ordinary-sync")).isTrue();
        assertThat(hasStep(workspace, message, "complete")).isFalse();
    }

    private DeliveryInbox inboxAt(Instant at) {
        return new DeliveryInbox(workspaceSteps, installationSteps, transactions, Clock.fixed(at, ZoneOffset.UTC));
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

    private boolean hasStep(Workspace target, Message message, String step) {
        return Boolean.TRUE.equals(transactions.execute(
                status -> workspaceSteps.contains(target.getId(), BrokerDelivery.from(message), step)));
    }

    private boolean hasInstallationStep(Message message, String step) {
        return Boolean.TRUE.equals(
                transactions.execute(status -> installationSteps.contains(BrokerDelivery.from(message), step)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker did not start");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private Message delivery(long sequence, Instant receivedAt) {
        return InboxTestMessages.delivery(consumerName, sequence, receivedAt);
    }
}
