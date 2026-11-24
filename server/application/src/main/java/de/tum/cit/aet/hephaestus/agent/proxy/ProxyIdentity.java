package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import org.jspecify.annotations.Nullable;

/** A verified model-gateway caller. Its token or sandbox run names its attempt. */
record ProxyIdentity(ProxyAttempt attempt) {
    @Nullable
    ProxyAttempt job() {
        return attempt.caller() == ProxyAttempt.Caller.REVIEW ? attempt : null;
    }

    @Nullable
    ProxyAttempt turn() {
        return attempt.caller() == ProxyAttempt.Caller.TURN ? attempt : null;
    }

    @Nullable
    ProxyAttempt draft() {
        return attempt.caller() == ProxyAttempt.Caller.DRAFT_REPORT ? attempt : null;
    }

    static ProxyIdentity of(AttemptJwt token) {
        return of(ProxyAttempt.of(token));
    }

    static ProxyIdentity of(ProxyAttempt attempt) {
        return new ProxyIdentity(attempt);
    }

    /** Names the caller for logs and rate limits, never with its credential. */
    String description() {
        return switch (attempt.caller()) {
            case REVIEW -> "job:" + attempt.id();
            case TURN -> "conversation-turn:" + attempt.id();
            case DRAFT_REPORT -> "report-draft:" + attempt.runId();
        };
    }
}
