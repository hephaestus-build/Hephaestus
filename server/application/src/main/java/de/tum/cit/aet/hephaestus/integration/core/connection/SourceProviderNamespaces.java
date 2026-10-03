package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProviderInstanceRegistered;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Source-only people need a namespace even when nobody logs in through that provider. */
@Component
@RequiredArgsConstructor
public class SourceProviderNamespaces {
    private final IdentityProviderRepository providers;
    private final PersonDataCopyFence copies;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.MANDATORY)
    public void ensure(IntegrationKind kind, ConnectionConfig config) {
        var namespace = PersonSourceNamespace.from(kind.name(), configuredUrl(config))
                .orElseThrow(() -> new IllegalStateException("An active source requires its exact provider instance"));
        String serverUrl = namespace.serverUrl();
        // Retain the source's URL spelling. Resolver alias closure supplies the canonical comparison;
        // source SQL can then bind the exact connection without a second URL parser in PostgreSQL.
        copies.holdForCapture();
        providers.registerSourceInstance(kind.name(), serverUrl);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.valueOf(kind.name()), serverUrl)
                .orElseThrow(() -> new IllegalStateException("The source namespace was not registered"));
        events.publishEvent(new PersonProviderInstanceRegistered(Objects.requireNonNull(provider.getId())));
    }

    static @Nullable String configuredUrl(ConnectionConfig config) {
        return switch (config) {
            case ConnectionConfig.GitHubAppConfig c -> c.serverUrl();
            case ConnectionConfig.GitHubPatConfig c -> c.serverUrl();
            case ConnectionConfig.GitLabConfig c -> c.serverUrl();
            case ConnectionConfig.SlackConfig ignored -> null;
            case ConnectionConfig.OutlineConfig c -> c.serverUrl();
        };
    }
}
