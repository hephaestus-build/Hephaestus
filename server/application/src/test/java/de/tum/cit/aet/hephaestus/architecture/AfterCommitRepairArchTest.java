package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 20 plain after-commit listeners reviewed for ADR 0099, including the two repaired here.
 * Other module listeners already declare their registry repair through {@link ApplicationModuleListener}.
 * A new plain listener, including one using the default phase or a composed annotation, needs its own repair entry.
 */
class AfterCommitRepairArchTest extends HephaestusArchitectureTest {
    private enum RepairKind {
        POLL,
        RECONCILE,
        DURABLE,
        ATOMIC,
    }

    private record Repair(RepairKind kind, String mechanism, String sourceMethod, boolean scheduled) {}

    private record Unrepaired(String owner, String reason) {}

    private static final Map<String, Unrepaired> UNREPAIRED = Map.of(
            "integration.scm.gitlab.workspace.GitLabConnectionStateListener.onDeactivated",
            new Unrepaired(
                    "INBOX",
                    "A lost suspension callback leaves the upstream hook delivering to a valid signed route; teardown and hook reconciliation do not cover SUSPENDED connections."));

    private static final Map<String, Repair> REPAIRS = Map.ofEntries(
            Map.entry(
                    "core.auth.webauthn.PasskeySecurityEvents.success",
                    new Repair(
                            RepairKind.ATOMIC,
                            "AuthEventWriter.writeInCurrentTransaction writes the success audit before commit; audit failure rolls back the passkey change.",
                            "core.auth.audit.AuthEventWriter.writeInCurrentTransaction",
                            false)),
            Map.entry(
                    "agent.job.FeedbackRepairWake.observed",
                    new Repair(
                            RepairKind.DURABLE,
                            "The publication registry and NotificationRedeliveryJob redeliver the wake; ReviewRunKind.resumeComposition locks and changes only a COMPLETED job.",
                            "notification.NotificationRedeliveryJob.resubmitFailed",
                            true)),
            Map.entry(
                    "mentor.MentorTurnWaits.publishWaits",
                    new Repair(
                            RepairKind.RECONCILE,
                            "MentorTimeline reads recompute waits from committed turn rows; the next SlotsChanged event republishes claimable turns across workspaces.",
                            "mentor.MentorTimeline.conversation",
                            false)),
            Map.entry(
                    "integration.core.sync.push.SyncPushService.onSyncStateChanged",
                    new Repair(
                            RepairKind.POLL,
                            "useConnectionSync refetches status, resources and jobs through syncPollInterval when push is unavailable or a job is active.",
                            "integration.core.sync.api.SyncController.getConnectionSyncStatus",
                            false)),
            Map.entry(
                    "integration.core.sync.push.SyncPushService.onConnectionActivated",
                    new Repair(
                            RepairKind.POLL,
                            "useConnectionSync refetches the committed connection status and ledgers; push only invalidates those reads.",
                            "integration.core.sync.api.SyncController.getConnectionSyncStatus",
                            false)),
            Map.entry(
                    "integration.core.sync.push.SyncPushService.onConnectionDeactivated",
                    new Repair(
                            RepairKind.POLL,
                            "useConnectionSync refetches the committed connection status and ledgers; push only invalidates those reads.",
                            "integration.core.sync.api.SyncController.getConnectionSyncStatus",
                            false)),
            Map.entry(
                    "integration.core.sync.activity.ConnectionActivityRecorder.onActivated",
                    new Repair(
                            RepairKind.RECONCILE,
                            "A process restart discards connectionIdCache; the next recordEventProcessed resolves the current ACTIVE connection.",
                            "integration.core.sync.activity.ConnectionActivityRecorder.recordEventProcessed",
                            false)),
            Map.entry(
                    "integration.core.sync.activity.ConnectionActivityRecorder.onDeactivated",
                    new Repair(
                            RepairKind.RECONCILE,
                            "A process restart discards connectionIdCache; the next recordEventProcessed resolves the current ACTIVE connection.",
                            "integration.core.sync.activity.ConnectionActivityRecorder.recordEventProcessed",
                            false)),
            Map.entry(
                    "integration.slack.sync.SlackDataSyncScheduler.onChannelConsentActivated",
                    new Repair(
                            RepairKind.POLL,
                            "SlackDataSyncScheduler.syncDataCron scans all ACTIVE monitored channels and synchronizes their history again.",
                            "integration.slack.sync.SlackDataSyncScheduler.syncDataCron",
                            true)),
            Map.entry(
                    "integration.slack.channel.SlackChannelConsentProjection.onConsentChanged",
                    new Repair(
                            RepairKind.RECONCILE,
                            "SlackChannelConsentProjection.rebuild replaces the consent projection at startup and every minute from ACTIVE channel rows.",
                            "integration.slack.channel.SlackChannelConsentProjection.rebuild",
                            true)),
            Map.entry(
                    "integration.slack.lifecycle.SlackScopeConsumerReconciler.onActivated",
                    new Repair(
                            RepairKind.RECONCILE,
                            "WorkspaceConsumerReconciler.reconcile starts and updates broker consumers for each active workspace every minute.",
                            "workspace.WorkspaceConsumerReconciler.reconcile",
                            true)),
            Map.entry(
                    "integration.slack.lifecycle.SlackScopeConsumerReconciler.onDeactivated",
                    new Repair(
                            RepairKind.RECONCILE,
                            "WorkspaceConsumerReconciler.reconcile rebuilds active workspaces' consumer subjects every minute from current connections.",
                            "workspace.WorkspaceConsumerReconciler.reconcile",
                            true)),
            Map.entry(
                    "integration.outline.lifecycle.OutlineConnectionStateListener.onActivated",
                    new Repair(
                            RepairKind.POLL,
                            "OutlineDocumentSyncScheduler.syncAll repeats document sync; OutlineDocumentSyncService calls ensureSubscription on a full reconcile.",
                            "integration.outline.sync.OutlineDocumentSyncScheduler.syncAll",
                            true)),
            Map.entry(
                    "integration.outline.lifecycle.OutlineConnectionStateListener.onDeactivated",
                    new Repair(
                            RepairKind.RECONCILE,
                            "ProviderTeardown durably owns disconnect deletion; OutlineRouteProjection.rebuild removes inactive routes, so an orphan cannot deliver and Outline disables it.",
                            "integration.outline.lifecycle.OutlineRouteProjection.rebuild",
                            true)),
            Map.entry(
                    "integration.outline.collection.OutlineCollectionAdminService.onCollectionResumed",
                    new Repair(
                            RepairKind.POLL,
                            "OutlineDocumentSyncScheduler.catchUp polls enabled PENDING collections and syncAll also revisits them.",
                            "integration.outline.sync.OutlineDocumentSyncScheduler.catchUp",
                            true)),
            Map.entry(
                    "integration.scm.github.commit.CommitEnrichmentEventListener.onCommitCreated",
                    new Repair(
                            RepairKind.POLL,
                            "GitHubDataSyncScheduler runs GitHubDataSyncService enrichment, which selects commits still missing contributor rows.",
                            "integration.scm.github.sync.GitHubDataSyncScheduler.syncDataCron",
                            true)),
            Map.entry(
                    "integration.scm.github.workspace.GitHubConnectionStateListener.onActivated",
                    new Repair(
                            RepairKind.POLL,
                            "GitHubDataSyncScheduler.syncDataCron revisits active PAT connections and their monitored repositories.",
                            "integration.scm.github.sync.GitHubDataSyncScheduler.syncDataCron",
                            true)),
            Map.entry(
                    "integration.scm.gitlab.workspace.GitLabConnectionStateListener.onActivated",
                    new Repair(
                            RepairKind.POLL,
                            "GitLabDataSyncScheduler.syncDataCron invokes initializeIfWebhookMissing before synchronizing repositories.",
                            "integration.scm.gitlab.sync.GitLabDataSyncScheduler.syncDataCron",
                            true)),
            Map.entry(
                    "integration.scm.gitlab.credentials.GitLabTokenLifecycleService.onReplaced",
                    new Repair(
                            RepairKind.RECONCILE,
                            "GitLabTokenService expires its validation cache after tokenValidationCacheDuration; a process restart also empties it.",
                            "integration.scm.gitlab.common.GitLabTokenService.validateToken",
                            false)));

