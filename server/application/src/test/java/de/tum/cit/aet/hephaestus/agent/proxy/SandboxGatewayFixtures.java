package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import jakarta.servlet.Filter;
import org.mockito.Mockito;

/** The real gateway credential filter for isolated turn controllers. */
public final class SandboxGatewayFixtures {
    private SandboxGatewayFixtures() {}

    public static Filter sandboxCredentialFilter(AttemptTokenVerifier verifier, AgentSandboxes sandboxes) {
        return new SandboxTokenAuthenticationFilter(verifier, sandboxes, Mockito.mock(ProxyAccounting.class));
    }
}
