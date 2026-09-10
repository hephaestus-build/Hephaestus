package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.metrics.WorkspaceMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Durable backlog gauges survive process restarts; no account, organization or workspace labels are exported. */
@Component
@ConditionalOnServerRole
public class GitHubAccessMetrics {
    public GitHubAccessMetrics(
            MeterRegistry registry, GitHubAccessMembershipRepository members, GitHubAccessActionRepository actions) {
        Gauge.builder(
                        WorkspaceMetrics.GITHUB_ACCESS_PENDING_REMOVALS,
                        members,
                        GitHubAccessMembershipRepository::countPendingRemovals)
                .description("Managed GitHub memberships still awaiting confirmed removal")
                .register(registry);
        Gauge.builder(
                        WorkspaceMetrics.GITHUB_ACCESS_MANUAL_RECOVERY,
                        actions,
                        GitHubAccessActionRepository::countManualRecovery)
                .description("Unconfirmed GitHub actions requiring an explicit owner decision")
                .register(registry);
    }
}
