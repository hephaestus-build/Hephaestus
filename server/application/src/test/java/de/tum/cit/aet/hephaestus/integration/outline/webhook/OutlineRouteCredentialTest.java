package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookPropertiesFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The receiver derives a route's signing secret from the routing key alone: what the server issued verifies, a route
 * of a retired key or a malformed route id does not, and a rotated key keeps the previous key's routes verifiable.
 */
class OutlineRouteCredentialTest extends BaseUnitTest {

    private static final String KEY = "outline-route-test-current-routing-secret-01";
    private static final String OTHER_KEY = "outline-route-test-other-routing-secret-0123";

    private static OutlineRouteCredential credential(String secret, @Nullable String previous) {
        return new OutlineRouteCredential(
                WebhookPropertiesFixture.withRouting(new WebhookProperties.Routing(secret, previous)));
    }

    @Test
    void shouldDeriveTheIssuedSecretFromTheRouteWhenTheReceiverHasTheSameKey() {
        OutlineRouteCredential.Issued issued = credential(KEY, null).issue();

        assertThat(credential(KEY, null).signingSecret(issued.keyId(), issued.routeId()))
                .hasValueSatisfying(secret ->
                        assertThat(new String(secret, StandardCharsets.UTF_8)).isEqualTo(issued.signingSecret()));
    }

    @Test
    void shouldIssueANewSecretForEveryRouteWhenRegisteringTwice() {
        OutlineRouteCredential credential = credential(KEY, null);

        OutlineRouteCredential.Issued first = credential.issue();
        OutlineRouteCredential.Issued second = credential.issue();

        assertThat(second.routeId()).isNotEqualTo(first.routeId());
        assertThat(second.signingSecret()).isNotEqualTo(first.signingSecret());
    }

    @Test
    void shouldDeriveTheSecretFromTheRoutingSecretWhenTheUrlIsKnown() {
        // Everything the URL carries, under another routing secret, yields another secret: the URL alone forges
        // nothing.
        OutlineRouteCredential.Issued issued = credential(KEY, null).issue();
        OutlineRouteCredential other = credential(OTHER_KEY, null);
        String otherKeyId = other.issue().keyId();

        assertThat(other.signingSecret(otherKeyId, issued.routeId()))
                .hasValueSatisfying(secret ->
                        assertThat(new String(secret, StandardCharsets.UTF_8)).isNotEqualTo(issued.signingSecret()));
    }

    @Test
    void shouldRejectARouteWhenItsKeyIsNotAccepted() {
        OutlineRouteCredential.Issued issued = credential(OTHER_KEY, null).issue();

        assertThat(credential(KEY, null).signingSecret(issued.keyId(), issued.routeId()))
                .isEmpty();
    }

    @Test
    void shouldKeepThePreviousKeysRoutesVerifiableWhenTheKeyRotates() {
        OutlineRouteCredential.Issued old = credential(OTHER_KEY, null).issue();
        OutlineRouteCredential rotated = credential(KEY, OTHER_KEY);

        assertThat(rotated.signingSecret(old.keyId(), old.routeId())).isPresent();
        assertThat(rotated.isCurrent(old.route())).isFalse();
        assertThat(rotated.isCurrent(rotated.issue().route())).isTrue();
    }

    @Test
    void shouldRejectARouteIdWhenItIsMalformed() {
        OutlineRouteCredential credential = credential(KEY, null);
        String keyId = credential.issue().keyId();

        assertThat(credential.signingSecret(keyId, "short")).isEmpty();
        assertThat(credential.signingSecret(keyId, "a".repeat(21) + "/")).isEmpty();
    }
}
