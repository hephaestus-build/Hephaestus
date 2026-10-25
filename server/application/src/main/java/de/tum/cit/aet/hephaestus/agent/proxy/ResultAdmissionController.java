package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PublicFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@ConditionalOnWorkerRole
@RequestMapping("/internal/llm")
public class ResultAdmissionController {
    private final ResultAdmissionService admission;

    public ResultAdmissionController(ResultAdmissionService admission) {
        this.admission = admission;
    }

    @Operation(
            operationId = "admitResults",
            summary = "Admit results for the current review attempt",
            description = "Requires the private worker connector and a proxy-scoped worker credential bound to the"
                    + " workspace, job and attempt. Browser cookies and app-admin authority do not authorize this"
                    + " request.",
            security = @SecurityRequirement(name = "workerBearer"))
    @PostMapping("/admit-results")
    @WorkspaceAgnostic("The worker credential supplies the workspace and exact job attempt")
    public AdmissionResponseDTO admitResults(
            @Valid @RequestBody AdmissionRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(() -> admission.admit(identity, request));
    }

    @Operation(
            operationId = "prepareFeedback",
            summary = "Refused: a sandbox prepares no feedback",
            description = "The worker composes and prepares the feedback after the sandbox ends. A sandbox that asks is"
                    + " answered 410.",
            security = @SecurityRequirement(name = "workerBearer"))
    @PostMapping("/prepare-feedback")
    @WorkspaceAgnostic("The worker credential supplies the workspace and exact job attempt")
    public PrepareFeedbackResponseDTO prepareFeedback(
            @Valid @RequestBody PrepareFeedbackRequestDTO request, Authentication authentication) {
        attemptOf(authentication);
        throw composedOnTheWorker();
    }

    @Operation(
            operationId = "readPublicFeedbackHistory",
            summary = "Refused: a sandbox reads no feedback history",
            description = "The worker reads the history when it composes the feedback after the sandbox ends. A sandbox"
                    + " that asks is answered 410.",
            security = @SecurityRequirement(name = "workerBearer"))
    @PostMapping("/public-feedback-history")
    @WorkspaceAgnostic("The worker credential supplies the workspace and exact job attempt")
    public PublicFeedbackHistoryDTO readPublicFeedbackHistory(Authentication authentication) {
        attemptOf(authentication);
        throw composedOnTheWorker();
    }

    private static ResponseStatusException composedOnTheWorker() {
        return new ResponseStatusException(HttpStatus.GONE, "Feedback is composed on the worker, not in the sandbox");
    }

    private static <T> T checked(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ResultAdmissionService.StaleAttemptException | AdmissionConflictException conflict) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The review attempt or admitted payload changed", conflict);
        } catch (JobDeliveryException refused) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage(), refused);
        }
    }

    private static ResultAdmissionService.AdmissionIdentity attemptOf(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof ProxyIdentity identity) || identity.job() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Review job credential required");
        var job = identity.job();
        var workerId = identity.workerId();
        if (workerId == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Review worker credential required");
        return new ResultAdmissionService.AdmissionIdentity(job.jobId(), job.workspaceId(), job.attempt(), workerId);
    }
}