    @Test
    void shouldNameARepairForEveryPlainAfterCommitListener() {
        var methods =
                classes.stream().flatMap(owner -> owner.getMethods().stream()).toList();
        var inventoried = methods.stream()
                .filter(method -> REPAIRS.containsKey(id(method))
                        || UNREPAIRED.containsKey(id(method))
                        || isPlainAfterCommitListener(method))
                .toList();

        // Exact membership also rejects stale entries and overloaded listeners sharing an inventory key.
        assertThat(inventoried.stream().map(AfterCommitRepairArchTest::id).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Stream.concat(REPAIRS.keySet().stream(), UNREPAIRED.keySet().stream())
                                .toList());

        for (JavaMethod method : inventoried) {
            Unrepaired gap = UNREPAIRED.get(id(method));
            if (gap != null) {
                assertThat(gap.owner()).as(id(method)).isNotBlank();
                assertThat(gap.reason()).as(id(method)).isNotBlank();
                assertThat(isPlainAfterCommitListener(method))
                        .as("Remove the repaired listener from UNREPAIRED: %s", id(method))
                        .isTrue();
                continue;
            }
            Repair repair = Objects.requireNonNull(REPAIRS.get(id(method)));
            var listener = Objects.requireNonNull(listener(method));
            assertThat(repair.mechanism()).as(id(method)).isNotBlank();
            var sources = methods.stream()
                    .filter(source -> id(source).equals(repair.sourceMethod()))
                    .toList();
            assertThat(sources)
                    .as("Repair source for %s: %s", id(method), repair.mechanism())
                    .isNotEmpty();
            if (repair.scheduled()) {
                assertThat(sources)
                        .as("Scheduled repair source: %s", repair.sourceMethod())
                        .anySatisfy(source -> assertThat(
                                        AnnotatedElementUtils.hasAnnotation(source.reflect(), Scheduled.class))
                                .isTrue());
            }
            switch (repair.kind()) {
                case ATOMIC -> {
                    assertThat(listener.phase()).as(repair.mechanism()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
                    var transaction = Objects.requireNonNull(
                            AnnotatedElementUtils.findMergedAnnotation(method.reflect(), Transactional.class));
                    assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
                }
                case DURABLE ->
                    assertThat(AnnotatedElementUtils.hasAnnotation(method.reflect(), ApplicationModuleListener.class))
                            .as(repair.mechanism())
                            .isTrue();
                case POLL, RECONCILE ->
                    assertThat(listener.phase()).as(repair.mechanism()).isEqualTo(TransactionPhase.AFTER_COMMIT);
            }
        }
    }

    @Test
    void shouldHaveNoUnrepairedAfterCommitListenersForOnePointZero(TestReporter reporter) throws IOException {
        String version = new JsonMapper()
                .readTree(Files.readString(Path.of("../..", "package.json")))
                .required("version")
                .asString();
        AfterCommitRepairRatchet.check(
                version, UNREPAIRED, summary -> reporter.publishEntry("after-commit-repair-ratchet", summary));
    }

    private static boolean isPlainAfterCommitListener(JavaMethod method) {
        var listener = listener(method);
        return listener != null
                && listener.phase() == TransactionPhase.AFTER_COMMIT
                && !AnnotatedElementUtils.hasAnnotation(method.reflect(), ApplicationModuleListener.class);
    }

    private static @Nullable TransactionalEventListener listener(JavaMethod method) {
        return AnnotatedElementUtils.findMergedAnnotation(method.reflect(), TransactionalEventListener.class);
    }

    private static String id(JavaMethod method) {
        return method.getOwner().getName().substring(BASE_PACKAGE.length() + 1) + "." + method.getName();
    }
}
