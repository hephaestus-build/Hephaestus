package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The decision on the parameters an installed client starts a sign-in with, shared by the federated
 * door ({@code GET /auth/login?mode=client}) and the dev door ({@code GET /auth/dev-login/client}).
 *
 * <p>The client id and redirect are checked first and exactly: until the redirect is known to be a
 * registered callback, nothing, not even an error, may be sent to it. Every later refusal goes back to
 * that callback.
 */
public sealed interface ClientSignInStart {

    /** Unguessable, URL-safe and bounded; echoed verbatim into the callback. */
    Pattern STATE = Pattern.compile("^[A-Za-z0-9._~-]{1,256}$");

    /** The pair is not registered: render the server's own error page, never redirect. */
    record Unregistered() implements ClientSignInStart {}

    /** The pair is registered but the request is malformed: end at the callback with {@code error}. */
    record Refused(String redirect) implements ClientSignInStart {}

    record Accepted(InstalledClient client, String codeChallenge, String state) implements ClientSignInStart {}

    static ClientSignInStart decide(InstalledClientRegistry registry, ClientSignInParameters parameters) {
        String clientId = parameters.clientId();
        String redirectUri = parameters.redirectUri();
        String codeChallenge = parameters.codeChallenge();
        String state = parameters.state();
        Optional<InstalledClient> client =
                clientId == null || redirectUri == null ? Optional.empty() : registry.find(clientId, redirectUri);
        if (client.isEmpty()) {
            return new Unregistered();
        }
        String callback = client.get().redirectUri();
        if (state == null || !STATE.matcher(state).matches()) {
            return new Refused(ClientSignInRedirect.error(callback, "invalid_request", null));
        }
        if (codeChallenge == null
                || !"S256".equals(parameters.codeChallengeMethod())
                || !Pkce.isChallenge(codeChallenge)) {
            return new Refused(ClientSignInRedirect.error(callback, "invalid_request", state));
        }
        return new Accepted(client.get(), codeChallenge, state);
    }
}
