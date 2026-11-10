package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.context.AttemptFolderBusyException;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AttemptCaptureFailures;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticeStartRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticeStartResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.StageResponseDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import jakarta.validation.Valid;
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

    public ResultAdmissionController(ResultAdmissionService admission, AttemptCaptureFailures captureFailures) {
        this.admission = admission;
        this.captureFailures = captureFailures;
    }

    @PostMapping("/start-practice")
    @WorkspaceAgnostic("The attempt credential supplies the workspace and exact job attempt")
    public PracticeStartResponseDTO startPractice(
            @Valid @RequestBody PracticeStartRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(identity, () -> admission.start(identity, request));
    }

    @PostMapping("/admit-results")
    @WorkspaceAgnostic("The attempt credential supplies the workspace and exact job attempt")
    public AdmissionResponseDTO admitResults(
            @Valid @RequestBody SealResultsRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(identity, () -> admission.admit(identity, request));
    }

    @PostMapping("/stage-result")
    @WorkspaceAgnostic("The attempt credential supplies the workspace and exact job attempt")
    public StageResponseDTO stageResult(
            @Valid @RequestBody AdmissionRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(identity, () -> admission.stage(identity, request));
    }

    private <T> T checked(ResultAdmissionService.AdmissionIdentity identity, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ResultAdmissionService.StaleAttemptException | AdmissionConflictException conflict) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The review attempt or admitted payload changed", conflict);
        } catch (JobDeliveryException refused) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage(), refused);
        } catch (AttemptFolderBusyException busy) {
            // Infrastructure, never a judgement of the results: the sandbox calls again.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The evidence folder is busy", busy);
        } catch (CheckoutUnverifiedException unverified) {
            // A capture failure, never a judgement of the results: the attempt ends with it as its recorded reason,
            // and the sandbox stops as it does for any attempt that no longer holds its job.
            captureFailures.fail(identity, unverified);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The review attempt ended: its captured checkout cannot be built", unverified);
        }
    }

    private static ResultAdmissionService.AdmissionIdentity attemptOf(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ProxyIdentity identity) || identity.job() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Review job credential required");
        var job = identity.job();
        return new ResultAdmissionService.AdmissionIdentity(
                job.id(), job.workspaceId(), ReviewRunKind.attempt(job.epoch()), job.runId());
    }
}
