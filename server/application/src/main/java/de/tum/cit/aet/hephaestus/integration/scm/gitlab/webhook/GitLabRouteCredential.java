package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.ParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The bearer token a connection-scoped GitLab group hook sends as {@code X-Gitlab-Token}: an HS256 JWS signed with
 * {@code hephaestus.webhook.routing.secret} that binds one connection, its workspace, the configured GitLab origin and
 * the connected group. The receiver verifies it without a database, so a token can only ever route to the connection
 * it names; the server re-checks every bound claim against the active connection after the delivery is durable.
 *
 * <p>The hook URL carries the signing key's fingerprint and a route id, a MAC over exactly the claims the token binds.
 * A token is deterministic for its route and key, so a hook's URL names its credential: when the key or any bound claim
 * changes the URL changes too, a new hook is registered, and no registration ever rewrites the token of an existing
 * one.
 */
@Component
public class GitLabRouteCredential {

    static final String ISSUER = "hephaestus";
    static final String AUDIENCE = "hephaestus:gitlab-webhook";
    static final JOSEObjectType TYPE = new JOSEObjectType("hephaestus-gitlab-route+jwt");

    /** Far below GitLab's 8192-character token limit, so a registered token is never truncated or rejected. */
    static final int MAX_TOKEN_LENGTH = 2048;

    static final int MAX_GROUP_PATH_LENGTH = 512;

    private static final String CLAIM_WORKSPACE = "wid";
    private static final String CLAIM_ORIGIN = "origin";
    private static final String CLAIM_GROUP_ID = "gid";
    private static final String CLAIM_GROUP_PATH = "gpath";
    private static final Set<String> CLAIMS =
            Set.of("iss", "aud", "sub", CLAIM_WORKSPACE, CLAIM_ORIGIN, CLAIM_GROUP_ID, CLAIM_GROUP_PATH);
    private static final Set<String> HEADER_PARAMETERS = Set.of("alg", "typ", "kid");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final int ROUTE_ID_LENGTH = 22;

    /**
     * NATS headers the receiver writes from a verified route. The server reads them only on connection-scoped
     * subjects, which only the receiver's connection endpoint publishes.
     */
    public static final String HEADER_WORKSPACE = "Hephaestus-Route-Workspace";

    public static final String HEADER_ORIGIN = "Hephaestus-Route-Origin";
    public static final String HEADER_GROUP_ID = "Hephaestus-Route-Group-Id";
    public static final String HEADER_GROUP_PATH = "Hephaestus-Route-Group-Path";

    /** What a verified token proves about the hook that sent it. */
    public record Route(long connectionId, long workspaceId, String providerOrigin, long groupId, String groupPath) {

        /** The headers a delivery verified for this route is published with. */
        public Map<String, String> headers() {
            return Map.of(
                    HEADER_WORKSPACE,
                    Long.toString(workspaceId),
                    HEADER_ORIGIN,
                    providerOrigin,
                    HEADER_GROUP_ID,
                    Long.toString(groupId),
                    HEADER_GROUP_PATH,
                    groupPath);
        }

        private String canonical() {
            return String.join(
                    "\n",
                    Long.toString(connectionId),
                    Long.toString(workspaceId),
                    providerOrigin,
                    Long.toString(groupId),
                    groupPath);
        }
    }

    /** A token for one route and the URL path segments that name it: {@code {keyId}/{routeId}}. */
    public record Issued(String token, String keyId, String routeId) {

        @Override
        public String toString() {
            return "Issued[token=<redacted>, keyId=" + keyId + ", routeId=" + routeId + "]";
        }
    }

    private record SigningKey(String keyId, byte[] secret) {}

    private final @Nullable SigningKey current;
    private final @Nullable SigningKey previous;

    public GitLabRouteCredential(WebhookProperties properties) {
        WebhookProperties.Routing routing = properties.routing();
        this.current = signingKey(routing.secret());
        this.previous = signingKey(routing.previousSecret());
    }

    /** Whether new connection-scoped hooks can be registered. */
    public boolean isConfigured() {
        return current != null;
    }

    /** Whether {@code keyId} names a key the receiver still accepts. */
    public boolean isAccepted(String keyId) {
        return keyFor(keyId).isPresent();
    }

