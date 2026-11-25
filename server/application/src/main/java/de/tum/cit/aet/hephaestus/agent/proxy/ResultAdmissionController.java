package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.context.AttemptFolderBusyException;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailures;
import de.tum.cit.aet.hephaestus.agent.job.AttemptServerErrors;
import de.tum.cit.aet.hephaestus.agent.job.AttemptServerErrors.Request;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticeStartRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticeStartResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.StageResponseDTO;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.ReadsContext;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import jakarta.validation.Valid;
import java.io.UncheckedIOException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnAgentRuntime
@RequestMapping("/internal/llm")
public class ResultAdmissionController {
    private final ResultAdmissionService admission;
    private final AttemptCaptureFailures captureFailures;
    private final AttemptServerErrors serverErrors;
    private final AgentRunAuthority authority;

    public ResultAdmissionController(
            ResultAdmissionService admission,
            AttemptCaptureFailures captureFailures,
            AttemptServerErrors serverErrors,
            AgentRunAuthority authority) {
        this.admission = admission;
        this.captureFailures = captureFailures;
        this.serverErrors = serverErrors;
        this.authority = authority;
    }

    @PostMapping("/start-practice")
    @ReadsContext
    public PracticeStartResponseDTO startPractice(
            @Valid @RequestBody PracticeStartRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(identity, Request.START, () -> admission.start(identity, request));
    }

    @PostMapping("/admit-results")
    @ReadsContext
    public AdmissionResponseDTO admitResults(
            @Valid @RequestBody SealResultsRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        // The answer reads the developer's earlier results and feedback: only while the run's authority still holds,
        // and it reaches the sandbox only if it still held once they were read.
        AdmissionResponseDTO admitted;
        try {
            admitted = checked(
                    identity,
                    Request.ADMIT,
                    () -> admission.admit(
                            identity, request, () -> authority.check(fence(identity), AuthorityBoundary.CONTEXT_READ)));
        } catch (AuthorityRefusedException refused) {
            // The read-only recheck refused under the admission's locks: the run ends now that they unwound.
            authority.endRefused(fence(identity), refused);
            throw refused;
        }
        requireAuthority(identity);
        return admitted;
    }

    @PostMapping("/stage-result")
    @ReadsContext
    public StageResponseDTO stageResult(
            @Valid @RequestBody AdmissionRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(identity, Request.STAGE, () -> admission.stage(identity, request));
    }

    private <T> T checked(ResultAdmissionService.AdmissionIdentity identity, Request kind, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AuthorityRefusedException | StaleRunException refused) {
            // A recheck of the run's authority or claim during the request: the run ended or moved on, and it is no
            // server error.
            throw refused;
        } catch (ResultAdmissionService.StaleAttemptException | AdmissionConflictException conflict) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The review attempt or admitted payload changed", conflict);
        } catch (JobDeliveryException refused) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage(), refused);
        } catch (AttemptFolderBusyException busy) {
            // Infrastructure, never a judgement of the results: the sandbox calls again.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The evidence folder is not ready on this server", busy);
        } catch (UncheckedIOException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The evidence folder is unavailable", unavailable);
        } catch (CheckoutUnverifiedException unverified) {
            // A capture failure, never a judgement of the results: the attempt ends with it as its recorded reason,
            // and the sandbox stops as it does for any attempt that no longer holds its job.
            captureFailures.fail(identity, unverified);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The review attempt ended: its captured checkout cannot be built", unverified);
        } catch (RuntimeException unexpected) {
            // The sandbox ends the attempt on the server's error and its log is not collected: the attempt keeps
            // which request failed and why, by type. A later error of the attempt replaces it.
            serverErrors.record(identity, kind, unexpected);
            throw unexpected;
        }
    }

    private void requireAuthority(ResultAdmissionService.AdmissionIdentity identity) {
        authority.require(fence(identity), AuthorityBoundary.CONTEXT_READ);
    }

    private static AgentRunFence fence(ResultAdmissionService.AdmissionIdentity identity) {
        return new AgentRunFence(identity.runId(), identity.workspaceId(), ReviewRunKind.epoch(identity.attempt()));
    }

    /**
     * The attempt that the credential names, once its run's authority holds: each of these routes reads the review's
     * sources, and an attempt token reaches no {@code SandboxRun} resolver, so the route rechecks it here.
     */
    private ResultAdmissionService.AdmissionIdentity attemptOf(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ProxyIdentity identity) || identity.job() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Review job credential required");
        var job = identity.job();
        var attempt = new ResultAdmissionService.AdmissionIdentity(
                job.id(), job.workspaceId(), ReviewRunKind.attempt(job.epoch()), job.runId());
        requireAuthority(attempt);
        return attempt;
    }
}
