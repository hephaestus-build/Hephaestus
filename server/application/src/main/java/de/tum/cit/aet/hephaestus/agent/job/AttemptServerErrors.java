package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps on a running attempt the error this server answered its results with: which request failed and the cause
 * classified by type, never the exception's message. The attempt's terminal error names it, as the sandbox's log is not
 * collected. The write is fenced on the attempt's run, so a stale attempt writes nothing.
 */
@Component
@ConditionalOnAgentRuntime
public class AttemptServerErrors {
    private static final Logger log = LoggerFactory.getLogger(AttemptServerErrors.class);

    /** A request of the review sandbox that this server answers. */
    public enum Request {
        START("started a practice"),
        STAGE("staged a result"),
        ADMIT("admitted the results");

        private final String phrase;

        Request(String phrase) {
            this.phrase = phrase;
        }
    }

    private final ReviewJobRepository jobs;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    AttemptServerErrors(ReviewJobRepository jobs, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jobs = jobs;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactions);
    }

    /** Best effort: the answer to the sandbox stays the server's error whether or not this records it. */
    @WorkspaceAgnostic("The attempt credential supplies the workspace; every write is fenced on it")
    public void record(AdmissionIdentity identity, Request request, RuntimeException failure) {
        var kind =
                PreparationFailure.of(PreparationFailure.Stage.INPUTS, failure).kind();
        String diagnosis = mapper.createObjectNode()
                .put("attempt", identity.attempt())
                .put("request", request.name())
                .put("kind", kind.name())
                .toString();
        try {
            transactions.executeWithoutResult(status -> jobs.recordServerError(
                    identity.jobId(), identity.workspaceId(), identity.attempt(), identity.runId(), diagnosis));
        } catch (RuntimeException unrecorded) {
            log.warn(
                    "Could not keep the server's error on its attempt: jobId={}, error={}",
                    identity.jobId(),
                    unrecorded.getClass().getSimpleName());
        }
    }

    /** How this server failed the attempt's results, for its terminal error; null when it kept no such error. */
    static @Nullable String describe(@Nullable JsonNode capture, int attempt) {
        JsonNode error = capture == null ? null : capture.get("serverError");
        if (error == null || error.path("attempt").asInt(-1) != attempt) return null;
        try {
            var request = Request.valueOf(error.path("request").asString());
            var kind = PreparationFailure.Kind.valueOf(error.path("kind").asString());
            return "The server answered the review's results with its own error when it " + request.phrase + ": "
                    + kind.phrase() + ".";
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
