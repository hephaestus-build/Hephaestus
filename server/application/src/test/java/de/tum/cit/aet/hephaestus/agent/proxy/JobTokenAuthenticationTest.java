package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobTokenAuthenticationTest extends BaseUnitTest {

    @Test
    void shouldRedactCredentials() {
        var auth = new JobTokenAuthentication(
                ProxyIdentity.of(
                        new ProxyAttempt(ProxyAttempt.Caller.TURN, UUID.randomUUID(), 42L, 1, UUID.randomUUID(), null)),
                Set.of("llm_proxy"));

        // The default AbstractAuthenticationToken credential is whatever was passed in; this override
        // is what keeps a proxy bearer token out of every log line that prints the Authentication.
        assertThat(auth.getCredentials()).isEqualTo("[REDACTED]");
    }
}
