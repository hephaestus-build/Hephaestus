package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClient;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Where an OAuth callback that began as an installed-client sign-in may end. Only an authenticated,
 * unexpired intent cookie (as {@link AuthIntentCookie#read} returns it) whose exact client and callback
 * are still registered yields a callback to redirect to; the redirect is rebuilt from the registry, not
 * from the cookie.
 */
sealed interface ClientCallback {

    /** Not an installed-client sign-in, or no valid intent: a browser sign-in. */
    record None() implements ClientCallback {}

    /** An installed-client sign-in whose client or callback is no longer registered. */
    record Unregistered() implements ClientCallback {}

    record Registered(InstalledClient client, AuthIntentCookie.Intent.ClientRequest request)
            implements ClientCallback {}

    static ClientCallback of(AuthIntentCookie.@Nullable Intent intent, InstalledClientRegistry registry) {
        AuthIntentCookie.Intent.ClientRequest request = intent == null ? null : intent.clientRequestOrNull();
        if (request == null) {
            return new None();
        }
        return registry.find(request.clientId(), request.redirectUri())
                .<ClientCallback>map(client -> new Registered(client, request))
                .orElseGet(Unregistered::new);
    }
}
