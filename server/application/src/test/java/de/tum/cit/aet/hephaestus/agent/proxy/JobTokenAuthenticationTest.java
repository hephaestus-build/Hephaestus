package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobTokenAuthenticationTest extends BaseUnitTest {

    private static final ProxyRouting ROUTING = new ProxyRouting(
            "job:test",
            "anthropic-messages",
            "https://api.anthropic.com",
            null,
            null,
            null,
            null,
            new ProxyRouting.BilledAttempt(
                    LlmUsageSourceType.AGENT_JOB, UUID.randomUUID(), 0, BigDecimal.ZERO, "worker-1"));

    @Test
    void shouldRedactCredentials() {
        var auth = new JobTokenAuthentication(ROUTING, null);

        // The default AbstractAuthenticationToken credential is whatever was passed in; this override
        // is what keeps a proxy bearer token out of every log line that prints the Authentication.
        assertThat(auth.getCredentials()).isEqualTo("[REDACTED]");
    }
}
