package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.proxy.JobTokenAuthenticationFilter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A review sandbox's workspace and result, on the routes that the image's review programs call: the routes of a review
 * Job's gateway session, here for the run that the sandbox holds. Each answers only while the claim holds the run.
 */
@RestController
@RequestMapping("/internal/agent/runs/{runId}/runtime")
@PreAuthorize("isAuthenticated()")
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@Profile("!specs & !cds-training")
@WorkspaceAgnostic("The sandbox token and the run's own row name the workspace, and the resolver checks both")
public class ReviewSandboxController {

    /** How the runner ended, as the run program reports it with the result (gateway-run.ts). */
    static final String EXIT_CODE_HEADER = "x-sandbox-exit-code";

    static final String TIMED_OUT_HEADER = "x-sandbox-timed-out";

    private final ReviewSandboxRuns reviews;
    private final AgentRuns runs;

    public ReviewSandboxController(ReviewSandboxRuns reviews, AgentRuns runs) {
        this.reviews = reviews;
        this.runs = runs;
    }

    /** @param resultByteBudget the most that the result may hold, as {@link SandboxOutputArchive} reads it */
    public record Capabilities(int protocolVersion, long workspaceByteBudget, long resultByteBudget) {}

    @GetMapping
    public Capabilities capabilities(SandboxRun run) {
        current(run);
        return new Capabilities(3, reviews.session(run).inputBytes(), SandboxOutputArchive.MAX_OUTPUT_BYTES);
    }

    @GetMapping("/workspace")
    public void workspace(SandboxRun run, HttpServletResponse response) throws IOException {
        current(run);
        var session = reviews.session(run);
        try (var input = session.download()) {
            response.setContentType("application/x-tar");
            response.setContentLengthLong(session.inputBytes());
            input.transferTo(response.getOutputStream());
        }
    }

    /** Its declared length is bounded on the gateway chain before this is reached. */
    @PostMapping(value = "/result", consumes = "application/x-tar")
    public void result(
            SandboxRun run,
            @RequestHeader("Content-Digest") String contentDigest,
            @RequestHeader(EXIT_CODE_HEADER) int exitCode,
            @RequestHeader(TIMED_OUT_HEADER) boolean timedOut,
            HttpServletRequest request,
            HttpServletResponse response) {
        current(run);
        var session = reviews.preparedSession(run);
        try {
            var upload = session.upload(request.getInputStream(), contentDigest);
            if (!upload.admitted()) {
                response.setHeader("ETag", upload.etag());
                response.setStatus(HttpStatus.CONFLICT.value());
                return;
            }
            reviews.measured(run, new SandboxResult(exitCode, session.result(), "", timedOut, Duration.ZERO));
            // Only now: the runner reads a 409 with this ETag as confirmed, and an error answer keeps the headers.
            response.setHeader("ETag", upload.etag());
            response.setStatus(HttpStatus.NO_CONTENT.value());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid result archive", exception);
        }
    }

    private void current(SandboxRun run) {
        if (run.kind() != AgentRunKind.REVIEW) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // The gateway's answer to a claim that no longer holds its run, as its other routes give it.
        if (!runs.isCurrent(run.fence().run(), run.fence().epoch()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED);
    }
}
