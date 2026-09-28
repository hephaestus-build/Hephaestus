package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkspaceNatsSubscriptionProvider implements NatsSubscriptionProvider {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceScopeFilter workspaceScopeFilter;
    private final ConnectionService connectionService;
    private final boolean outlineEnabled;
    private final boolean slackEnabled;

    public WorkspaceNatsSubscriptionProvider(
            WorkspaceRepository workspaceRepository,
            WorkspaceScopeFilter workspaceScopeFilter,
            ConnectionService connectionService,
            @Value("${hephaestus.integration.outline.enabled:false}") boolean outlineEnabled,
            @Value("${hephaestus.integration.slack.enabled:false}") boolean slackEnabled) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceScopeFilter = workspaceScopeFilter;
        this.connectionService = connectionService;
        this.outlineEnabled = outlineEnabled;
        this.slackEnabled = slackEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NatsSubscriptionInfo> getSubscriptionInfo(Long scopeId) {
        return workspaceRepository.findById(scopeId).map(this::toSubscriptionInfo);
    }

    private NatsSubscriptionInfo toSubscriptionInfo(Workspace workspace) {
        List<StreamSubscription> subscriptions = new ArrayList<>();
        addScmSubscription(workspace, subscriptions);
        addOutlineSubscription(workspace, subscriptions);
        addSlackSubscription(workspace, subscriptions);
        return new NatsSubscriptionInfo(workspace.getId(), subscriptions);
    }

    /**
     * The SCM stream subscription (repository + organization filters). Added only when the workspace
     * has an ACTIVE SCM connection: without one there is no stream to bind, so an Outline-only
     * workspace is never mislabeled onto the {@code github} stream.
     */
    private void addScmSubscription(Workspace workspace, List<StreamSubscription> out) {
        Optional<IntegrationKind> scmKind = connectionService.findActiveProviderKind(workspace.getId());
        if (scmKind.isEmpty()) {
            return;
        }
        String streamName = ConsumerSubjectMath.streamNameFor(scmKind.get())
                .orElseThrow(() -> new IllegalStateException("No NATS stream for SCM kind=" + scmKind.get()));

        Set<String> subjects = new HashSet<>();
        Set<String> repositoryNames = workspace.getRepositoriesToMonitor().stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .filter(workspaceScopeFilter::isRepositoryAllowed)
                .collect(Collectors.toSet());
        for (String nameWithOwner : repositoryNames) {
            subjects.add(ConsumerSubjectMath.repositoryFilter(streamName, nameWithOwner));
        }
        String organizationLogin = workspace.getAccountLogin();
        if (organizationLogin != null && !organizationLogin.isBlank()) {
            subjects.add(ConsumerSubjectMath.organizationFilter(streamName, organizationLogin));
        }
        // Everything the connection's own hook delivers, including projects not monitored yet; admission decides.
        if (scmKind.get() == IntegrationKind.GITLAB) {
            connectionService
                    .findActive(workspace.getId(), IntegrationKind.GITLAB)
                    .map(Connection::getId)
                    .ifPresent(connectionId ->
                            subjects.add(ConsumerSubjectMath.connectionFilter(streamName, connectionId)));
        }
        if (!subjects.isEmpty()) {
            out.add(new StreamSubscription(streamName, subjects));
        }
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
    private void addOutlineSubscription(Workspace workspace, List<StreamSubscription> out) {
        if (!outlineEnabled) {
            return;
        }
        connectionService
                .findActiveOutlineConfig(workspace.getId())
                .map(ConnectionConfig.OutlineConfig::webhookSubscriptionId)
                .filter(subscriptionId -> subscriptionId != null && !subscriptionId.isBlank())
                .ifPresent(subscriptionId -> out.add(new StreamSubscription(
                        "outline", Set.of(ConsumerSubjectMath.subscriptionFilter("outline", subscriptionId)))));
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
    private void addSlackSubscription(Workspace workspace, List<StreamSubscription> out) {
        if (!slackEnabled) {
            return;
        }
        connectionService
                .findSlackConfig(workspace.getId())
                .map(ConnectionConfig.SlackConfig::teamId)
                .filter(teamId -> teamId != null && !teamId.isBlank())
                .ifPresent(teamId -> out.add(
                        new StreamSubscription("slack", Set.of(ConsumerSubjectMath.teamFilter("slack", teamId)))));
    }
}
