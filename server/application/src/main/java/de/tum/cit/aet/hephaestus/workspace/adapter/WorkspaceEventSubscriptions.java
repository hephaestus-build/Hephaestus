package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderEventSubscriptions;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace receives the deliveries that its subscription matches: GitHub events of its monitored repositories and of
 * its organization, every event of its GitLab connection's hook, and the events of its Slack team and its Outline
 * subscription. An inactive workspace, or one outside the instance's scope filter, receives none.
 */
@Component
public class WorkspaceEventSubscriptions implements ProviderEventSubscriptions {

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository monitors;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final ConnectionService connectionService;
    private final boolean outlineEnabled;
    private final boolean slackEnabled;
    private final boolean gitlabEnabled;

    public WorkspaceEventSubscriptions(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository monitors,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            @Value("${hephaestus.integration.outline.enabled:false}") boolean outlineEnabled,
            @Value("${hephaestus.integration.slack.enabled:false}") boolean slackEnabled,
            @Value("${hephaestus.integration.gitlab.enabled:false}") boolean gitlabEnabled) {
        this.workspaceRepository = workspaceRepository;
        this.monitors = monitors;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.outlineEnabled = outlineEnabled;
        this.slackEnabled = slackEnabled;
        this.gitlabEnabled = gitlabEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> subscribers(String subject) {
        Optional<IntegrationKind> kind = ConsumerSubjectMath.kindFromSubjectPrefix(subject);
        if (kind.isEmpty()) {
            return List.of();
        }
        return candidates(kind.get(), subject).stream()
                .distinct()
                .sorted()
                .filter(workspaceId -> filters(workspaceId).stream()
                        .anyMatch(filter -> ConsumerSubjectMath.matchesFilter(filter, subject)))
                .toList();
    }

    /** The workspaces that may subscribe to {@code subject}: few, so each one's full filter check stays cheap. */
    private List<Long> candidates(IntegrationKind kind, String subject) {
        if (kind != IntegrationKind.GITHUB) {
            return connectionService.findWorkspaceIdsWithConnection(kind, IntegrationState.ACTIVE);
        }
        String[] tokens = subject.split("\\.", -1);
        if (tokens.length != 4 || "?".equals(tokens[1])) {
            return List.of();
        }
        String owner = tokens[1].replace('~', '.');
        if ("?".equals(tokens[2])) {
            return workspaceRepository.findAllByAccountLoginIgnoreCase(owner).stream()
                    .map(Workspace::getId)
                    .toList();
        }
        return monitors.findWorkspaceIdsByNameWithOwner(owner + "/" + tokens[2].replace('~', '.'));
    }

    /** The subject filters of an active workspace in scope, or none. */
    private Set<String> filters(long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .filter(workspaceScopeFilter::isWorkspaceAllowed)
                .map(this::toFilters)
                .orElse(Set.of());
    }

    private Set<String> toFilters(Workspace workspace) {
        Set<String> filters = new HashSet<>();
        addScmSubscription(workspace, filters);
        addOutlineSubscription(workspace, filters);
        addSlackSubscription(workspace, filters);
        return filters;
    }

    /**
     * The SCM stream subscription. Added only when the workspace has an ACTIVE SCM connection: without one there is no
     * stream to bind, so an Outline-only workspace is never mislabeled onto the {@code github} stream. GitHub subscribes
     * to its monitored repositories and its organization; GitLab to everything the connection's own hook delivers,
     * including projects not monitored yet, since route admission decides.
     */
    private void addScmSubscription(Workspace workspace, Set<String> out) {
        Optional<IntegrationKind> scmKind = connectionService.findActiveProviderKind(workspace.getId());
        if (scmKind.isEmpty()) {
            return;
        }
        if (scmKind.get() == IntegrationKind.GITLAB && !gitlabEnabled) return;
        String streamName = ConsumerSubjectMath.streamNameFor(scmKind.get())
                .orElseThrow(() -> new IllegalStateException("No NATS stream for SCM kind=" + scmKind.get()));

        Set<String> subjects = new HashSet<>();
        if (scmKind.get() == IntegrationKind.GITLAB) {
            connectionService
                    .findActive(workspace.getId(), IntegrationKind.GITLAB)
                    .map(Connection::getId)
                    .ifPresent(connectionId ->
                            subjects.add(ConsumerSubjectMath.connectionFilter(streamName, connectionId)));
        } else {
            workspace.getRepositoriesToMonitor().stream()
                    .map(RepositoryToMonitor::getNameWithOwner)
                    .filter(workspaceScopeFilter::isRepositoryAllowed)
                    .forEach(nameWithOwner ->
                            subjects.add(ConsumerSubjectMath.repositoryFilter(streamName, nameWithOwner)));
            String organizationLogin = workspace.getAccountLogin();
            if (organizationLogin != null && !organizationLogin.isBlank()) {
                subjects.add(ConsumerSubjectMath.organizationFilter(streamName, organizationLogin));
            }
        }
        out.addAll(subjects);
    }

    /**
     * The Outline stream subscription. Added when the workspace has an ACTIVE Outline connection with
     * a registered change-notification subscription id, filtered to {@code outline.<subId>.>}.
     *
     * <p>Gated on {@code hephaestus.integration.outline.enabled}: with the flag off, every Outline
     * message handler bean is absent, so a durable consumer bound here would pull messages and
     * dispatch them to nothing (redelivery → dead-letter). A stale ACTIVE connection row left over
     * from an enabled deployment must not resurrect the binding.
     */
    private void addOutlineSubscription(Workspace workspace, Set<String> out) {
        if (!outlineEnabled) {
            return;
        }
        connectionService
                .findActiveOutlineConfig(workspace.getId())
                .map(ConnectionConfig.OutlineConfig::webhookSubscriptionId)
                .filter(subscriptionId -> subscriptionId != null && !subscriptionId.isBlank())
                .ifPresent(
                        subscriptionId -> out.add(ConsumerSubjectMath.subscriptionFilter("outline", subscriptionId)));
    }

    /**
     * The Slack stream subscription. Added when the workspace has an ACTIVE Slack connection with a
     * team id, filtered to {@code slack.<team>.>}: one consumer per workspace, so one team's message
     * burst never delays another workspace's ingest and per-workspace ordering is preserved.
     *
     * <p>Gated on {@code hephaestus.integration.slack.enabled} for the same reason as the Outline arm
     * above: the Slack message handlers are themselves flag-gated, so binding the consumer while the
     * flag is off produces deliveries with no handler.
     */
    private void addSlackSubscription(Workspace workspace, Set<String> out) {
        if (!slackEnabled) {
            return;
        }
        connectionService
                .findSlackConfig(workspace.getId())
                .map(ConnectionConfig.SlackConfig::teamId)
                .filter(teamId -> teamId != null && !teamId.isBlank())
                .ifPresent(teamId -> out.add(ConsumerSubjectMath.teamFilter("slack", teamId)));
    }
}
