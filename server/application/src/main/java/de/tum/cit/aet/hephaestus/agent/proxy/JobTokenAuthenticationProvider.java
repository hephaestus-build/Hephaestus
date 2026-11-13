package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunArgumentResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import java.io.Serial;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/** Authenticates the model gateway's attempt and sandbox credentials. */
// Credential checks must not initialize model configuration.
final class JobTokenAuthenticationProvider implements AuthenticationProvider {
    private final ReviewJobRepository jobs;
    private final AgentRuns runs;
    private final AttemptTokenVerifier verifier;
    private final @Nullable AgentSandboxes sandboxes;
    private final Map<AgentRunKind, SandboxRunKind> kinds;

    /** @param sandboxes null where this process owns no sandboxes, which refuses every sandbox token */
    JobTokenAuthenticationProvider(
            ReviewJobRepository jobs,
            AgentRuns runs,
            AttemptTokenVerifier verifier,
            @Nullable AgentSandboxes sandboxes,
            Map<AgentRunKind, SandboxRunKind> kinds) {
        this.jobs = jobs;
        this.runs = runs;
        this.verifier = verifier;
        this.sandboxes = sandboxes;
        this.kinds = kinds;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var token = (BearerTokenAuthenticationToken) authentication;
        var request = (JobTokenAuthenticationFilter.RequestIdentity) token.getDetails();
        if (request == null) throw invalid();
        AttemptJwt attempt;
        try {
            attempt = verifier.verify(token.getToken());
        } catch (AttemptTokenInvalidException notAnAttempt) {
            return sandbox(token.getToken(), request, notAnAttempt);
        }
        if (attempt.kind() != AttemptJwt.Kind.REVIEW) throw invalid();
        return review(attempt, request);
    }

    private Authentication review(AttemptJwt jwt, JobTokenAuthenticationFilter.RequestIdentity request) {
        if (request.turnId() != null) throw invalid();
        var job = jobs.findById(jwt.id()).orElseThrow(JobTokenAuthenticationProvider::invalid);
        if (job.getWorkspaceId() != jwt.workspaceId()) throw invalid();
        if (request.runtimeJobId() != null
                && !request.runtimeJobId().equals(jwt.id().toString())) throw invalid();
        // A valid credential of an attempt that ended or was requeued, so that it lost its lease: its runner stops.
        if (!job.getRunId().equals(jwt.runId())
                || !runs.isCurrent(new AgentRunRef(jwt.runId(), jwt.workspaceId()), jwt.epoch()))
            throw new AttemptLeaseConflict("This review attempt no longer holds its lease");
        return new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes());
    }

    /**
     * A sandbox's token reaches no runtime and no turn endpoint. Its run's headers name a run of its own subject; the
     * run's kind names the attempt, while the claim holds the run. A claim that no longer holds it gets {@code 409},
     * so its loop stops; anything else is no credential.
     */
    private Authentication sandbox(
            String credential,
            JobTokenAuthenticationFilter.RequestIdentity request,
            AttemptTokenInvalidException notAnAttempt) {
        AgentSandboxes owner = sandboxes;
        if (owner == null) throw new BadCredentialsException("Invalid or expired credential", notAnAttempt);
        SandboxJwt token;
        try {
            token = verifier.verifySandbox(credential);
        } catch (AttemptTokenInvalidException invalid) {
            invalid.addSuppressed(notAnAttempt);
            throw new BadCredentialsException("Invalid or expired credential", invalid);
        }
        if (request.runtimeJobId() != null || request.turnId() != null) throw invalid();
        var live = owner.authenticate(token).orElseThrow(JobTokenAuthenticationProvider::invalid);
        var run = SandboxRunArgumentResolver.resolve(runs, live, request.run(), request.epoch())
                .orElseThrow(JobTokenAuthenticationProvider::invalid);
        var kind = kinds.get(run.kind());
        if (kind == null || kind.sandbox() != live.kind()) throw invalid();
        if (!runs.isCurrent(run.fence().run(), run.fence().epoch()))
            throw new AttemptLeaseConflict("This sandbox's claim no longer holds its run");
        var attempt = kind.attempt(run).orElseThrow(JobTokenAuthenticationProvider::invalid);
        var authentication =
                new JobTokenAuthentication(ProxyIdentity.of(attempt), Set.of(AttemptTokenIssuer.LLM_PROXY_SCOPE));
        authentication.setDetails(run);
        return authentication;
    }

    /**
     * Answered {@code 409} with {@link JobTokenAuthenticationFilter#ATTEMPT_ENDED}: the credential is valid, but the
     * attempt ended, or another server or a later attempt now owns the work.
     */
    static final class AttemptLeaseConflict extends BadCredentialsException {
        @Serial
        private static final long serialVersionUID = 1L;

        AttemptLeaseConflict(String message) {
            super(message);
        }
    }

    private static BadCredentialsException invalid() {
        return new BadCredentialsException("Invalid or expired credential");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
