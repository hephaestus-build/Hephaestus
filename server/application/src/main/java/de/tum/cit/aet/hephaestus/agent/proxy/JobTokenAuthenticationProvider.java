package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenInvalidException;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import java.io.Serial;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * Authenticates the sandbox credentials: an attempt's, of a review or of a Heph turn, and a Heph host's session token.
 * A review attempt's token reaches its own runtime and the model. A Heph turn attempt's token reaches the model while this worker holds the attempt's lease, and its own turn endpoints, which
 * check that the attempt runs here. A Heph host's gateway session token, which no signed claim backs, reaches only its
 * own runtime: the discovery and workspace its initializer downloads, and its command channel.
 */
// Credential checks must not initialize model configuration.
final class JobTokenAuthenticationProvider implements AuthenticationProvider {
    private final ReviewJobRepository jobs;
    private final MentorTurnRepository turns;
    private final SandboxGatewaySessions sessions;
    private final AttemptTokenVerifier verifier;

    JobTokenAuthenticationProvider(
            ReviewJobRepository jobs,
            MentorTurnRepository turns,
            SandboxGatewaySessions sessions,
            AttemptTokenVerifier verifier) {
        this.jobs = jobs;
        this.turns = turns;
        this.sessions = sessions;
        this.verifier = verifier;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var token = (BearerTokenAuthenticationToken) authentication;
        var request = (JobTokenAuthenticationFilter.RequestIdentity) token.getDetails();
        if (request == null) throw invalid();
        AttemptJwt attempt;
        try {
            attempt = verifier.verify(token.getToken());
        } catch (AttemptTokenInvalidException invalid) {
            // Only a session registered as a Heph host's takes an unsigned token. A rejected job token is never
            // read as one, even while its review session is open.
            var session = sessions.unsignedSessionFor(token.getToken());
            if (session.isPresent()) return unsignedSession(session.get(), request);
            throw new BadCredentialsException("Invalid or expired credential", invalid);
        }
        return switch (attempt.kind()) {
            case REVIEW -> review(attempt, request);
            case TURN -> turn(attempt, request);
        };
    }

    private Authentication review(AttemptJwt jwt, JobTokenAuthenticationFilter.RequestIdentity request) {
        if (request.turnId() != null || request.commands()) throw invalid();
        var job = jobs.findById(jwt.id()).orElseThrow(JobTokenAuthenticationProvider::invalid);
        if (job.getWorkspaceId() != jwt.workspaceId()) throw invalid();
        if (request.runtimeJobId() != null
                && !request.runtimeJobId().equals(jwt.id().toString())) throw invalid();
        // A valid credential of an attempt that ended or was requeued, so that it lost its lease: its runner stops.
        if (!jobs.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.attempt(), jwt.leaseId()))
            throw new AttemptLeaseConflict("This review attempt no longer holds its lease");
        return new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes(), null);
    }

    /**
     * A turn token never reaches a sandbox runtime, and on the turn endpoints only its own turn. A model call needs the
     * attempt to hold its live lease; an attempt that ended or moved on gets {@code 409}, so its host stops.
     */
    private Authentication turn(AttemptJwt jwt, JobTokenAuthenticationFilter.RequestIdentity request) {
        if (request.runtimeJobId() != null) throw invalid();
        if (request.turnId() != null) {
            if (!request.turnId().equals(jwt.id().toString())) throw invalid();
        } else {
            // An unknown turn, or one of another workspace, is no credential at all; only a known turn's ended attempt
            // gets 409.
            if (turns.findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId()).isEmpty()) throw invalid();
            if (!turns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.attempt(), jwt.leaseId()))
                throw new AttemptLeaseConflict("This turn attempt no longer holds its lease");
        }
        return new JobTokenAuthentication(ProxyIdentity.of(jwt), jwt.scopes(), jwt);
    }

    /**
     * A Heph host's session token, which no signed claim backs, reaches only that host's own runtime reads: the
     * discovery and workspace archive its initializer downloads before the host starts, and its command channel.
     */
    private static Authentication unsignedSession(
            SandboxGatewaySessions.UnsignedSession session, JobTokenAuthenticationFilter.RequestIdentity request) {
        boolean permitted = switch (session.kind()) {
            case HEPH_HOST -> request.commands() || request.workspaceRead();
            case REVIEW -> false;
        };
        if (!permitted || !session.id().toString().equals(request.runtimeJobId())) throw invalid();
        return new JobTokenAuthentication(ProxyIdentity.hostSession(session.id()), Set.of(), null);
    }

    /**
     * Answered {@code 409} with {@link JobTokenAuthenticationFilter#ATTEMPT_ENDED}: the credential is valid, but the
     * attempt ended, or another worker or a later attempt now owns the work.
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
