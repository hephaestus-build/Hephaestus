package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GitLabRouteCredentialTest {

    private static final String KEY = "routing-key-for-tests-0123456789abcdef-long-enough-for-hs512-signing";
    private static final String NEXT_KEY = "next-routing-key-for-tests-0123456789ab";
    private static final GitLabRouteCredential.Route ROUTE =
            new GitLabRouteCredential.Route(7L, 3L, "https://gitlab.lrz.de", 42L, "hephaestustest/introcourse");

    private static GitLabRouteCredential credential(@Nullable String secret, @Nullable String previous) {
        return new GitLabRouteCredential(
                WebhookPropertiesFixture.withRouting(new WebhookProperties.Routing(secret, previous)));
    }

    @Test
    void shouldVerifyTheRouteItIssuedWhenConnectionKeyAndRouteMatch() {
        GitLabRouteCredential credential = credential(KEY, null);

        GitLabRouteCredential.Issued issued = credential.issue(ROUTE);

        assertThat(credential.verify(issued.token(), 7L, issued.keyId(), issued.routeId()))
                .contains(ROUTE);
        assertThat(credential.issue(ROUTE)).isEqualTo(issued);
        assertThat(issued.token().length()).isLessThanOrEqualTo(GitLabRouteCredential.MAX_TOKEN_LENGTH);
        assertThat(issued.toString()).doesNotContain(issued.token());
    }

    @Test
    void shouldNameEveryChangedRouteClaimWithAnotherRouteIdUnderTheSameKey() {
        GitLabRouteCredential credential = credential(KEY, null);
        GitLabRouteCredential.Issued issued = credential.issue(ROUTE);

        GitLabRouteCredential.Issued renamed = credential.issue(new GitLabRouteCredential.Route(
                7L, 3L, "https://gitlab.lrz.de", 42L, "hephaestustest/introcourse-2026"));

        assertThat(renamed.keyId()).isEqualTo(issued.keyId());
        assertThat(renamed.routeId()).isNotEqualTo(issued.routeId());
        assertThat(credential.verify(issued.token(), 7L, issued.keyId(), renamed.routeId()))
                .isEmpty();
        assertThat(credential.verify(renamed.token(), 7L, renamed.keyId(), issued.routeId()))
                .isEmpty();
    }

    @Test
    void shouldRejectTokenOfAnotherConnectionWhenReplayedToItsEndpoint() {
        GitLabRouteCredential credential = credential(KEY, null);
        GitLabRouteCredential.Issued issued = credential.issue(ROUTE);

        assertThat(credential.verify(issued.token(), 8L, issued.keyId(), issued.routeId()))
                .isEmpty();
    }

    @Test
    void shouldAcceptPreviousKeyOnlyUnderItsOwnKeyId() {
        GitLabRouteCredential.Issued old = credential(KEY, null).issue(ROUTE);

        GitLabRouteCredential rotating = credential(NEXT_KEY, KEY);
        GitLabRouteCredential.Issued next = rotating.issue(ROUTE);
        assertThat(next.keyId()).isNotEqualTo(old.keyId());
        assertThat(rotating.verify(old.token(), 7L, old.keyId(), old.routeId())).contains(ROUTE);
        assertThat(rotating.verify(old.token(), 7L, next.keyId(), old.routeId()))
                .isEmpty();
        assertThat(rotating.verify(old.token(), 7L, old.keyId(), next.routeId()))
                .isEmpty();

        GitLabRouteCredential retired = credential(NEXT_KEY, null);
        assertThat(retired.isAccepted(old.keyId())).isFalse();
        assertThat(retired.verify(old.token(), 7L, old.keyId(), old.routeId())).isEmpty();
    }

    @Test
    void shouldRejectTokensThatAreNotExactlyTheSignedRoute() throws Exception {
        GitLabRouteCredential credential = credential(KEY, null);
        GitLabRouteCredential.Issued issued = credential.issue(ROUTE);
        String keyId = issued.keyId();
        String routeId = issued.routeId();

        assertThat(credential.verify(null, 7L, keyId, routeId)).isEmpty();
        assertThat(credential.verify("x".repeat(GitLabRouteCredential.MAX_TOKEN_LENGTH + 1), 7L, keyId, routeId))
                .isEmpty();
        assertThat(credential.verify(issued.token(), 7L, "unknown-key", routeId))
                .isEmpty();
        assertThat(credential.verify(credential(NEXT_KEY, null).issue(ROUTE).token(), 7L, keyId, routeId))
                .isEmpty();
        assertThat(credential.verify(new PlainJWT(claims(b -> {})).serialize(), 7L, keyId, routeId))
                .isEmpty();

        // The exact header: algorithm, type and key id, nothing else.
        assertRejected(credential, sign(JWSAlgorithm.HS512, keyId, h -> h, claims(b -> {})));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h.type(JOSEObjectType.JWT), claims(b -> {})));
        assertRejected(
                credential,
                sign(
                        JWSAlgorithm.HS256,
                        keyId,
                        h -> h.criticalParams(Set.of("x")).customParam("x", 1),
                        claims(b -> {})));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h.customParam("x", "y"), claims(b -> {})));

        // Issuer, audience and the exact claim set.
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.issuer("other"))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.audience("other"))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("admin", true))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("gid", null))));

        // Ids are positive integers, never strings, fractions, overflowing or signed values.
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("wid", "3"))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("wid", 3.5))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("gid", 1e30))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("gid", -42L))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.subject("07"))));
        assertRejected(credential, sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.subject("7.0"))));

        // The origin exactly as normalized.
        assertRejected(
                credential,
                sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> b.claim("origin", "HTTPS://GitLab.lrz.de/"))));

        // Correctly signed and shaped, the token still verifies.
        assertThat(credential.verify(sign(JWSAlgorithm.HS256, keyId, h -> h, claims(b -> {})), 7L, keyId, routeId))
                .contains(ROUTE);
    }

    @Test
    void shouldRefuseToIssueWhatItWouldNotVerify() {
        GitLabRouteCredential credential = credential(KEY, null);

        assertThatThrownBy(() -> credential.issue(
                        new GitLabRouteCredential.Route(7L, 3L, "https://gitlab.lrz.de", 42L, "g".repeat(513))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credential.issue(
                        new GitLabRouteCredential.Route(7L, 0L, "https://gitlab.lrz.de", 42L, "group")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldIssueNothingWithoutRoutingKey() {
        GitLabRouteCredential credential = credential(null, null);

        assertThat(credential.isConfigured()).isFalse();
        assertThatThrownBy(() -> credential.issue(ROUTE)).isInstanceOf(NullPointerException.class);
    }

    private void assertRejected(GitLabRouteCredential credential, String token) {
        GitLabRouteCredential.Issued issued = credential.issue(ROUTE);
        assertThat(credential.verify(token, 7L, issued.keyId(), issued.routeId()))
                .isEmpty();
    }

    private static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> change) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(GitLabRouteCredential.ISSUER)
                .audience(GitLabRouteCredential.AUDIENCE)
                .subject("7")
                .claim("wid", 3L)
                .claim("origin", "https://gitlab.lrz.de")
                .claim("gid", 42L)
                .claim("gpath", "hephaestustest/introcourse");
        change.accept(builder);
        return builder.build();
    }

    /** A token signed with the configured key, but with a header and claims the test shapes. */
    private static String sign(
            JWSAlgorithm algorithm, String keyId, UnaryOperator<JWSHeader.Builder> header, JWTClaimsSet claims)
            throws Exception {
        JWSHeader.Builder builder = new JWSHeader.Builder(algorithm)
                .type(GitLabRouteCredential.TYPE)
                .keyID(keyId);
        SignedJWT jwt = new SignedJWT(header.apply(builder).build(), claims);
        jwt.sign(new MACSigner(KEY.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
