package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import jakarta.servlet.Filter;

/**
 * The sandbox gateway's credential filter, for tests outside this package. The worker role is off in the test
 * context, so {@link LlmProxySecurityConfig} builds no gateway chain there.
 */
public final class SandboxGatewayFixtures {

    private SandboxGatewayFixtures() {}

    /** Authenticates a job or turn credential as the gateway does: by the attempt's live lease. */
    public static Filter credentialFilter(
            ReviewJobRepository jobs, MentorTurnRepository turns, AttemptTokenVerifier verifier) {
        return new JobTokenAuthenticationFilter(jobs, turns, new SandboxGatewaySessions(), verifier);
    }
}