    /** The token for {@code route} under the current key. */
    public Issued issue(Route route) {
        SigningKey key = Objects.requireNonNull(current, "hephaestus.webhook.routing.secret is not configured");
        if (!isWellFormed(route)) {
            throw new IllegalArgumentException("GitLab route cannot be signed: connectionId=" + route.connectionId());
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(TYPE)
                .keyID(key.keyId())
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(Long.toString(route.connectionId()))
                .claim(CLAIM_WORKSPACE, route.workspaceId())
                .claim(CLAIM_ORIGIN, route.providerOrigin())
                .claim(CLAIM_GROUP_ID, route.groupId())
                .claim(CLAIM_GROUP_PATH, route.groupPath())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new MACSigner(key.secret()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign GitLab route credential", e);
        }
        String token = jwt.serialize();
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "GitLab route credential too long: connectionId=" + route.connectionId());
        }
        return new Issued(token, key.keyId(), routeId(key, route));
    }

    /**
     * The route {@code token} proves, when it is a credential for exactly {@code connectionId}, signed by the accepted
     * key {@code keyId}, whose claims are the ones {@code routeId} names. Anything else — another connection's token, a
     * retired key, a different algorithm, type, issuer or audience, an extra header, a missing, extra or mistyped claim
     * — is empty.
     */
    public Optional<Route> verify(@Nullable String token, long connectionId, String keyId, String routeId) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        Optional<SigningKey> key = keyFor(keyId);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            if (!HEADER_PARAMETERS.equals(header.getIncludedParams())
                    || !JWSAlgorithm.HS256.equals(header.getAlgorithm())
                    || !TYPE.equals(header.getType())
                    || !keyId.equals(header.getKeyID())
                    || !jwt.verify(new MACVerifier(key.get().secret()))) {
                return Optional.empty();
            }
            return route(jwt.getJWTClaimsSet(), connectionId)
                    .filter(route -> MessageDigest.isEqual(
                            routeId(key.get(), route).getBytes(StandardCharsets.US_ASCII),
                            routeId.getBytes(StandardCharsets.UTF_8)));
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Route> route(JWTClaimsSet claims, long connectionId) {
        Map<String, Object> values = claims.getClaims();
        if (!values.keySet().equals(CLAIMS)
                || !ISSUER.equals(values.get("iss"))
                || !List.of(AUDIENCE).equals(claims.getAudience())
                || !(values.get("sub") instanceof String subject)
                || !POSITIVE_ID.matcher(subject).matches()
                || Long.parseLong(subject) != connectionId
                || !(values.get(CLAIM_WORKSPACE) instanceof Long workspaceId)
                || !(values.get(CLAIM_GROUP_ID) instanceof Long groupId)
                || !(values.get(CLAIM_ORIGIN) instanceof String origin)
                || !(values.get(CLAIM_GROUP_PATH) instanceof String groupPath)) {
            return Optional.empty();
        }
        Route route = new Route(connectionId, workspaceId, origin, groupId, groupPath);
        return isWellFormed(route) ? Optional.of(route) : Optional.empty();
    }

    private static boolean isWellFormed(Route route) {
        return route.connectionId() > 0
                && route.workspaceId() > 0
                && route.groupId() > 0
                && ScmOrigin.of(route.providerOrigin()).equals(Optional.of(route.providerOrigin()))
                && !route.groupPath().isBlank()
                && route.groupPath().length() <= MAX_GROUP_PATH_LENGTH
                && route.groupPath().chars().noneMatch(Character::isISOControl);
    }

    private Optional<SigningKey> keyFor(String keyId) {
        if (current != null && current.keyId().equals(keyId)) {
            return Optional.of(current);
        }
        if (previous != null && previous.keyId().equals(keyId)) {
            return Optional.of(previous);
        }
        return Optional.empty();
    }

    private static @Nullable SigningKey signingKey(@Nullable String secret) {
        if (secret == null) {
            return null;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        return new SigningKey(mac(bytes, "hephaestus-gitlab-route-key").substring(0, 16), bytes);
    }

    /** Names {@code route} under {@code key} without revealing the key: a MAC over exactly the claims a token binds. */
    private static String routeId(SigningKey key, Route route) {
        return mac(key.secret(), "hephaestus-gitlab-route-id\n" + route.canonical())
                .substring(0, ROUTE_ID_LENGTH);
    }

    private static String mac(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] tag = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
