package de.tum.cit.aet.hephaestus.core.auth.provider;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.CacheLoads;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads an OpenID Connect issuer's discovery document (OpenID Connect Discovery 1.0 § 4). The issuer and the document
 * are untrusted network input: the issuer passes the SCM endpoint policy, the fetch connects only through its guard,
 * follows no redirect and has a total deadline, and the document must name the same issuer and HTTPS endpoints.
 *
 * <p>A result, found or not, is kept for {@link #REMEMBER}. So an unreachable issuer never fails the sign-in page,
 * and anonymous page loads cannot make more than one fetch per issuer in that time. Spring's own
 * {@code ClientRegistrations.fromIssuerLocation} is not used: it has its own transport, without the guard.
 */
public class OidcDiscovery {

    static final Duration DEADLINE = Duration.ofSeconds(5);
    static final Duration REMEMBER = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(OidcDiscovery.class);

    /** The endpoints of one issuer. {@code issuer} is the document's own spelling, which ID tokens must carry. */
    public record Endpoints(String issuer, String authorizationUri, String tokenUri, String jwkSetUri) {}

    /** The endpoints, or the reason, in words an admin can act on, why there are none. */
    public record Result(@Nullable Endpoints endpoints, String reason) {}

    private final ScmServerEndpointPolicy egress;
    private final ObjectMapper objectMapper;
    private final AsyncCache<String, Result> results =
            Caffeine.newBuilder().expireAfterWrite(REMEMBER).maximumSize(64).buildAsync();

    public OidcDiscovery(ScmServerEndpointPolicy egress, ObjectMapper objectMapper) {
        this.egress = egress;
        this.objectMapper = objectMapper;
    }

    /** The cached endpoints of {@code issuer}. One fetch at a time for each issuer. Call it with no transaction. */
    public Result discover(String issuer) {
        return CacheLoads.get(results, issuer, this::fetch);
    }

    /** Fetches again, for an admin who saves the issuer now. Call it with no transaction. */
    public Result refresh(String issuer) {
        Result result = fetch(issuer);
        results.put(issuer, CompletableFuture.completedFuture(result));
        return result;
    }

    /**
     * Checks an issuer as an admin enters it: HTTPS (or the e2e simulator), no credentials, query or fragment, and an
     * origin that the SCM endpoint policy admits. A path is allowed: a realm is one.
     *
     * @throws InvalidInputException with a reason for the admin
     */
    public void validateIssuer(String issuer) {
        URI uri = requireEndpoint(issuer, "issuer");
        if (uri.getRawQuery() != null) {
            throw new InvalidInputException("Remove the query from the issuer.");
        }
    }

    private Result fetch(String issuer) {
        try {
            validateIssuer(issuer);
            String origin = ScmOrigin.of(issuer).orElseThrow();
            String body = egress.clientFor(origin)
                    .get()
                    // Discovery § 4: the path is appended to the issuer without its trailing slash.
                    .uri(URI.create(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration"))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(DEADLINE)
                    .block();
            JsonNode document = objectMapper.readTree(body == null ? "" : body);
            String named = text(document, "issuer");
            // Discovery § 4.3: the document names exactly the issuer that was used to read it.
            if (named == null || !named.equals(issuer)) {
                return unavailable(issuer, "The discovery document names another issuer.");
            }
            return new Result(
                    new Endpoints(
                            named,
                            requireEndpoint(text(document, "authorization_endpoint"), "authorization_endpoint")
                                    .toString(),
                            requireEndpoint(text(document, "token_endpoint"), "token_endpoint")
                                    .toString(),
                            requireEndpoint(text(document, "jwks_uri"), "jwks_uri")
                                    .toString()),
                    "");
        } catch (InvalidInputException e) {
            return unavailable(issuer, e.getMessage());
        } catch (RuntimeException e) {
            // A refused address, a timeout, a redirect, an error status or a body that is not JSON.
            log.atWarn()
                    .addKeyValue("auth.oidc.issuer", issuer)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .log("auth.oidc: the issuer's discovery document could not be read");
            return new Result(null, "Hephaestus could not read the issuer's discovery document. Check the issuer.");
        }
    }

    private static Result unavailable(String issuer, String reason) {
        log.atWarn().addKeyValue("auth.oidc.issuer", issuer).log("auth.oidc: {}", reason);
        return new Result(null, reason);
    }

    private URI requireEndpoint(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException("The " + name + " is missing.");
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new InvalidInputException("The " + name + " is not a valid URL.", e);
        }
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
        boolean simulator = egress.isSimulationOrigin(scheme, uri.getHost(), uri.getPort());
        if (!"https".equals(scheme) && !simulator) {
            throw new InvalidInputException("The " + name + " must use HTTPS.");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new InvalidInputException("Remove the credentials or the fragment from the " + name + ".");
        }
        String origin = ScmOrigin.of(value)
                .orElseThrow(() -> new InvalidInputException("The " + name + " needs a valid host name."));
        egress.validate(origin);
        return uri;
    }

    private static @Nullable String text(JsonNode document, String field) {
        JsonNode value = document.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
