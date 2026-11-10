package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.ModelLinks;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hosts that a model-written link may point to in a workspace ({@link ModelLinks}): Hephaestus itself, with its
 * workspace subdomains, and the hosts of the workspace's active connections. A Slack workspace links through its
 * team subdomain, so a Slack connection allows every subdomain of {@code slack.com}.
 */
@Service
@Transactional(readOnly = true)
public class ModelLinkHosts {

    private static final String SLACK_HOSTS = ".slack.com";

    private final ConnectionRepository connections;
    private final Set<String> hephaestus;

    public ModelLinkHosts(
            ConnectionRepository connections,
            @Value("${hephaestus.webapp.url:}") String webappUrl,
            WorkspaceSubdomainProperties subdomains) {
        this.connections = connections;
        var own = new HashSet<String>();
        var webapp = host(webappUrl);
        if (webapp != null) own.add(webapp);
        if (subdomains.enabled()) {
            own.add(subdomains.baseDomain());
            own.add("." + subdomains.baseDomain());
        }
        this.hephaestus = Set.copyOf(own);
    }

    /** The allowed hosts of one workspace. */
    public Set<String> forWorkspace(long workspaceId) {
        return forWorkspaces(Set.of(workspaceId)).getOrDefault(workspaceId, hephaestus);
    }

    /** The allowed hosts of each workspace, read in one query. */
    public Map<Long, Set<String>> forWorkspaces(Collection<Long> workspaceIds) {
        return workspaceIds.isEmpty()
                ? Map.of()
                : forConnections(workspaceIds, connections.findActiveWorkspaceConfigs(workspaceIds));
    }

    /** The allowed hosts of each workspace, from its active connections that the caller already read. */
    public Map<Long, Set<String>> forConnections(
            Collection<Long> workspaceIds, List<ConnectionRepository.WorkspaceConfigRow> active) {
        var hosts = new HashMap<Long, Set<String>>();
        for (Long id : workspaceIds) hosts.put(id, new HashSet<>(hephaestus));
        for (var row : active) {
            var allowed = hosts.get(row.workspaceId());
            if (allowed == null) continue;
            switch (row.config()) {
                case ConnectionConfig.GitHubAppConfig app -> addHost(allowed, app.serverUrl());
                case ConnectionConfig.GitHubPatConfig pat -> addHost(allowed, pat.serverUrl());
                case ConnectionConfig.GitLabConfig gitlab -> addHost(allowed, gitlab.serverUrl());
                case ConnectionConfig.OutlineConfig outline -> addHost(allowed, outline.serverUrl());
                case ConnectionConfig.SlackConfig slack -> {
                    allowed.add(SLACK_HOSTS.substring(1));
                    allowed.add(SLACK_HOSTS);
                }
                default -> {}
            }
        }
        hosts.replaceAll((id, allowed) -> Set.copyOf(allowed));
        return hosts;
    }

    private static void addHost(Set<String> allowed, @Nullable String url) {
        var host = host(url);
        if (host != null) allowed.add(host);
    }

    private static @Nullable String host(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        try {
            var host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
