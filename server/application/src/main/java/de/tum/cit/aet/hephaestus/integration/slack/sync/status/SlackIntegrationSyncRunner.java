package de.tum.cit.aet.hephaestus.integration.slack.sync.status;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.slack.sync.SlackChannelHistorySyncService.WorkspaceSyncSummary;
import de.tum.cit.aet.hephaestus.integration.slack.sync.SlackDataSyncScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs Slack reconciliation through the consent-gated history sync. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackIntegrationSyncRunner implements IntegrationSyncRunner {

    private final SlackDataSyncScheduler dataSyncScheduler;

    public SlackIntegrationSyncRunner(SlackDataSyncScheduler dataSyncScheduler) {
        this.dataSyncScheduler = dataSyncScheduler;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    /** Slack has no deletion sweep of its own — {@code type} is unused here. */
    @Override
    public void reconcile(IntegrationRef ref, SyncExecutionHandle handle, SyncJobType type) {
        WorkspaceSyncSummary summary = dataSyncScheduler.syncWorkspaceNow(ref.workspaceId(), handle);
        handle.progress(summary.synced() + summary.skipped(), summary.channels(), progressDetail(summary));
        if (summary.failed() > 0 || summary.budgetExhausted()) {
            handle.reportWarnings();
        }
        if (handle.isCancellationRequested()) {
            handle.reportCancelled();
        }
    }

    /**
     * The terminal summary for a Slack reconcile, in the shared progress shape.
     *
     * <p>Per-channel counters are folded into {@code currentStep} rather than exposed as their own
     * top-level keys: the UI reads {@code currentStep} and nothing else, so any other keys go
     * unrendered.
     */
    public static SyncProgress progressDetail(WorkspaceSyncSummary summary) {
        StringBuilder step = new StringBuilder();
        step.append("Synced ")
                .append(summary.synced())
                .append(" of ")
                .append(summary.channels())
                .append(summary.channels() == 1 ? " channel" : " channels");
        if (summary.ingested() > 0) {
            step.append(" · ").append(summary.ingested()).append(summary.ingested() == 1 ? " message" : " messages");
        }
        if (summary.skipped() > 0) {
            step.append(" · ").append(summary.skipped()).append(" skipped");
        }
        if (summary.failed() > 0) {
            step.append(" · ").append(summary.failed()).append(" failed");
        }
        if (summary.budgetExhausted()) {
            step.append(" · request budget used up");
        }
        return SyncProgress.ofResource(
                SyncPhase.CHANNELS, step.toString(), null, summary.synced() + summary.skipped(), summary.channels());
    }
}
