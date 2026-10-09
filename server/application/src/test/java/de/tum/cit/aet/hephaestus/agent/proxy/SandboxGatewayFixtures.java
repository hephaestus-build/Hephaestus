package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import jakarta.servlet.Filter;
import tools.jackson.databind.ObjectMapper;

/**
 * The sandbox gateway's credential filter, for tests outside this package. The worker role is off in the test
 * context, so {@link LlmProxySecurityConfig} builds no gateway chain there.
 */
public final class SandboxGatewayFixtures {

    private SandboxGatewayFixtures() {}

    /** Authenticates a job or turn credential as the gateway does on a worker named {@code workerId}. */
    public static Filter credentialFilter(
            AgentJobRepository jobs,
            MentorTurnRepository turns,
            WorkerJwtVerifier verifier,
            ObjectMapper mapper,
            String workerId) {
        return new JobTokenAuthenticationFilter(jobs, turns, new SandboxGatewaySessions(), verifier, mapper, workerId);
    }
}
