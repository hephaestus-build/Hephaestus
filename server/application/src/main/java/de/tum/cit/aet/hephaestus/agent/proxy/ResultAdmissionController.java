package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
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
            summary = "Prepare feedback for an admitted item review",
            description =
                    "Requires the same fenced worker credential and admitted ITEM review. Preparation persists the"
                            + " package before a provider write.",
            security = @SecurityRequirement(name = "workerBearer"))
    @PostMapping("/prepare-feedback")
    @WorkspaceAgnostic("The worker credential supplies the workspace and exact job attempt")
    public PrepareFeedbackResponseDTO prepareFeedback(
            @Valid @RequestBody PrepareFeedbackRequestDTO request, Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(() -> admission.prepareFeedback(identity, request));
    }

    @Operation(
            operationId = "readPublicFeedbackHistory",
            summary = "Read authorized feedback receipts for the current item review",
            description =
                    "Requires the same fenced worker credential and admitted ITEM review. Returns confirmed same-item,"
                            + " same-developer copy history; a refused read is not an empty history.",
            security = @SecurityRequirement(name = "workerBearer"))
    @PostMapping("/public-feedback-history")
    @WorkspaceAgnostic("The worker credential supplies the workspace and exact job attempt")
    public PublicFeedbackHistoryDTO readPublicFeedbackHistory(Authentication authentication) {
        var identity = attemptOf(authentication);
        return checked(() -> admission.publicFeedbackHistory(identity));
    }

    private static <T> T checked(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ResultAdmissionService.StaleAttemptException | AdmissionConflictException conflict) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The review attempt or admitted payload changed", conflict);
        } catch (JobDeliveryException | ResultAdmissionService.PublicHistoryRefusedException refused) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage(), refused);
        } catch (FeedbackTextRefusedException text) {
            // Nothing was prepared; the rule and field tell the composer which text to rewrite before it sends again.
            var refused = new ResponseStatusException(HttpStatus.CONFLICT, text.getMessage(), text);
            refused.getBody().setProperty("code", "FEEDBACK_TEXT_REFUSED");
            refused.getBody().setProperty("destination", text.destination().name());
            refused.getBody().setProperty("rule", text.rule());
            refused.getBody().setProperty("field", text.field());
            throw refused;
        } catch (PublicationContextRefusedException stale) {
            // Nothing was prepared; the code tells the runner to compose again from a fresh history read.
            var refused = new ResponseStatusException(HttpStatus.CONFLICT, stale.getMessage(), stale);
            refused.getBody().setProperty("code", stale.reason().name());
            throw refused;
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
