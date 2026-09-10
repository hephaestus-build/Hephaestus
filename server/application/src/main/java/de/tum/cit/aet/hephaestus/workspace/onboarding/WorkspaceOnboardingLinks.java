package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class WorkspaceOnboardingLinks {
    private final ConnectionRepository connections;
    private final LoginProviderQuery providers;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry registry;

    List<WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO> options(
            long workspaceId, long accountId, List<Long> required) {
        var links = identities.activeLinksForAccount(accountId);
        var enabledProviders = providers.enabledProviders();
        var result = new java.util.ArrayList<WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO>();
        for (var connection : connections.findByWorkspaceId(workspaceId)) {
            if (connection.getKind() != IntegrationKind.SLACK && connection.getKind() != IntegrationKind.OUTLINE)
                continue;
            String type = connection.getKind().name();
            String server = serverUrl(connection);
            String team = teamId(connection);
            var provider = enabledProviders.stream()
                    .filter(p -> p.type().equals(type)
                            && origin(server) != null
                            && Objects.equals(origin(p.serverUrl()), origin(server)))
                    .findFirst()
                    .orElse(null);
            boolean available = connection.getState() == IntegrationState.ACTIVE
                    && provider != null
                    && team != null
                    && !team.isBlank();
            boolean linked = team != null
                    && !team.isBlank()
                    && origin(server) != null
                    && links.stream()
                            .anyMatch(link -> type.equals(registry.providerTypeName(link.gitProviderId()))
                                    && Objects.equals(
                                            origin(registry.providerServerUrl(link.gitProviderId())), origin(server))
                                    && team.equals(link.teamId()));
            long id = Objects.requireNonNull(connection.getId());
            String name = connection.getDisplayName();
            result.add(new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(
                    id,
                    name == null || name.isBlank() ? type.equals("SLACK") ? "Slack" : "Outline" : name,
                    type,
                    provider == null ? null : provider.registrationId(),
                    teamName(connection),
                    required.contains(id),
                    available,
                    linked));
        }
        // A removed required integration stays visible as a configuration problem, never a silent waiver.
        for (long id : required)
            if (result.stream().noneMatch(link -> link.connectionId() == id)) {
                result.add(new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(
                        id, "Unavailable integration", "UNKNOWN", null, null, true, false, false));
            }
        return List.copyOf(result);
    }

    private static @Nullable String serverUrl(Connection connection) {
        return switch (connection.getConfig()) {
            case ConnectionConfig.SlackConfig ignored -> "https://slack.com";
            case ConnectionConfig.OutlineConfig outline -> outline.serverUrl();
            default -> null;
        };
    }

    private static @Nullable String teamId(Connection connection) {
        return connection.getConfig() instanceof ConnectionConfig.SlackConfig slack
                ? slack.teamId()
                : connection.getInstanceKey();
    }

    private static @Nullable String teamName(Connection connection) {
        return connection.getConfig() instanceof ConnectionConfig.SlackConfig slack ? slack.teamName() : null;
    }

    private static @Nullable String origin(@Nullable String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            int port = uri.getPort();
            boolean defaultPort = port < 0
                    || (port == 443 && uri.getScheme().equalsIgnoreCase("https"))
                    || (port == 80 && uri.getScheme().equalsIgnoreCase("http"));
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(java.util.Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
