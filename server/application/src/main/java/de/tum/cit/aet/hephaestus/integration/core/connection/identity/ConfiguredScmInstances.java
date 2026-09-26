package de.tum.cit.aet.hephaestus.integration.core.connection.identity;

import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The SCM instances a user's token may be sent to: each enabled login provider of the type, plus the
 * operator's default instance. A request naming any other server is refused before its token is
 * forwarded or stored, and so is one naming an instance two enabled login providers sign in to, since
 * which of them identifies the user would be a guess.
 */
@Component
public class ConfiguredScmInstances {

    // Login providers live in the server role; without them only the operator's default qualifies.
    private final List<LoginProviderQuery> loginProviders;

    public ConfiguredScmInstances(List<LoginProviderQuery> loginProviders) {
        this.loginProviders = loginProviders;
    }

    /** The canonical origin of the configured instance {@code serverUrl} names; blank names the default. */
    public String require(IdentityProviderType type, @Nullable String serverUrl, String defaultServerUrl) {
        Optional<String> requested =
                ScmOrigin.of(serverUrl == null || serverUrl.isBlank() ? defaultServerUrl : serverUrl);
        if (requested.isEmpty()) {
            throw notConfigured(type);
        }
        long registrations = loginProviders.stream()
                .flatMap(query -> query.enabledProviders().stream())
                .filter(provider -> type.name().equals(provider.type()))
                .map(provider -> ScmOrigin.of(provider.serverUrl()))
                .filter(requested::equals)
                .count();
        if (registrations > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    type + " sign-in is configured more than once for " + requested.get()
                            + ". An instance admin must keep one of them under Instance admin → Login providers.");
        }
        if (registrations == 0 && !requested.equals(ScmOrigin.of(defaultServerUrl))) {
            throw notConfigured(type);
        }
        return requested.get();
    }

    private static ResponseStatusException notConfigured(IdentityProviderType type) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT, "This " + type + " instance is not configured on this server");
    }
}
