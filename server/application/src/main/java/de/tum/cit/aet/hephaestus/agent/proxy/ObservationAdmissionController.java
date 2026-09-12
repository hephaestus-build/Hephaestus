package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/internal/llm")
@Hidden
@PreAuthorize("isAuthenticated()")
public class ObservationAdmissionController {

    private static final Logger log = LoggerFactory.getLogger(ObservationAdmissionController.class);

    private final ObservationAdmissionService admission;
    private final PracticeReviewRefusalMetrics refusals;

    public ObservationAdmissionController(
            ObservationAdmissionService admission, PracticeReviewRefusalMetrics refusals) {
        this.admission = admission;
        this.refusals = refusals;
    }

    @PostMapping("/admit-observations")
    @WorkspaceAgnostic("Authenticated sandbox token carries and constrains workspace route")
    public ObjectNode admit(@RequestBody JsonNode request, Authentication authentication) {
        if (request.path("schemaVersion").asInt(-1) != 1
                || !request.path("observations").isArray()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Expected schemaVersion 1 and observations array");
        }
        if (!(authentication.getPrincipal() instanceof ProxyRouting routing)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent-job credential required");
        }
        UUID jobId = routing.sourceId();
        if (jobId == null
                || routing.attempt() == null
                || routing.attempt().sourceType() != LlmUsageSourceType.AGENT_JOB
                || routing.workspaceId() == null
                || routing.attempt().workerId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent-job credential required");
        }
        var identity = new ObservationAdmissionService.AdmissionIdentity(
                jobId,
                routing.workspaceId(),
                routing.attempt().number(),
                routing.attempt().workerId());
        // A refusal is 422, never 5xx: the runner repeats only a 5xx or a transport failure, and repeating a
        // decided submission would put the same question again. The reason is already recorded on the job.
        try {
            return admission.admit(identity, request.path("observations"));
        } catch (ObservationsRefusedException e) {
            refusals.recordExecutionRefusal(e.reasonCode());
            log.info("Refused this review's observations ({}): jobId={}, {}", e.reasonCode(), jobId, e.reason());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.reason(), e);
        } catch (JobDeliveryException e) {
            refusals.recordExecutionRefusal(ObservationAdmissionService.INADMISSIBLE_REASON_CODE);
            log.info("Refused this review's observations as inadmissible: jobId={}, {}", jobId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), e);
        } catch (ObservationAdmissionService.StaleAttemptException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Review attempt no longer owns this job", e);
        } catch (ObservationAdmissionService.AdmissionConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Observations differ from the admitted payload", e);
        } catch (IllegalStateException e) {
            // The evidence store or a verifier failed, not the submission: a 5xx the runner repeats. Mapped
            // here because the shared advice would answer 409, which the runner treats as final.
            log.error("Observation admission could not be completed: jobId={}", jobId, e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Observation admission could not be completed", e);
        }
    }
}
