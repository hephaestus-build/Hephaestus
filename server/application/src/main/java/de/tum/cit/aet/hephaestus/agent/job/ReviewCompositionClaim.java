package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** The decision made when a composition run is claimed, shared by every gateway replica. */
@Component
@ConditionalOnAgentRuntime
public class ReviewCompositionClaim {
    private static final String FIELD = "compositionClaim";
    private final ReviewJobRepository jobs;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ClusterLock locks;

    public ReviewCompositionClaim(
            ReviewJobRepository jobs, TransactionTemplate transactions, ObjectMapper mapper, ClusterLock locks) {
        this.jobs = jobs;
        this.transactions = transactions;
        this.mapper = mapper;
        this.locks = locks;
    }

    /** The claim decision has no credential or captured input. */
    public record Decision(Instant started, @Nullable CompositionStop withholdOnly, boolean withheld) {
        public Decision {
            Objects.requireNonNull(started);
        }
    }

    /** Serializes withholding across replicas; a completed close is not repeated by later requests. */
    public void withholdOnce(SandboxRun run, ReviewJob job, Runnable withhold) {
        exclusive(run, () -> {
            var current = jobs.findByRunIdAndWorkspaceId(
                            run.fence().runId(), run.fence().workspaceId())
                    .filter(found -> found.getId().equals(job.getId()))
                    .orElseThrow(() -> new StaleRunException(run.fence()));
            var snapshot = require(run, current);
            if (!snapshot.withheld()) {
                withhold.run();
                save(run, current, new Decision(snapshot.started(), snapshot.withholdOnly(), true));
            }
            return true;
        });
    }

    /** Serializes withholding for this composition lease without holding a database transaction over file reads. */
    private <T> T exclusive(SandboxRun run, Supplier<T> work) {
        try {
            return locks.call(
                            "review-composition/" + run.fence().runId() + "/"
                                    + run.fence().epoch(),
                            Duration.ZERO,
                            work)
                    .orElseThrow(() -> unavailable(null));
        } catch (ClusterLock.UnavailableException unavailable) {
            throw unavailable(unavailable);
        }
    }

    private static ErrorResponseException unavailable(@Nullable Throwable failure) {
        var response = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, failure);
        response.setDetail("Review composition is temporarily unavailable");
        response.getHeaders().set("Retry-After", "1");
        return response;
    }

    public Optional<Decision> find(SandboxRun run, ReviewJob job) {
        if (job.getWorkspaceId() != run.fence().workspaceId()
                || !run.fence().runId().equals(job.getRunId())) return Optional.empty();
        var capture = job.getCapture();
        if (capture == null) return Optional.empty();
        var stored = capture.path(FIELD);
        if (!stored.path("runId").asString("").equals(run.fence().runId().toString())
                || stored.path("epoch").asInt(-1) != run.fence().epoch()) return Optional.empty();
        return Optional.of(mapper.treeToValue(stored.required("snapshot"), Decision.class));
    }

    public Decision require(SandboxRun run, ReviewJob job) {
        return find(run, job)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "The composition has no recorded claim decision"));
    }

    /** Joins the claim/start transaction when one exists; the run fence decides whether the descriptor is recorded. */
    public void save(SandboxRun run, ReviewJob job, Decision snapshot) {
        var stored = mapper.createObjectNode();
        stored.put("runId", run.fence().runId().toString());
        stored.put("epoch", run.fence().epoch());
        stored.set("snapshot", mapper.valueToTree(snapshot));
        Integer changed = transactions.execute(status -> jobs.saveCompositionClaim(
                job.getId(),
                run.fence().workspaceId(),
                run.fence().runId(),
                job.getAttempt(),
                run.fence().epoch(),
                mapper.writeValueAsString(stored)));
        if (changed == null || changed != 1) throw new StaleRunException(run.fence());
    }
}
