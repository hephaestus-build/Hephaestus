package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryRef;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmMonitoredRepositoryQuery implements MonitoredRepositoryQuery {
    private final ScmItemQueryRepository queries;
    private final ConnectionService connections;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, MonitoredRepositoryRef> repositories(long workspaceId) {
        var kind = connections.findActiveProviderKind(workspaceId);
        if (kind.isEmpty()) return Map.of();
        var connection = connections.findActive(workspaceId, kind.orElseThrow());
        if (connection.isEmpty()) return Map.of();
        var origin = PersonSourceNamespace.from(
                        kind.orElseThrow().name(),
                        SourceProviderNamespaces.configuredUrl(
                                connection.orElseThrow().getConfig()))
                .flatMap(namespace -> ScmOrigin.of(namespace.serverUrl()));
        if (origin.isEmpty()) return Map.of();
        Map<Long, MonitoredRepositoryRef> found = new LinkedHashMap<>();
        for (var row : queries.monitored(
                workspaceId,
                ScmItemEvents.REPOSITORY.value(),
                kind.orElseThrow().name())) {
            if (!origin.equals(ScmOrigin.of(row.getServerUrl()))) continue;
            var ref = new MonitoredRepositoryRef(
                    row.getItemId(), row.getMonitorId(), row.getNameWithOwner(), row.getDefaultBranch());
            if (found.putIfAbsent(ref.repositoryItemId(), ref) != null)
                throw new IllegalStateException("Repository item has ambiguous monitor identity");
        }
        return Map.copyOf(found);
    }
}
