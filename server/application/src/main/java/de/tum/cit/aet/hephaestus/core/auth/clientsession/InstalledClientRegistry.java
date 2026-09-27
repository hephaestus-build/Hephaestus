package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * The installed clients this instance lets sign in, built once from configuration.
 *
 * <p>Each configured Chrome extension id yields exactly one callback,
 * {@code https://<id>.chromiumapp.org/callback}, and exactly one origin, {@code chrome-extension://<id>},
 * so the callback allowlist and the CORS allowlist cannot drift apart. Redirects are matched by exact
 * string equality, never by prefix or pattern (RFC 8252 §8.10). Server role only: the root security
 * configuration reads its origins when present, and the worker and webhook roles serve no client.
 */
@ConditionalOnServerRole
@Component
@NamedInterface("installed-clients")
public class InstalledClientRegistry {

    /** Chrome derives an extension id from its public key as 32 characters in {@code a-p}. */
    public static final Pattern EXTENSION_ID = Pattern.compile("[a-p]{32}");

    private final Map<String, InstalledClient> byClientId;

    public InstalledClientRegistry(AuthProperties properties) {
        Map<String, InstalledClient> clients = new LinkedHashMap<>();
        for (String id : properties.browserExtensionIds()) {
            if (!EXTENSION_ID.matcher(id).matches()) {
                throw new IllegalStateException(
                        "hephaestus.auth.browser-extension-ids entries must be 32-character Chrome extension ids (a-p)");
            }
            clients.put(
                    id,
                    new InstalledClient(
                            InstalledClientKind.BROWSER_EXTENSION,
                            id,
                            "https://" + id + ".chromiumapp.org/callback",
                            "chrome-extension://" + id));
        }
        this.byClientId = Map.copyOf(clients);
    }

    /** The client registered under {@code clientId} whose callback is exactly {@code redirectUri}. */
    public Optional<InstalledClient> find(String clientId, String redirectUri) {
        return Optional.ofNullable(byClientId.get(clientId))
                .filter(client -> client.redirectUri().equals(redirectUri));
    }

    public boolean isRegistered(String clientId) {
        return byClientId.containsKey(clientId);
    }

    /** The browser origins of every registered client, for the CORS allowlist. */
    public List<String> origins() {
        return byClientId.values().stream()
                .map(InstalledClient::origin)
                .sorted()
                .toList();
    }
}
