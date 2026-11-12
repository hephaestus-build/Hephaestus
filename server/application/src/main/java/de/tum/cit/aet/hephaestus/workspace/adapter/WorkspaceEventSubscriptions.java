package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderEventSubscriptions;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
    private final ConnectionRepository connections;
    private final boolean outlineEnabled;
    private final boolean slackEnabled;
    private final boolean gitlabEnabled;

    public WorkspaceEventSubscriptions(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository monitors,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionRepository connections,
            @Value("${hephaestus.integration.outline.enabled:false}") boolean outlineEnabled,
            @Value("${hephaestus.integration.slack.enabled:false}") boolean slackEnabled,
            @Value("${hephaestus.integration.gitlab.enabled:false}") boolean gitlabEnabled) {
        this.workspaceRepository = workspaceRepository;
        this.monitors = monitors;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connections = connections;
        this.outlineEnabled = outlineEnabled;
        this.slackEnabled = slackEnabled;
        this.gitlabEnabled = gitlabEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Subscription> subscribers(String subject) {
        Optional<IntegrationKind> kind = ConsumerSubjectMath.kindFromSubjectPrefix(subject);
        if (kind.isEmpty() || !enabled(kind.get())) return List.of();
        return candidates(kind.get(), subject).stream()
                .distinct()
                .sorted()
                .flatMap(workspaceId -> workspaceRepository
                        .findById(workspaceId)
                        .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                        .filter(workspaceScopeFilter::isWorkspaceAllowed)
                        .stream()
                        .flatMap(workspace -> connections.findByWorkspaceId(workspaceId).stream()
                                .filter(connection -> connection.getKind() == kind.get())
                                .filter(connection -> filters(workspace, connection).stream()
                                        .anyMatch(filter -> ConsumerSubjectMath.matchesFilter(filter, subject)))
                                .sorted(Comparator.comparing(connection -> Objects.requireNonNull(connection.getId())))
                                .map(connection ->
                                        new Subscription(workspaceId, Objects.requireNonNull(connection.getId())))))
                .toList();
    }

    private boolean enabled(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> true;
            case GITLAB -> gitlabEnabled;
            case SLACK -> slackEnabled;
            case OUTLINE -> outlineEnabled;
        };
    }

    /** The workspaces that may subscribe to {@code subject}: few, so each one's full filter check stays cheap. */
    private List<Long> candidates(IntegrationKind kind, String subject) {
        if (kind != IntegrationKind.GITHUB) {
            return Arrays.stream(IntegrationState.values())
                    .flatMap(state -> connections.findWorkspaceIdsByKindAndState(kind, state).stream())
                    .toList();
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

    /** Route membership and lifecycle admission are separate: the receiver locks and checks the latter. */
    private Set<String> filters(Workspace workspace, Connection connection) {
        Set<String> subjects = new HashSet<>();
        switch (connection.getKind()) {
            case GITHUB -> {
                workspace.getRepositoriesToMonitor().stream()
                        .map(RepositoryToMonitor::getNameWithOwner)
                        .filter(workspaceScopeFilter::isRepositoryAllowed)
                        .forEach(name -> subjects.add(ConsumerSubjectMath.repositoryFilter("github", name)));
                String login = workspace.getAccountLogin();
                if (login != null && !login.isBlank()) {
                    subjects.add(ConsumerSubjectMath.organizationFilter("github", login));
                }
            }
            case GITLAB ->
                subjects.add(
                        ConsumerSubjectMath.connectionFilter("gitlab", Objects.requireNonNull(connection.getId())));
            case SLACK -> {
                if (connection.getConfig() instanceof ConnectionConfig.SlackConfig config
                        && config.teamId() != null
                        && !config.teamId().isBlank()) {
                    subjects.add(ConsumerSubjectMath.teamFilter("slack", config.teamId()));
                }
            }
            case OUTLINE -> {
                if (connection.getConfig() instanceof ConnectionConfig.OutlineConfig config
                        && config.webhookSubscriptionId() != null
                        && !config.webhookSubscriptionId().isBlank()) {
                    subjects.add(ConsumerSubjectMath.subscriptionFilter("outline", config.webhookSubscriptionId()));
                }
            }
        }
        return subjects;
    }
}
